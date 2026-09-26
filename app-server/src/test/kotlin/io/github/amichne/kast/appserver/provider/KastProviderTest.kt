package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerDispatch
import io.github.amichne.kast.appserver.core.BrokerDispatchRequest
import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.core.ObserverPresentation
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeReadOperation
import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.appserver.runtime.BrokerInvocationApproval
import io.github.amichne.kast.appserver.runtime.ClientConnectionId
import io.github.amichne.kast.appserver.runtime.ExactPlanApprovalOutcome
import io.github.amichne.kast.appserver.runtime.ExactPlanApprovalResolution
import io.github.amichne.kast.appserver.runtime.ExactPlanApprovalSubject
import io.github.amichne.kast.appserver.runtime.HostedApprovalOwnerId
import io.github.amichne.kast.appserver.runtime.HostedPlanApprovalGrant
import io.github.amichne.kast.appserver.runtime.PendingExactPlanApproval
import io.github.amichne.kast.appserver.runtime.SharedTaskSessions
import io.github.amichne.kast.appserver.runtime.document
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastProviderTest {
    @Test
    fun `phase tools are absent even when an old approval grant is supplied`(@TempDir temporary: Path) =
        runBlocking<Unit> {
            val cwd = temporary.toRealPath()
            val executor = RecordingCatalogSource(capabilitySchema().replace("\"plan\"", "\"planIdentity\""))
            val operations = mutableListOf<ExistingIdeOperation>()
            val broker =
                approvedBroker(
                    temporary,
                    executor,
                    ExistingIdeClient { _, operation ->
                        operations += operation
                        hostRejection()
                    },
                )
            val grant = approvedGrant(cwd)
            val approved =
                BrokerInvocationContext.admit(
                        threadId = "thread-1",
                        turnId = "turn-1",
                        callId = "call-1",
                        workingDirectory = cwd,
                        approval = BrokerInvocationApproval.Granted(grant),
                    )
                    .refinedValue()
            val arguments = Json.encodeToJsonElement(PhaseIdentityArguments("plan:${"a".repeat(64)}")).jsonObject
            val rejected =
                assertInstanceOf(
                    BrokerDispatch.Rejected::class.java,
                    broker.dispatch(
                        BrokerDispatchRequest(
                            ToolAddress(namespace("kast"), toolName("change_apply")),
                            arguments,
                            approved,
                        )
                    ),
                )
            assertInstanceOf(BrokerFailure.UnknownTool::class.java, rejected.failure)
            assertTrue(operations.isEmpty())
            val count = operations.size
            val substituted =
                broker.dispatch(
                    BrokerDispatchRequest(
                        ToolAddress(namespace("kast"), toolName("change_apply")),
                        Json.encodeToJsonElement(PhaseIdentityArguments("plan:${"c".repeat(64)}")).jsonObject,
                        approved,
                    )
                )
            assertInstanceOf(BrokerDispatch.Rejected::class.java, substituted)
            assertEquals(count, operations.size)
            assertGrantContext(cwd, grant)
        }

    private fun assertGrantContext(cwd: Path, grant: HostedPlanApprovalGrant) {
        assertInstanceOf(
            Refinement.Rejected::class.java,
            BrokerInvocationContext.admit(
                threadId = "thread-1",
                turnId = "turn-other",
                callId = "call-1",
                workingDirectory = cwd,
                approval = BrokerInvocationApproval.Granted(grant),
            ),
        )
    }

    @Serializable
    private data class HostRejection(val type: String = "HOST_REJECTED", val failure: String = "DIRTY_DOCUMENTS")

    private fun hostRejection() =
        ExistingIdeExchange.HostRejected(
            CanonicalJsonDocument.generated(HostRejection.serializer()).create(HostRejection())
        )

    private fun searchInput(): JsonElement = io.github.amichne.kast.appserver.publicNameQuery("Thing")

    private suspend fun approvedBroker(
        temporary: Path,
        executor: RecordingCatalogSource,
        client: ExistingIdeClient,
    ): Broker {
        val cwd = temporary.toRealPath()
        val options =
            KastProviderOptions(
                catalogSource = executor,
                roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(CanonicalRoot(cwd)) },
                ideClient = client,
            )
        val qualification = KastProviderQualifier.qualify(options) as KastProviderQualification.Qualified
        return Broker.create(listOf(qualification.registration), BrokerLimits.defaults()).validatedValue()
    }

    private fun approvedGrant(cwd: Path): HostedPlanApprovalGrant {
        val original = context(cwd)
        val subject =
            ExactPlanApprovalSubject.admit(
                    planIdentity = "a".repeat(64),
                    hostedChallenge = "b".repeat(64),
                    root = original.workingDirectory,
                    host = HostedApprovalOwnerId.admit("11111111-1111-1111-1111-111111111111").refinedValue(),
                )
                .refinedValue()
        val tasks = SharedTaskSessions()
        val controller = ClientConnectionId.fresh()
        tasks.connect(controller)
        tasks.attach(original.threadId, controller)
        val pending = PendingExactPlanApproval.open(subject, original, tasks).refinedValue()
        val resolution =
            pending.respond(controller, buildJsonObject { put("decision", "accept") })
                as ExactPlanApprovalResolution.Resolved
        val proof = (resolution.outcome as ExactPlanApprovalOutcome.Approved).proof
        return HostedPlanApprovalGrant.fromSignedControllerApproval(
                proof,
                "e30.${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64))}",
            )
            .refinedValue()
    }

    @Test
    fun `zero exit host rejections retain details but never present successful observations`(@TempDir temporary: Path) =
        runBlocking {
            val executable = executable(temporary.resolve("kast"))
            val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
            for (rejection in
                listOf(
                    checkNotNull(javaClass.getResource("/canonical-rejected-presentation.json")).readText(),
                    """{"type":"HOST_REJECTED","failure":"DEADLINE_EXCEEDED"}""",
                    """{"schemaVersion":1,"outcome":"rejected","failure":"DIRTY_DOCUMENTS","detail":"saved content required","stage":"EPOCH_OBSERVATION"}""",
                )) {
                val options =
                    KastProviderOptions(
                        catalogSource = RecordingCatalogSource(installedKastCatalogFixture()),
                        roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(CanonicalRoot(cwd)) },
                        ideClient =
                            ExistingIdeClient { _, _ ->
                                ExistingIdeExchange.HostRejected(
                                    CanonicalJsonDocument.generated(JsonElement.serializer())
                                        .create(Json.parseToJsonElement(rejection))
                                )
                            },
                    )
                val qualified =
                    assertInstanceOf(
                        KastProviderQualification.Qualified::class.java,
                        KastProviderQualifier.qualify(options),
                    )
                val broker = Broker.create(listOf(qualified.registration), BrokerLimits.defaults()).validatedValue()
                val result =
                    assertInstanceOf(
                        BrokerDispatch.Completed::class.java,
                        broker.dispatch(
                            BrokerDispatchRequest(
                                ToolAddress(namespace("kast"), toolName("query_symbols")),
                                searchInput(),
                                context(cwd),
                            )
                        ),
                    )
                assertEquals(false, result.presentation.success)
                assertEquals(ObserverPresentation.None, result.presentation.observer)
                val envelope = Json.parseToJsonElement(result.presentation.content.single().text).jsonObject
                assertEquals(JsonPrimitive("completed"), envelope["status"])
                assertEquals(Json.parseToJsonElement(rejection), envelope["document"])
            }
        }

    @Test
    fun `applied change observer retains a typed native diff and rejects escaped paths`() {
        val presentation = observerPresentation("change.apply", KastObserverFixtures.changeApply)
        val changes = (presentation as ObserverPresentation.FileChanges).files.entries
        assertEquals(1, changes.size)
        assertEquals(
            "cli/src/main/kotlin/sample/EventConsumer.kt",
            changes.single().path.value,
        )
        assertEquals(
            "@@ class EventConsumer @@\n-    fun consume() = old()\n+    fun consume() = new()",
            changes.single().diff.value,
        )
        assertEquals(
            ObserverPresentation.None,
            observerPresentation(
                "change.apply",
                KastObserverFixtures.changeApply.replace(
                    "cli/src/main/kotlin/sample/EventConsumer.kt",
                    "../outside.kt",
                ),
            ),
        )
    }

    @Test
    fun `source observer preserves snapshot proven one-based line coordinates`() {
        val source =
            KastObserverFixtures.sourceRead.replace(
                "\"type\": \"returned\",",
                "\"type\": \"returned\", \"lines\": {\"startInclusive\": 4, \"endInclusive\": 8},",
            )
        assertTrue("lines 4–8" in observer("source.read", source))
        assertEquals(
            ObserverPresentation.None,
            observerPresentation("source.read", source.replace("\"endInclusive\": 8", "\"endInclusive\": 0")),
        )
        assertEquals(
            ObserverPresentation.None,
            observerPresentation(
                "source.read",
                source.replace("{\"startInclusive\": 4, \"endInclusive\": 8}", "false"),
            ),
        )
    }

    @Test
    fun `diagnostic observer shows severity location and message without selectors`() {
        val presentation =
            observer(
                "diagnostic.check",
                """
                {"status":"completed","document":{"operation":"diagnostic.check","status":"complete",
                 "diagnostics":[{"severity":"error","code":"UNRESOLVED_REFERENCE","message":"Unresolved reference: Missing",
                 "location":{"candidateSelector":"candidate:v2:hidden","file":"src/Example.kt",
                 "range":{"startInclusive":17,"endExclusive":24}}}]}}
                """
                    .trimIndent(),
            )
        assertTrue("error" in presentation)
        assertTrue("Example.kt" in presentation)
        assertTrue("Unresolved reference: Missing" in presentation)
        assertTrue("candidate:v2:" !in presentation)
    }

    @Test
    fun `projection rejects missing or weakened canonical execution budgets`(@TempDir temporary: Path) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        for ((schema, failure) in
            listOf(
                capabilitySchema().replace("\"schemaVersion\":15", "\"schemaVersion\":8") to
                    KastQualificationFailure.SCHEMA_INCOMPATIBLE,
                capabilitySchema().replace("\"operationMillis\":60000", "\"operationMillis\":30000") to
                    KastQualificationFailure.SCHEMA_INCOMPATIBLE,
                capabilitySchema()
                    .replace("\"executionBudget\":{\"readinessMillis\":1020000,\"operationMillis\":60000},", "") to
                    KastQualificationFailure.SCHEMA_INVALID,
            )) {
            val options = KastProviderOptions(catalogSource = RecordingCatalogSource(schema))
            assertEquals(
                KastProviderQualification.Rejected(failure),
                KastProviderQualifier.qualify(options),
            )
        }
    }

    @Test
    fun `first invocation uses the canonical semantic boundary without a child process`(@TempDir temporary: Path) =
        runBlocking {
            val cwd = temporary.toRealPath()
            val executor = RecordingCatalogSource(installedKastCatalogFixture())
            val operations = mutableListOf<ExistingIdeOperation>()
            val options =
                KastProviderOptions(
                    catalogSource = executor,
                    roots = CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(CanonicalRoot(cwd)) },
                    ideClient =
                        ExistingIdeClient { _, operation ->
                            operations += operation
                            hostRejection()
                        },
                )
            val qualified =
                assertInstanceOf(
                    KastProviderQualification.Qualified::class.java,
                    KastProviderQualifier.qualify(options),
                )
            val broker = Broker.create(listOf(qualified.registration), BrokerLimits.defaults()).validatedValue()
            val result =
                broker.dispatch(
                    BrokerDispatchRequest(
                        ToolAddress(namespace("kast"), toolName("query_symbols")),
                        searchInput(),
                        context(cwd),
                    )
                )
            assertInstanceOf(BrokerDispatch.Completed::class.java, result, result.toString())
            val read = assertInstanceOf(ExistingIdeOperation.Read::class.java, operations.single())
            assertEquals(ExistingIdeReadOperation.QUERY_RUN, read.kind)
            assertEquals(2, executor.reads)
        }

    @Test
    fun `complete suite retains one change route and omits phase tools`(@TempDir temporary: Path) =
        runBlocking<Unit> {
            val executable = executable(temporary.resolve("kast"))
            val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
            val executor = RecordingCatalogSource(schema = capabilitySchema())
            val options = KastProviderOptions(catalogSource = executor)
            val qualification =
                assertInstanceOf(
                    KastProviderQualification.Qualified::class.java,
                    KastProviderQualifier.qualify(options),
                )
            val broker =
                Broker.create(
                        listOf(qualification.registration),
                        BrokerLimits.defaults(),
                    )
                    .validatedValue()

            assertCompleteFixtureCatalog(qualification, broker)
            val completed =
                broker.dispatch(
                    BrokerDispatchRequest(
                        ToolAddress(namespace("kast"), toolName("change_apply")),
                        Json.encodeToJsonElement(ApprovalPlanArguments("plan-1")).jsonObject,
                        context(cwd),
                    )
                )
            val explicit =
                broker.dispatch(
                    BrokerDispatchRequest(
                        ToolAddress(namespace("kast"), toolName("query_symbols")),
                        searchInput().jsonObject,
                        context(cwd),
                    )
                )

            val rejected = assertInstanceOf(BrokerDispatch.Rejected::class.java, completed)
            assertInstanceOf(BrokerFailure.UnknownTool::class.java, rejected.failure)
            assertEquals(2, executor.reads)
            val invalidRead = assertInstanceOf(BrokerDispatch.Rejected::class.java, explicit)
            val invalidInput =
                assertInstanceOf(BrokerFailure.ProviderInvocationRejected::class.java, invalidRead.failure)
            assertEquals(ProviderFailureCode.WORKSPACE_ROOT_MARKER_NOT_FOUND, invalidInput.code)
            assertEquals(
                2,
                executor.reads,
            )
        }

    @Test
    fun `complete suite publishes all qualified tools`(@TempDir temporary: Path) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val options = KastProviderOptions(catalogSource = RecordingCatalogSource(schema = capabilitySchema()))
        val qualification =
            assertInstanceOf(
                KastProviderQualification.Qualified::class.java,
                KastProviderQualifier.qualify(options),
            )
        val broker =
            Broker.create(
                    listOf(qualification.registration),
                    BrokerLimits.defaults(),
                )
                .validatedValue()

        assertEquals(
            io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions.all.map { it.name.value }.sorted(),
            broker.catalog.namespaces.single().tools.map { tool -> tool.name.value },
        )
        assertEquals(
            io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions.all.mapTo(linkedSetOf()) {
                it.name.value
            },
            qualification.bootstrap.tools.definitions.mapTo(linkedSetOf()) { it.name.value },
        )
    }

    @Test
    fun `supported Kast operations produce selector-free observer Markdown`() {
        val source =
            observer(
                "source.read",
                """
                {
                  "status": "completed",
                  "document": {
                    "operation": "source.read",
                    "status": "complete",
                    "snapshot": {
                      "canonicalRoot": "/workspace",
                      "generation": 17,
                      "sourceState": "sha256:hidden",
                      "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
                      "textIdentity": "sha256:hidden",
                      "coordinateUnit": "utf16-code-unit",
                      "length": 42
                    },
                    "region": {
                      "kind": "declaration",
                      "selection": {
                        "selector": "source-selector-v1:opaque",
                        "range": {"startInclusive": 17, "endExclusive": 59}
                      }
                    },
                    "entities": [],
                    "text": {
                      "type": "returned",
                      "selection": {
                        "selector": "source-selector-v1:opaque",
                        "range": {"startInclusive": 17, "endExclusive": 59}
                      },
                      "text": "class EventConsumer(\n    private val source: EventSource,\n)"
                    }
                  }
                }
                """
                    .trimIndent(),
            )

        assertEquals(
            """
            **Kast · source**

            [EventConsumer.kt](<events/core/src/main/kotlin/sample/EventConsumer.kt>)

            ```kotlin
            class EventConsumer(
                private val source: EventSource,
            )
            ```
            """
                .trimIndent(),
            source,
        )
        listOf(source).forEach { markdown ->
            FORBIDDEN_OBSERVER_TOKENS.forEach { forbidden ->
                check(!markdown.contains(forbidden)) { "Observer Markdown leaked $forbidden" }
            }
            check(!markdown.contains("/workspace")) { "Observer Markdown leaked workspace root" }
        }
    }

    @Test
    fun `qualified Kast observations remain visibly incomplete`() {
        val source =
            observer(
                "source.read",
                """{"status":"completed","document":{"operation":"source.read","status":"qualified","snapshot":{"canonicalRoot":"/workspace","generation":17,"sourceState":"state","file":"src/EventConsumer.kt","textIdentity":"identity","coordinateUnit":"utf16-code-unit","length":20},"region":{"kind":"declaration","selection":{"selector":"source-selector-v1:opaque","range":{"startInclusive":3,"endExclusive":20}}},"entities":[],"text":{"type":"withheld","reason":"byte-limit-reached"},"qualification":{"knownMinimumEntityCount":0,"limitations":["text-byte-limit-reached"],"continuation":{"type":"available","continuation":"continuation:opaque"}}}}""",
            )
        val semantic =
            observer(
                "query.run",
                KastObserverFixtures.qualifiedQueryOccurrences,
            )
        val walk = observer("query.run", KastObserverFixtures.qualifiedQueryWalk)

        listOf(source, semantic, walk).forEach { markdown ->
            check(markdown.contains("> Qualified — evidence incomplete"))
            check(!markdown.contains("compiler-confirmed"))
            FORBIDDEN_OBSERVER_TOKENS.forEach { forbidden -> check(!markdown.contains(forbidden)) }
        }
    }

    @Test
    fun `semantic query leads with related symbols and hides protocol evidence`() {
        val semanticQuery = observer("query.run", KastObserverFixtures.queryOccurrences)

        assertEquals(
            """
            **Kast · query**

            **2 compiler-confirmed callers**

            | Symbol | Kind | File |
            |---|---|---|
            | `CheckoutService` | class-like | [CheckoutService.kt](<checkout/core/src/main/kotlin/sample/CheckoutService.kt>) |
            | `recordEvent` | function | [AuditSink.kt](<audit/src/main/kotlin/sample/AuditSink.kt>) |
            """
                .trimIndent(),
            semanticQuery,
        )
        FORBIDDEN_OBSERVER_TOKENS.forEach { forbidden ->
            check(!semanticQuery.contains(forbidden)) { "Observer Markdown leaked $forbidden" }
        }
    }

    @Test
    fun `query walk leads with depth bearing related symbols`() {
        val walk = observer("query.run", KastObserverFixtures.queryWalk)

        assertEquals(
            """
            **Kast · query walk**

            **2 compiler-confirmed callers** · 2 hops

            | Depth | Symbol | Kind | File |
            |---:|---|---|---|
            | 1 | `CheckoutService` | class-like | [CheckoutService.kt](<checkout/core/src/main/kotlin/sample/CheckoutService.kt>) |
            | 2 | `recordEvent` | function | [AuditSink.kt](<audit/src/main/kotlin/sample/AuditSink.kt>) |

            1 walk observation
            """
                .trimIndent(),
            walk,
        )
        FORBIDDEN_OBSERVER_TOKENS.forEach { forbidden ->
            check(!walk.contains(forbidden)) { "Observer Markdown leaked $forbidden" }
        }
        check(!walk.contains("/workspace")) { "Observer Markdown leaked workspace root" }
    }

    @Test
    fun `semantic and walk observations fail closed on malformed record depth`() {
        val mixedRelations =
            observerPresentation(
                "query.run",
                KastObserverFixtures.mixedQueryOccurrences,
            )
        val corruptedWalk = KastObserverFixtures.queryWalk.replaceFirst("\"depth\":1", "\"depth\":0")
        check(corruptedWalk != KastObserverFixtures.queryWalk)
        val mismatchedWalk = observerPresentation("query.run", corruptedWalk)

        assertEquals(ObserverPresentation.None, mixedRelations)
        assertEquals(ObserverPresentation.None, mismatchedWalk)
    }

    @Test
    fun `absolute Kast file paths are relative to the admitted invocation directory`(@TempDir temporary: Path) {
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val file = workspace.resolve("src/main/kotlin/sample/EventConsumer.kt")
        val rendered = observer(
            "source.read",
            KastObserverFixtures.sourceRead
                .replace("/workspace", workspace.toString())
                .replace("events/core/src/main/kotlin/sample/EventConsumer.kt", file.toString()),
            workspace,
        )

        check(rendered.contains("[EventConsumer.kt](<src/main/kotlin/sample/EventConsumer.kt>)"))
        check(!rendered.contains(workspace.toString()))
    }

    @Test
    fun `unsupported malformed and contradictory observations fail closed`() {
        val malformed = observerPresentation(
            "query.run",
            KastObserverFixtures.queryOccurrences.replace("\"items\"", "\"missing_items\""),
        )
        val mismatched = observerPresentation(
            "query.run",
            """{"status":"completed","document":{"operation":"source.read","status":"complete","items":[]}}""",
        )
        val unsupported =
            observerPresentation(
                "diagnostic.check",
                """{"status":"completed","document":{"operation":"diagnostic.check","status":"complete"}}""",
            )

        assertEquals(ObserverPresentation.None, malformed)
        assertEquals(ObserverPresentation.None, mismatched)
        assertEquals(ObserverPresentation.None, unsupported)
    }

    @Test
    fun `provider start rejects contract drift before invocation`(@TempDir temporary: Path) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(cwd.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val originalSchema = capabilitySchema()
        val changedSchema =
            originalSchema.replace(
                "\"outputSchema\":{\"type\":\"object\"}",
                "\"outputSchema\":{\"type\":\"object\",\"description\":\"changed\"}",
            )
        check(changedSchema != originalSchema)
        val executor =
            RecordingCatalogSource(
                schema = originalSchema,
                replacementSchema = changedSchema,
            )
        val options = KastProviderOptions(catalogSource = executor)
        val qualification = KastProviderQualifier.qualify(options) as KastProviderQualification.Qualified
        val broker =
            Broker.create(
                    listOf(qualification.registration),
                    BrokerLimits.defaults(),
                )
                .validatedValue()

        val dispatch =
            broker.dispatch(
                BrokerDispatchRequest(
                    ToolAddress(namespace("kast"), toolName("query_symbols")),
                    searchInput().jsonObject,
                    context(cwd),
                )
            ) as BrokerDispatch.Rejected

        val failure =
            assertInstanceOf(BrokerFailure.ProviderStartupRejected::class.java, dispatch.failure, dispatch.failure.toString())
        assertEquals(ProviderFailureCode.KAST_CONTRACT_CHANGED, failure.code)
        assertEquals(2, executor.reads)
    }

    @Test
    fun `open input object is rejected before extra arguments can be dropped`(@TempDir temporary: Path) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val openInput =
            capabilitySchema()
                .replaceFirst(
                    """"additionalProperties":false,""",
                    "",
                )
        val options = KastProviderOptions(catalogSource = RecordingCatalogSource(schema = openInput))

        assertEquals(
            KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE),
            KastProviderQualifier.qualify(options),
        )
    }

    private fun assertCompleteFixtureCatalog(qualification: KastProviderQualification.Qualified, broker: Broker) {
        assertEquals(
            io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions.all.map { it.name.value }.sorted(),
            broker.catalog.namespaces.single().tools.map { tool -> tool.name.value },
        )
        assertEquals(
            io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions.all.mapTo(linkedSetOf()) {
                it.name.value
            },
            qualification.bootstrap.tools.definitions.mapTo(linkedSetOf()) { it.name.value },
        )
    }

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }

    private fun context(cwd: Path): BrokerInvocationContext =
        BrokerInvocationContext.admit(
                "thread-1",
                "turn-1",
                "call-1",
                cwd,
            )
            .refinedValue()

    private fun namespace(value: String): ProviderNamespace = ProviderNamespace.admit(value).refinedValue()

    private fun toolName(value: String): ToolName = ToolName.admit(value).refinedValue()

    private fun observer(
        operation: String,
        document: String,
        observerDirectory: Path = Path.of(".").toRealPath(),
    ): String =
        (observerPresentation(operation, document, observerDirectory) as ObserverPresentation.Markdown).source.value

    private fun observerPresentation(
        operation: String,
        document: String,
        observerDirectory: Path = Path.of(".").toRealPath(),
    ): ObserverPresentation =
        KastObserverProjector.project(
            checkNotNull(KastOperationId.admit(operation)),
            KastInvocationOutput(
                Json.parseToJsonElement(document).jsonObject,
                success = true,
                observerDirectory = checkNotNull(CanonicalBrokerDirectory.admit(observerDirectory)),
            ),
        )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
        }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong =
        when (this) {
            is Validation.Validated -> value
            is Validation.Rejected -> throw AssertionError("Expected validation, received $failures")
        }

    private companion object {
        val FORBIDDEN_OBSERVER_TOKENS =
            listOf(
                "candidate:v",
                "exact:v",
                "sha256:",
                "canonical-signature-sha256",
                "source-selector-v",
                "continuation:opaque",
            )
    }
}

@Serializable private data class ApprovalPlanArguments(val plan: String)

@Serializable private data class PhaseIdentityArguments(val planIdentity: String)
