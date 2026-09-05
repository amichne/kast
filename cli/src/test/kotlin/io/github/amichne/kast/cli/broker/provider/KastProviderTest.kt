package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerDispatch
import io.github.amichne.kast.cli.broker.core.BrokerDispatchRequest
import io.github.amichne.kast.cli.broker.core.BrokerFailure
import io.github.amichne.kast.cli.broker.core.BrokerInvocationContext
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import io.github.amichne.kast.cli.broker.core.ProviderFailureCode
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ToolAddress
import io.github.amichne.kast.cli.broker.core.ToolContent
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class KastProviderTest {
    @Test
    fun `source observer preserves snapshot proven one-based line coordinates`() {
        val source = KastObserverFixtures.sourceRead.replace(
            "\"type\": \"returned\",",
            "\"type\": \"returned\", \"lines\": {\"startInclusive\": 4, \"endInclusive\": 8},",
        )
        assertTrue("lines 4–8" in observer("source.read", source))
        assertEquals(ObserverPresentation.None,
            observerPresentation("source.read", source.replace("\"endInclusive\": 8", "\"endInclusive\": 0")))
        assertEquals(ObserverPresentation.None, observerPresentation("source.read",
            source.replace("{\"startInclusive\": 4, \"endInclusive\": 8}", "false")))
    }

    @Test
    fun `diagnostic observer shows severity location and message without selectors`() {
        val presentation = observer("diagnostic.check", """
            {"status":"completed","document":{"operation":"diagnostic.check","status":"complete",
             "diagnostics":[{"severity":"error","code":"UNRESOLVED_REFERENCE","message":"Unresolved reference: Missing",
             "location":{"candidateSelector":"candidate:v2:hidden","file":"src/Example.kt",
             "range":{"startInclusive":17,"endExclusive":24}}}]}}
        """.trimIndent())
        assertTrue("error" in presentation)
        assertTrue("Example.kt" in presentation)
        assertTrue("Unresolved reference: Missing" in presentation)
        assertTrue("candidate:v2:" !in presentation)
    }

    @Test
    fun `projection rejects missing or weakened canonical execution budgets`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        for (schema in listOf(
            capabilitySchema().replace("\"operationMillis\": 60000", "\"operationMillis\": 30000"),
            capabilitySchema().replace("\"executionBudget\": {\"readinessMillis\": 1020000, \"operationMillis\": 60000},", ""),
        )) {
            val options = KastProviderOptions.admit(
                executable,
                temporary.toRealPath(),
                RecordingProcessExecutor(schema),
            ).refinedValue()
            assertEquals(
                KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE),
                KastProviderQualifier.qualify(options),
            )
        }
    }

    @Test
    fun `first cold invocation survives thirty seconds and separates readiness from semantic work`(
        @TempDir temporary: Path,
    ) = runTest {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val executor = RecordingProcessExecutor(capabilitySchema(), invocationDelayMillis = 31_000)
        val options = KastProviderOptions.admit(executable, cwd, executor).refinedValue()
        val qualification = assertInstanceOf(
            KastProviderQualification.Qualified::class.java,
            KastProviderQualifier.qualify(options),
        )
        val broker = Broker.create(listOf(qualification.registration), BrokerLimits.defaults())
            .validatedValue()
        val result = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("kast"), toolName("symbol_lookup")),
                buildJsonObject { put("query", "Thing") },
                context(cwd),
            ),
        )
        assertInstanceOf(BrokerDispatch.Completed::class.java, result)
        assertEquals(
            listOf(listOf("start"), listOf("symbol", "discover", "--query", "Thing")),
            executor.requests.filterNot { it.arguments.first().startsWith("--") }
                .map(BrokerProcessRequest::arguments),
        )
        assertEquals(1_020_000L, executor.requests.first { it.arguments == listOf("start") }.timeoutMillis)
        assertEquals(60_000L, executor.requests.last().timeoutMillis)
    }

    @Test
    fun `installed contract publishes and invokes only no-approval tools`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val executor = RecordingProcessExecutor(schema = capabilitySchema())
        val options = KastProviderOptions.admit(executable, cwd, executor).refinedValue()
        val qualification = assertInstanceOf(
            KastProviderQualification.Qualified::class.java,
            KastProviderQualifier.qualify(options),
        )
        val broker = Broker.create(
            listOf(qualification.registration),
            BrokerLimits.defaults(),
        ).validatedValue()

        assertEquals(
            listOf("symbol_lookup"),
            broker.catalog.namespaces.single().tools.map { tool -> tool.name.value },
        )
        val completed = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("kast"), toolName("symbol_lookup")),
                buildJsonObject { put("query", "Thing") },
                context(cwd),
            ),
        )
        val explicit = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("kast"), toolName("change_apply")),
                buildJsonObject { put("plan", "plan-1") },
                context(cwd),
            ),
        )

        assertEquals(true, (completed as BrokerDispatch.Completed).presentation.success)
        assertEquals(
            listOf(
                ToolContent(
                    "{\"document\":{\"items\":[],\"operation\":\"symbol.discover\"," +
                        "\"status\":\"complete\"},\"status\":\"completed\"}",
                ),
            ),
            completed.presentation.content,
        )
        assertEquals(
            "**Kast · symbol**\n\n_No matching symbols._",
            (completed.presentation.observer as ObserverPresentation.Markdown).source.value,
        )
        assertEquals(
            listOf("symbol", "discover", "--query", "Thing"),
            executor.requests.last().arguments,
        )
        assertInstanceOf(BrokerFailure.UnknownTool::class.java, (explicit as BrokerDispatch.Rejected).failure)
        assertEquals(2, executor.requests.count { it.arguments == listOf("--version") })
        assertEquals(2, executor.requests.count { it.arguments == listOf("--schema") })
    }

    @Test
    fun `supported Kast operations produce selector-free observer Markdown`() {
        val discover = observer(
            "symbol.discover",
            """
            {
              "status": "completed",
              "document": {
                "operation": "symbol.discover",
                "status": "complete",
                "items": [{
                  "type": "declaration",
                  "candidateSelector": "candidate:v2:opaque",
                  "kind": "class",
                  "name": "EventConsumer",
                  "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
                  "offset": 17
                }]
              }
            }
            """.trimIndent(),
        )
        val inspect = observer(
            "symbol.inspect",
            """
            {
              "status": "completed",
              "document": {
                "operation": "symbol.inspect",
                "status": "complete",
                "symbol": {
                  "selector": "exact:v2:opaque",
                  "kind": "classlike",
                  "name": "EventConsumer",
                  "qualifiedIdentity": "com.aexp.mobile.one.streaming.events.core.EventConsumer",
                  "file": "events/core/src/main/kotlin/sample/EventConsumer.kt",
                  "range": {"startInclusive": 17, "endExclusive": 140},
                  "compilerEvidence": {
                    "identity": "canonical-signature-sha256-v1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "signature": {
                      "type": "class-like",
                      "qualifiedIdentity": "com.aexp.mobile.one.streaming.events.core.EventConsumer"
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val source = observer(
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
            """.trimIndent(),
        )

        assertEquals(
            """
            **Kast · symbol**

            `EventConsumer` · class

            [EventConsumer.kt](<events/core/src/main/kotlin/sample/EventConsumer.kt>)
            """.trimIndent(),
            discover,
        )
        assertEquals(
            """
            **Kast · symbol**

            `EventConsumer` · class-like · compiler-confirmed

            [EventConsumer.kt](<events/core/src/main/kotlin/sample/EventConsumer.kt>)

            `com.aexp.mobile.one.streaming.events.core.EventConsumer`
            """.trimIndent(),
            inspect,
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
            """.trimIndent(),
            source,
        )
        listOf(discover, inspect, source).forEach { markdown ->
            FORBIDDEN_OBSERVER_TOKENS.forEach { forbidden ->
                check(!markdown.contains(forbidden)) { "Observer Markdown leaked $forbidden" }
            }
            check(!markdown.contains("/workspace")) { "Observer Markdown leaked workspace root" }
        }
    }

    @Test
    fun `qualified Kast observations remain visibly incomplete`() {
        val discover = observer(
            "symbol.discover",
            """{"status":"completed","document":{"operation":"symbol.discover","status":"qualified","items":[{"type":"declaration","candidateSelector":"candidate:v2:opaque","kind":"class","name":"EventConsumer","file":"src/EventConsumer.kt","offset":3}],"qualification":"[result-limit-reached]"}}""",
        )
        val inspect = observer(
            "symbol.inspect",
            """{"status":"completed","document":{"operation":"symbol.inspect","status":"qualified","symbol":{"selector":"exact:v2:opaque","kind":"classlike","name":"EventConsumer","qualifiedIdentity":"sample.EventConsumer","file":"src/EventConsumer.kt","range":{"startInclusive":3,"endExclusive":20},"compilerEvidence":{"identity":"canonical-signature-sha256-v1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","signature":{"type":"class-like","qualifiedIdentity":"sample.EventConsumer"}}},"qualification":"compiler-evidence-incomplete"}}""",
        )
        val source = observer(
            "source.read",
            """{"status":"completed","document":{"operation":"source.read","status":"qualified","snapshot":{"canonicalRoot":"/workspace","generation":17,"sourceState":"state","file":"src/EventConsumer.kt","textIdentity":"identity","coordinateUnit":"utf16-code-unit","length":20},"region":{"kind":"declaration","selection":{"selector":"source-selector-v1:opaque","range":{"startInclusive":3,"endExclusive":20}}},"entities":[],"text":{"type":"withheld","reason":"byte-limit-reached"},"qualification":{"knownMinimumEntityCount":0,"limitations":["text-byte-limit-reached"],"continuation":{"type":"available","continuation":"continuation:opaque"}}}}""",
        )
        val semantic = observer(
            "relation.read",
            KastObserverFixtures.semanticQuery.replaceFirst(
                "\"status\": \"complete\",",
                "\"status\": \"qualified\", \"qualification\": {\"type\": \"terminal_incomplete\", \"knownMinimum\": 2, \"limitations\": [\"result-limit-reached\"]},",
            ),
        )
        val impact = observer(
            "traversal.run",
            KastObserverFixtures.impactAnalysis.replaceFirst(
                "\"status\": \"complete\",",
                "\"status\": \"qualified\", \"qualification\": {\"type\": \"terminal_incomplete\", \"limitations\": [\"depth-limit-reached\"], \"relationLimitations\": []},",
            ),
        )

        listOf(discover, inspect, source, semantic, impact).forEach { markdown ->
            check(markdown.contains("> Qualified — evidence incomplete"))
            check(!markdown.contains("compiler-confirmed"))
            FORBIDDEN_OBSERVER_TOKENS.forEach { forbidden -> check(!markdown.contains(forbidden)) }
        }
    }

    @Test
    fun `semantic query leads with related symbols and hides protocol evidence`() {
        val semanticQuery = observer("relation.read", KastObserverFixtures.semanticQuery)

        assertEquals(
            """
            **Kast · semantic query**

            **2 compiler-confirmed callers**

            | Symbol | Kind | File |
            |---|---|---|
            | `CheckoutService` | class-like | [CheckoutService.kt](<checkout/core/src/main/kotlin/sample/CheckoutService.kt>) |
            | `recordEvent` | function | [AuditSink.kt](<audit/src/main/kotlin/sample/AuditSink.kt>) |
            """.trimIndent(),
            semanticQuery,
        )
        FORBIDDEN_OBSERVER_TOKENS.forEach { forbidden ->
            check(!semanticQuery.contains(forbidden)) { "Observer Markdown leaked $forbidden" }
        }
    }

    @Test
    fun `impact analysis leads with unique affected symbols and keeps snapshot secondary`() {
        val impact = observer("traversal.run", KastObserverFixtures.impactAnalysis)

        assertEquals(
            """
            **Kast · impact analysis**

            **2 affected symbols** · 2 hops

            | Depth | Symbol | Kind | File |
            |---:|---|---|---|
            | 1 | `CheckoutService` | class-like | [CheckoutService.kt](<checkout/core/src/main/kotlin/sample/CheckoutService.kt>) |
            | 2 | `recordEvent` | function | [AuditSink.kt](<audit/src/main/kotlin/sample/AuditSink.kt>) |

            _Callers · generation 42 · 2 compiler-confirmed relationships_
            """.trimIndent(),
            impact,
        )
        FORBIDDEN_OBSERVER_TOKENS.forEach { forbidden ->
            check(!impact.contains(forbidden)) { "Observer Markdown leaked $forbidden" }
        }
        check(!impact.contains("/workspace")) { "Observer Markdown leaked workspace root" }
    }

    @Test
    fun `semantic and impact observations fail closed on contradictory graph structure`() {
        val mixedRelations = observerPresentation(
            "relation.read",
            KastObserverFixtures.semanticQuery.replaceFirst("\"callers\"", "\"callees\""),
        )
        val danglingImpact = observerPresentation(
            "traversal.run",
            KastObserverFixtures.impactAnalysis.replaceFirst("\"source\": 1", "\"source\": 99"),
        )

        assertEquals(ObserverPresentation.None, mixedRelations)
        assertEquals(ObserverPresentation.None, danglingImpact)
    }

    @Test
    fun `absolute Kast file paths are relative to the admitted invocation directory`(
        @TempDir temporary: Path,
    ) {
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val file = workspace.resolve("src/main/kotlin/sample/EventConsumer.kt")
        val rendered = observer(
            "symbol.discover",
            """{"status":"completed","document":{"operation":"symbol.discover","status":"complete","items":[{"type":"declaration","candidateSelector":"candidate:v2:opaque","kind":"symbol","name":"EventConsumer","file":"$file","offset":3}]}}""",
            workspace,
        )

        check(rendered.contains("[EventConsumer.kt](<src/main/kotlin/sample/EventConsumer.kt>)"))
        check(!rendered.contains(workspace.toString()))
    }

    @Test
    fun `unsupported malformed and contradictory observations fail closed`() {
        val malformed = observerPresentation(
            "symbol.inspect",
            """{"status":"completed","document":{"operation":"symbol.inspect","status":"complete","symbol":{"name":"EventConsumer"}}}""",
        )
        val mismatched = observerPresentation(
            "symbol.discover",
            """{"status":"completed","document":{"operation":"source.read","status":"complete","items":[]}}""",
        )
        val unsupported = observerPresentation(
            "diagnostic.check",
            """{"status":"completed","document":{"operation":"diagnostic.check","status":"complete"}}""",
        )

        assertEquals(ObserverPresentation.None, malformed)
        assertEquals(ObserverPresentation.None, mismatched)
        assertEquals(ObserverPresentation.None, unsupported)
    }

    @Test
    fun `provider start rejects contract drift before invocation`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val executor = RecordingProcessExecutor(
            schema = capabilitySchema(),
            replacementSchema = capabilitySchema(description = "Changed installed contract."),
        )
        val options = KastProviderOptions.admit(executable, cwd, executor).refinedValue()
        val qualification = KastProviderQualifier.qualify(options) as KastProviderQualification.Qualified
        val broker = Broker.create(
            listOf(qualification.registration),
            BrokerLimits.defaults(),
        ).validatedValue()

        val dispatch = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("kast"), toolName("symbol_lookup")),
                buildJsonObject { put("query", "Thing") },
                context(cwd),
            ),
        ) as BrokerDispatch.Rejected

        val failure = dispatch.failure as BrokerFailure.ProviderStartupRejected
        assertEquals(ProviderFailureCode.KAST_CONTRACT_CHANGED, failure.code)
        assertEquals(0, executor.requests.count { it.arguments.firstOrNull() == "symbol" })
    }

    @Test
    fun `open input object is rejected before extra arguments can be dropped`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val openInput = capabilitySchema().replaceFirst(
            """"additionalProperties": false,""",
            "",
        )
        val options = KastProviderOptions.admit(
            executable,
            cwd,
            RecordingProcessExecutor(schema = openInput),
        ).refinedValue()

        assertEquals(
            KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE),
            KastProviderQualifier.qualify(options),
        )
    }

    @Test
    fun `initial qualification is bounded even when an executor never returns`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val options = KastProviderOptions.admit(
            executable,
            cwd,
            BrokerProcessExecutor { awaitCancellation() },
            qualificationTimeoutMillis = 50,
        ).refinedValue()

        val result = withTimeout(1_000) { KastProviderQualifier.qualify(options) }

        assertEquals(
            KastProviderQualification.Rejected(KastQualificationFailure.VERSION_UNAVAILABLE),
            result,
        )
    }

    private class RecordingProcessExecutor(
        private val schema: String,
        private val replacementSchema: String = schema,
        private val invocationDelayMillis: Long = 0,
    ) : BrokerProcessExecutor {
        val requests = mutableListOf<BrokerProcessRequest>()
        private var schemaReads = 0

        override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution {
            requests += request
            return when (request.arguments) {
                listOf("--version") -> BrokerProcessExecution.Completed(0, "kast 9.9.9\n", "")
                listOf("--schema") -> {
                    schemaReads += 1
                    BrokerProcessExecution.Completed(
                        0,
                        if (schemaReads == 1) schema else replacementSchema,
                        "",
                    )
                }
                listOf("start") -> {
                    delay(invocationDelayMillis)
                    BrokerProcessExecution.Completed(0, """{"command":"start","status":"complete","runtime":"running"}""", "")
                }
                else -> {
                    delay(invocationDelayMillis)
                    BrokerProcessExecution.Completed(
                        0,
                        """{"operation":"symbol.discover","status":"complete","items":[]}""",
                        "",
                    )
                }
            }
        }
    }

    private fun capabilitySchema(description: String = "Find one symbol."): String =
        """
        {
          "schemaVersion": 1,
          "serverProjection": {
            "schemaVersion": 3,
            "namespace": "kast",
            "tools": [
              {
                "operationId": "symbol.discover",
                "name": "symbol_lookup",
                "description": "$description",
                "deferLoading": true,
                "approvalPolicy": "none",
                "executionBudget": {"readinessMillis": 1020000, "operationMillis": 60000},
                "cliUsage": "kast symbol discover --query VALUE",
                "inputSchema": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["query"],
                  "properties": { "query": { "type": "string", "minLength": 1 } }
                },
                "outputSchema": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["status", "document"],
                  "properties": {
                    "status": { "const": "completed" },
                    "document": { "type": "object" }
                  }
                },
                "invocation": {
                  "type": "CLI",
                  "command": ["symbol", "discover"],
                  "bindings": [
                    { "type": "OPTION", "inputField": "query", "option": "--query" }
                  ]
                }
              },
              {
                "operationId": "change.apply",
                "name": "change_apply",
                "description": "Apply an approved plan.",
                "deferLoading": true,
                "approvalPolicy": "explicit",
                "executionBudget": {"readinessMillis": 1020000, "operationMillis": 60000},
                "cliUsage": "kast change apply --plan VALUE",
                "inputSchema": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["plan"],
                  "properties": { "plan": { "type": "string", "minLength": 1 } }
                },
                "outputSchema": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["status", "document"],
                  "properties": {
                    "status": { "const": "completed" },
                    "document": { "type": "object" }
                  }
                },
                "invocation": {
                  "type": "CLI",
                  "command": ["change", "apply"],
                  "bindings": [
                    { "type": "OPTION", "inputField": "plan", "option": "--plan" }
                  ]
                }
              }
            ]
          }
        }
        """.trimIndent()

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }

    private fun context(cwd: Path): BrokerInvocationContext = BrokerInvocationContext.admit(
        "thread-1",
        "turn-1",
        "call-1",
        cwd,
    ).refinedValue()

    private fun namespace(value: String): ProviderNamespace =
        ProviderNamespace.admit(value).refinedValue()

    private fun toolName(value: String): ToolName = ToolName.admit(value).refinedValue()

    private fun observer(
        operation: String,
        document: String,
        observerDirectory: Path = Path.of(".").toRealPath(),
    ): String = (observerPresentation(operation, document, observerDirectory) as
        ObserverPresentation.Markdown).source.value

    private fun observerPresentation(
        operation: String,
        document: String,
        observerDirectory: Path = Path.of(".").toRealPath(),
    ): ObserverPresentation = KastObserverProjector.project(
        checkNotNull(KastOperationId.admit(operation)),
        KastInvocationOutput(
            Json.parseToJsonElement(document).jsonObject,
            success = true,
            observerDirectory = checkNotNull(CanonicalBrokerDirectory.admit(observerDirectory)),
        ),
    )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
    }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong = when (this) {
        is Validation.Validated -> value
        is Validation.Rejected -> throw AssertionError("Expected validation, received $failures")
    }

    private companion object {
        val FORBIDDEN_OBSERVER_TOKENS = listOf(
            "candidate:v",
            "exact:v",
            "sha256:",
            "canonical-signature-sha256",
            "source-selector-v",
            "continuation:opaque",
        )
    }
}
