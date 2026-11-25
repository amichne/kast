package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceRejectionReason
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitionReason
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import java.nio.file.Path
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

class NativeJavaLocationQueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testClassAndExactMethodStartMixedSourceReads() = runBlocking {
        val (fixture, _) = prepare()
        val owner =
            location(
                file = "Endpoint.java",
                source = java,
                marker = "Endpoint {",
                declaration = "public class Endpoint",
                kind = SymbolKindDocument.CLASSLIKE,
            )
        val method =
            location(
                file = "Endpoint.java",
                source = java,
                marker = "return value;",
                declaration = "public static int relay",
                kind = SymbolKindDocument.FUNCTION,
            )
        val selected =
            listOf(owner to "Endpoint", method to "relay").map { (start, name) ->
                val outcome = execute(fixture, start.request)
                assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
                val rows = (outcome as OperationOutcome.Complete).evidence.payload.items.values
                assertEquals("Exact location for ${start.marker}", 1, rows.size)
                val symbol = rows.single() as QueryResultItemDocument.ExactSymbol
                assertEquals(name, symbol.name?.value)
                assertEquals(start.kind, symbol.kind)
                assertEquals(start.start, symbol.location?.range?.startInclusive?.value)
                assertEquals(fixture.projectPath("src/Endpoint.java"), symbol.location?.file?.value)
                assertNotNull(symbol.signature)
                symbol
            }
        val (classLike, callable) = selected
        assertEquals(CompilerSignatureDocument.ClassLike(packetQueryText("mixed.Endpoint")), classLike.signature)
        assertClassTypeReferences(fixture, classLike)
        val signature = callable.signature as CompilerSignatureDocument.Function
        assertEquals("mixed.Endpoint.relay", signature.qualifiedIdentity.value)
        assertEquals(listOf("kotlin/Int"), signature.valueParameters.values.map { it.value })
        assertMixedSourceUses(fixture, method.request, callable.ref)
    }

    fun testExactNameMatchesLocationAcrossBothDiscoveryRoutes() = runBlocking {
        val (fixture, _) = prepare(javaDiscoverySources)
        val method = request("LegacyRelay.java", javaRelaySource.indexOf("relay(int"))
        assertJavaDiscovery(
            ::request,
            { query ->
                val outcome = execute(fixture, query)
                assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
                (outcome as OperationOutcome.Complete).evidence.payload.items.values.map {
                    it as QueryResultItemDocument.ExactSymbol
                }
            },
        ) { reference ->
            assertMixedSourceUses(
                fixture = fixture,
                selection = method,
                reference = reference,
                sources = javaDiscoverySources,
            )
        }
    }

    fun testFreshExactNameAfterJavaOverloadEdits() = runBlocking {
        val (fixture, files) = prepare(mapOf("LegacyRelay.java" to javaRelaySource))
        val epochs = MovingLiveReadAuthorityFixture(fixture.lease.workspaceRoot)
        val records = ExactRevalidationRecords()
        var authority = epochs.admit()
        assertWarmJavaNames(
            request("LegacyRelay.java", 0),
            { content ->
                project.replaceSource(files.single(), content)
                authority = epochs.advance()
            },
        ) { query ->
            val outcome =
                fixture
                    .reacquiringProtocol(authority, records)
                    .execute(
                        request = query,
                        lease = authority,
                        budget = budget,
                        policy = policy,
                    )
            assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
            (outcome as OperationOutcome.Complete).evidence.payload.items.values.map {
                it as QueryResultItemDocument.ExactSymbol
            }
        }
    }

    fun testFieldAndConstructorLocationsRejectCompleteOnly() = runBlocking {
        val (fixture, _) = prepare()
        val unsupported =
            listOf("ignored = 1", "Endpoint()", "record Pair", "class Local").map { marker ->
                request("Endpoint.java", java.indexOf(marker))
            } + unsupportedJavaByName().map { request("Endpoint.java", 0).copy(from = it) }
        for (query in unsupported) {
            val outcome = execute(fixture, query)
            assertTrue("Unsupported Java declaration: $outcome", outcome is OperationOutcome.Rejected)
            val rejected = (outcome as OperationOutcome.Rejected).reason
            assertTrue(rejected.toString(), rejected is QueryRunRejection.CompletionUnproven)
            val coverage = (rejected as QueryRunRejection.CompletionUnproven).originalCoverage
            assertTrue(coverage.toString(), coverage is QueryCompletionCoverageDocument.Qualified)
            assertEquals(
                listOf(QueryLimitationDocument.DISCOVERY_INCOMPLETE),
                (coverage as QueryCompletionCoverageDocument.Qualified).limitations.values,
            )
        }
    }

    fun testFieldNamesOutsideTheSelectionProveAbsence() = runBlocking {
        val (fixture, _) = prepare(javaDiscoverySources + ("Endpoint.java" to java))
        for (source in excludedJavaByName()) {
            val outcome = execute(fixture, request("Endpoint.java", 0).copy(from = source))
            assertTrue("$source: $outcome", outcome is OperationOutcome.Complete)
            assertEquals(0, (outcome as OperationOutcome.Complete).evidence.payload.items.values.size)
        }
    }

    fun testNestedInterfaceAndInstanceMethodHaveExactIdentities() = runBlocking {
        val (fixture, _) = prepare()
        val types =
            mapOf(
                "Contract {" to CompilerSignatureDocument.ClassLike(packetQueryText("mixed.Endpoint.Contract")),
                "convert(int" to
                    CompilerSignatureDocument.Function(
                        qualifiedIdentity = packetQueryText("mixed.Endpoint.Contract.convert"),
                        receiver = CompilerReceiverDocument.Absent,
                        contextReceivers = packetQueryList(emptyList()),
                        valueParameters = packetQueryList(listOf(packetQueryText("kotlin/Int"))),
                        typeParameterCount = CompilerTypeParameterCountDocument.parse(0).packetQueryRefined(),
                    ),
            )
        for ((marker, expected) in types) {
            val outcome = execute(fixture, request("Endpoint.java", java.indexOf(marker)))
            assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
            val row = (outcome as OperationOutcome.Complete).evidence.payload.items.values.single()
            assertEquals(expected, (row as QueryResultItemDocument.ExactSymbol).signature)
        }
    }

    fun testFreshRunReacquiresShiftedOverloadAndRejectsRenamedTarget() = runBlocking {
        val (fixture, files) = prepare()
        val file = files.single { it.name == "Endpoint.java" }
        val epochs = MovingLiveReadAuthorityFixture(fixture.lease.workspaceRoot)
        val records = ExactRevalidationRecords()
        val original = epochs.admit()
        val protocol = fixture.reacquiringProtocol(original, records)
        val targets =
            listOf("Contract {", "return value;").map { marker ->
                val selected =
                    protocol.execute(
                        request = request("Endpoint.java", java.indexOf(marker)),
                        lease = original,
                        budget = budget,
                        policy = policy,
                    )
                assertTrue(selected.toString(), selected is OperationOutcome.Complete)
                (selected as OperationOutcome.Complete).evidence.payload.items.values.single()
                    as QueryResultItemDocument.ExactSymbol
            }
        val insertion = "// space before the class\n"
        project.replaceSource(file, insertion + java)
        val shifted = epochs.advance()
        for (target in targets) {
            assertShifted(
                fixture = fixture,
                authority = shifted,
                records = records,
                target = target,
                offsetDelta = insertion.length,
            )
        }
        assertRenamedRejected(
            fixture = fixture,
            epochs = epochs,
            records = records,
            file = file,
            use = reuse(targets.last()),
        )
    }

    private suspend fun assertShifted(
        fixture: NativeScopedTraceFixture,
        authority: LiveSemanticReadAuthority,
        records: ExactRevalidationRecords,
        target: QueryResultItemDocument.ExactSymbol,
        offsetDelta: Int,
    ) {
        val reused =
            fixture
                .reacquiringProtocol(authority, records)
                .execute(
                    request = reuse(target),
                    lease = authority,
                    budget = budget,
                    policy = policy,
                )
        assertTrue(reused.toString(), reused is OperationOutcome.Complete)
        val result = (reused as OperationOutcome.Complete).evidence.payload
        val current = result.items.values.single() as QueryResultItemDocument.ExactSymbol
        assertTrue(target.symbolId != current.symbolId)
        assertEquals(target.signature, current.signature)
        assertEquals(
            checkNotNull(target.location).range.startInclusive.value + offsetDelta,
            checkNotNull(current.location).range.startInclusive.value,
        )
        val acquired = checkNotNull(result.referenceAcquisitions).references.single()
        assertEquals(target.ref.token, acquired.previous)
        assertEquals(current.ref.token, acquired.current)
        assertEquals(ReadReferenceAcquisitionReason.STALE_SEMANTIC_AUTHORITY, acquired.reason)
    }

    private suspend fun assertRenamedRejected(
        fixture: NativeScopedTraceFixture,
        epochs: MovingLiveReadAuthorityFixture,
        records: ExactRevalidationRecords,
        file: VirtualFile,
        use: QueryRunRequest.Run,
    ) {
        project.replaceSource(file, java.replace("int relay(int", "int renamed(int").replace("relay(1)", "renamed(1)"))
        val renamed = epochs.advance()
        val rejected =
            fixture
                .reacquiringProtocol(renamed, records)
                .execute(
                    request = use,
                    lease = renamed,
                    budget = budget,
                    policy = policy,
                )
        assertTrue(rejected.toString(), rejected is OperationOutcome.Rejected)
        val reference = (rejected as OperationOutcome.Rejected).reason as QueryRunRejection.ReferenceRejected
        assertEquals(QueryReferenceRejectionReason.REVALIDATION_DECLARATION_MISSING, reference.reason)
    }

    private fun prepare(
        sources: Map<String, String> = mapOf("Endpoint.java" to java, "Consumers.kt" to kotlin)
    ): Pair<NativeScopedTraceFixture, List<VirtualFile>> {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("java-location-jdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativeSourceContextFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepare(sources)
    }
}

private val java = javaLocationSource
private val kotlin = kotlinLocationSource
private val budget = NativePacketQueryServices.budget
private val policy =
    QueryInvocationPolicy(
        previewRows = NativeScopedTraceFixture.resources.resultLimit,
        previewBytesLimit = budget.returnedBytes,
        retainedBytes = budget.returnedBytes,
        previewBytes = CanonicalQueryCliDocuments::previewBytes,
    )
private val domain =
    QueryExpansionScopeDocument.Sources(
        sourceSets = packetQueryList(listOf(packetQueryText("main"))),
        directory = QueryDirectoryScopeDocument(packetQueryText("src"), QueryContainmentDocument.DESCENDANTS),
        sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
        generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
    )

private fun com.intellij.openapi.project.Project.replaceSource(file: VirtualFile, content: String) {
    ApplicationManager.getApplication().invokeAndWait {
        WriteCommandAction.runWriteCommandAction(this) {
            file.setBinaryContent(content.toByteArray())
        }
        PsiDocumentManager.getInstance(this).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(this)
    }
}

private fun location(file: String, source: String, marker: String, declaration: String, kind: SymbolKindDocument) =
    LocationExpectation(
        request = request(file, source.indexOf(marker)),
        marker = marker,
        start = source.indexOf(declaration),
        kind = kind,
    )

private data class LocationExpectation(
    val request: QueryRunRequest.Run,
    val marker: String,
    val start: Int,
    val kind: SymbolKindDocument,
)

private fun request(file: String, offset: Int) =
    QueryRunRequest.Run(
        from = QueryFromDocument.Location(packetQueryText("src/$file"), packetQueryOffset(offset)),
        steps = packetQueryList(emptyList()),
        output =
            QueryOutputDocument.Symbols(
                packetQueryList(
                    listOf(
                        QuerySymbolFieldDocument.NAME,
                        QuerySymbolFieldDocument.LOCATION,
                        QuerySymbolFieldDocument.SIGNATURE,
                    )
                )
            ),
        execution =
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
    )

private suspend fun assertClassTypeReferences(
    fixture: NativeScopedTraceFixture,
    selected: QueryResultItemDocument.ExactSymbol,
) {
    val request =
        reuse(selected)
            .copy(
                steps = packetQueryList(listOf(QueryStepDocument.Related(RelationKindDocument.REFERENCES, domain))),
                output = QueryOutputDocument.Occurrences,
            )
    val outcome = execute(fixture, request)
    assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
    val sites =
        (outcome as OperationOutcome.Complete).evidence.payload.items.values.map { row ->
            val occurrence = (row as QueryResultItemDocument.ReferenceOccurrence).occurrence.occurrence
            fixture.projectPath(occurrence.file.value) to occurrence.range.startInclusive.value
        }
    val javaType = fixture.projectPath("src/Endpoint.java") to java.indexOf("Endpoint sameOwner")
    val kotlinQualifier = fixture.projectPath("src/Consumers.kt") to kotlin.indexOf("Endpoint.relay")
    assertTrue(sites.toString(), javaType in sites)
    assertTrue(sites.toString(), kotlinQualifier in sites)
}

private suspend fun assertMixedSourceUses(
    fixture: NativeScopedTraceFixture,
    selection: QueryRunRequest.Run,
    reference: QueryReferenceDocument.ExactSymbol,
    sources: Map<String, String> = mapOf("Endpoint.java" to java, "Consumers.kt" to kotlin),
) {
    val expectedUses = sources.mapNotNull { (file, source) ->
        val marker = if (file.endsWith(".java")) "relay(1)" else "relay(2)"
        val offset = source.indexOf(marker)
        if (offset < 0) null else fixture.projectPath("src/$file") to offset
    }
    assertEquals(2, expectedUses.size)
    val exact = QueryFromDocument.References(packetQueryList(listOf(reference)))
    for (kind in listOf(RelationKindDocument.REFERENCES, RelationKindDocument.CALLERS)) {
        val request =
            selection.copy(
                from = exact,
                steps = packetQueryList(listOf(QueryStepDocument.Related(kind, domain))),
                output = QueryOutputDocument.Occurrences,
            )
        val outcome = execute(fixture, request)
        assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
        val rows = (outcome as OperationOutcome.Complete).evidence.payload.items.values
        val uses = rows.map { row ->
            val occurrence =
                when (row) {
                    is QueryResultItemDocument.ReferenceOccurrence -> row.occurrence.occurrence
                    is QueryResultItemDocument.Occurrence -> row.relation.occurrence
                    else -> error("Expected occurrence for $kind, got $row")
                }
            fixture.projectPath(occurrence.file.value) to occurrence.range.startInclusive.value
        }
        assertEquals("$kind occurrences", expectedUses.sortedBy { it.first }, uses.sortedBy { it.first })
    }
}

private fun reuse(target: QueryResultItemDocument.ExactSymbol) =
    request("Endpoint.java", 0).copy(from = QueryFromDocument.References(packetQueryList(listOf(target.ref))))

private fun NativeScopedTraceFixture.projectPath(path: String) =
    Path.of(lease.workspaceRoot.value).resolve(path).toString()

private suspend fun execute(fixture: NativeScopedTraceFixture, request: QueryRunRequest.Run) =
    fixture.protocol().execute(request = request, lease = fixture.lease, budget = budget, policy = policy)
