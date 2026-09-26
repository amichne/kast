package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.contract.*
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.readScope
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CanonicalQueryProtocolTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
    private val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined())
    private val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(10).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(10000).refined(),
        )

    private val largerGrant = ExecutionBudgetDocument(maxWorkUnits = WorkUnitLimit.parse(200).refined())

    @Test
    fun `retained empty result is readable without another semantic execution`() = runTest {
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    executions++
                    QueryExecutionResult.Complete(
                        QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                        QueryCoverage.Complete(QueryCount.parse(0).refined()),
                    )
                },
                CanonicalQueryReferences(),
            )
        val first =
            protocol.execute(request().copy(retention = QueryRetentionModeDocument.RETAIN), lease, budget)
                as OperationOutcome.Complete
        val reference = (first.evidence.payload.retention as QueryResultRetention.Retained).reference
        val read =
            protocol.execute(
                QueryRunRequest.ReadResult.symbols(
                    reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                lease,
                budget,
            ) as OperationOutcome.Complete
        assertEquals(1, executions)
        assertEquals(first.evidence.payload.items, read.evidence.payload.items)
        assertEquals(first.evidence.payload.failures, read.evidence.payload.failures)
        assertEquals(QueryResultRetention.Retained(reference), read.evidence.payload.retention)
        assertNull(read.evidence.payload.nextCursor)
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_STALE_BASIS),
            (protocol.execute(
                    QueryRunRequest.ReadResult.symbols(
                        reference,
                        output = QueryOutputDocument.Symbols(bounded(emptyList())),
                    ),
                    SemanticReadLease(root, EvidenceGeneration.parse(8).refined()),
                    budget,
                ) as OperationOutcome.Rejected)
                .reason,
        )
        assertEquals(1, executions)
    }

    @Test
    fun `opaque checkpoint binds query and snapshot while allowing fresh page budget`() = runTest {
        val store = QueryStateStore()
        var executions = 0
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { admitted ->
                    executions++
                    if (admitted.checkpoint == null) {
                        val checkpoint =
                            object : QueryCheckpoint {
                                override val plan = admitted.plan
                                override val lease = admitted.lease
                                override val retainedBytes = 1024L
                            }
                        QueryExecutionResult.Qualified(
                            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                            QueryCoverage.Qualified.create(
                                    QueryCount.parse(0).refined(),
                                    setOf(QueryLimitation.WORK_LIMIT_REACHED),
                                )
                                .refined(),
                            QueryContinuationState.Resumable(checkpoint),
                        )
                    } else
                        QueryExecutionResult.Complete(
                            QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                            QueryCoverage.Complete(QueryCount.parse(0).refined()),
                        )
                },
                CanonicalQueryReferences(),
                store,
            )
        val first = protocol.execute(request(), lease, budget) as OperationOutcome.Qualified
        val token = first.qualification.progress.continuationToken!!
        assertTrue(token.value.length < 64)
        assertEquals(
            ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
            (first.qualification.progress as QueryQualifiedProgressDocument.Resumable).nextAction,
        )
        assertCheckpointBinding(protocol, token)
        assertInstanceOf(
            OperationOutcome.Complete::class.java,
            protocol.execute(
                QueryRunRequest.Resume(token, executionBudget = largerGrant),
                lease,
                budget.copy(returnedBytes = QueryByteLimit.parse(20000).refined()),
            ),
        )
        assertEquals(2, executions)
    }

    private suspend fun assertCheckpointBinding(protocol: CanonicalQueryProtocol, token: QueryExecutionContinuation) {
        // Resume is token-only: a caller cannot provide a conflicting replacement plan.
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_MISMATCH),
            (protocol.execute(
                    QueryRunRequest.Resume(token),
                    SemanticReadLease(root, EvidenceGeneration.parse(8).refined()),
                    budget,
                ) as OperationOutcome.Rejected)
                .reason,
        )
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE),
            (protocol.execute(
                    QueryRunRequest.Resume(
                        QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000002")
                            .refined()
                    ),
                    lease,
                    budget,
                ) as OperationOutcome.Rejected)
                .reason,
        )
    }

    @Test
    fun `host lookup expands the token before canonical authority and kind validation`() {
        val handle = text("candidate:v4:" + "a".repeat(64))
        val tokens = mutableMapOf<ProtocolText, ProtocolText>()
        val transport =
            object : QueryReferenceTransport {
                override fun issue(canonical: ProtocolText): ProtocolText {
                    tokens[handle] = canonical
                    return handle
                }

                override fun restore(token: ProtocolText): CanonicalSelectorDecoding<ProtocolText> =
                    tokens[token]?.let { CanonicalSelectorDecoding.Decoded(it) }
                        ?: CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.STALE_AUTHORITY)
            }
        val references = CanonicalQueryReferences(transport)
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/Subject.kt")).refined()
            )
        val issued =
            (references.issueRangeCandidate(lease, file, 2, 5) as CandidateSelectorTokenIssuance.Issued).selector
        assertEquals(handle, issued)
        val restored = (references.restoreCandidate(issued, lease) as CanonicalSelectorDecoding.Decoded).value
        assertEquals(lease, restored.lease)
        assertEquals(file, (restored as CandidateSelector.Range).file)
        val stale = SemanticReadLease(root, EvidenceGeneration.parse(8).refined())
        assertEquals(
            CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
            (references.restoreCandidate(issued, stale) as CanonicalSelectorDecoding.Rejected).failure,
        )
        assertTrue(references.restoreExact(issued, lease) is CanonicalSelectorDecoding.Rejected)
        tokens.clear()
        assertEquals(
            CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
            (references.restoreCandidate(issued, lease) as CanonicalSelectorDecoding.Rejected).failure,
        )
    }

    @Test
    fun `host supplied authority and budget reach query execution with exact scope`() = runTest {
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { request ->
                    assertSame(lease, request.lease)
                    assertSame(budget, request.budget)
                    val source = (request.plan as AdmittedQueryPlan.Symbols).source
                    val scope = source.scope as QueryScope.Restricted
                    assertEquals(
                        setOf("main", "test"),
                        (scope.sourceSets as SymbolDiscoverySourceSets.Exact).values.map { it.value }.toSet(),
                    )
                    QueryExecutionResult.Complete(
                        QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                        QueryCoverage.Complete(QueryCount.parse(0).refined()),
                    )
                },
                CanonicalQueryReferences(),
            )
        val result = protocol.execute(request(), lease, budget)
        assertEquals(EvidenceBasis.Published(lease.generation), (result as OperationOutcome.Complete).evidence.basis)
    }

    @Test
    fun `qualified empty query stays qualified and semantic rejection stays rejected`() = runTest {
        val coverage =
            QueryCoverage.Qualified.create(
                    QueryCount.parse(0).refined(),
                    setOf(QueryLimitation.RESULT_LIMIT_REACHED),
                )
                .refined()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations {
                    QueryExecutionResult.Qualified(
                        QueryResult(QueryRows.Symbols.of(emptyList()), emptyList()),
                        coverage,
                    )
                },
                CanonicalQueryReferences(),
            )
        assertTrue(protocol.execute(request(), lease, budget) is OperationOutcome.Qualified)
        val rejected =
            CanonicalQueryProtocol(
                    QueryOperations {
                        QueryExecutionResult.Rejected(QueryExecutionRejection.BUDGET_REJECTED)
                    },
                    CanonicalQueryReferences(),
                )
                .execute(request(), lease, budget)
        assertTrue(rejected is OperationOutcome.Rejected)
    }

    @Test
    fun `constrained candidate reference restores scope and preserves published authority`() {
        val constraints =
            SymbolDiscoveryConstraints(
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse("src").refined(),
                    SymbolDiscoveryContainment.DESCENDANTS,
                ),
                SymbolDiscoveryPackageConstraint(
                    SymbolDiscoveryPackage.parse("example").refined(),
                    SymbolDiscoveryContainment.DIRECT,
                ),
                SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined(),
                SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("integrationTest").refined()))
                    .refined(),
            )
        val file = Path.of("/workspace/src/Subject.kt")
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.CLASS,
                    "Subject",
                    lease,
                    file,
                    file.toUri().toString(),
                    0,
                )
                .refined()
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val selection = SymbolDiscoverySelection.restore(lease, scope, candidate, constraints).refined()
        val references = CanonicalQueryReferences()
        val token = (references.issueDeclarationCandidate(selection) as CandidateSelectorTokenIssuance.Issued).selector
        assertTrue(token.value.startsWith("candidate:v3:"))
        val restored =
            (references.restoreCandidate(token, lease) as CanonicalSelectorDecoding.Decoded).value
                as CandidateSelector.Declaration
        assertEquals(constraints, restored.selection.constraints)
        val stale = SemanticReadLease(root, EvidenceGeneration.parse(8).refined())
        assertTrue(references.restoreCandidate(token, stale) is CanonicalSelectorDecoding.Rejected)
    }

    @Test
    fun `file and text batch references retain their original read scope`() {
        val constraints =
            SymbolDiscoveryConstraints(
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse("src").refined(),
                    SymbolDiscoveryContainment.DESCENDANTS,
                ),
                SymbolDiscoveryPackageConstraint(
                    SymbolDiscoveryPackage.parse("example").refined(),
                    SymbolDiscoveryContainment.DIRECT,
                ),
                sourceSets =
                    SymbolDiscoverySourceSets.Exact.from(
                            setOf(WorkspaceSourceSetName.parse("integrationTest").refined())
                        )
                        .refined(),
            )
        val path = Path.of("/workspace/src/Subject.kt")
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val maximum = SymbolDiscoveryBudget(budget.resources, SymbolDiscoveryByteLimit.parse(10000).refined())
        val scopeRequest = SymbolSearchScopeRequest(lease, scope)
        val fileRequest =
            SymbolDiscoveryRequest(
                scopeRequest,
                SymbolDiscoveryTarget.All(SymbolNameDiscoveryKind.FILE),
                maximum,
                constraints,
            )
        val textRequest =
            SymbolDiscoveryRequest(
                scopeRequest,
                SymbolDiscoveryTarget.Text(SymbolDiscoveryPattern.parse("Subject").refined()),
                maximum,
            )
        val file =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.FILE,
                    "Subject.kt",
                    lease,
                    path,
                    path.toUri().toString(),
                    null,
                )
                .refined()
        val textCandidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.TEXT,
                    "Subject",
                    lease,
                    path,
                    path.toUri().toString(),
                    0,
                    7,
                )
                .refined()
        val elapsed = SymbolDiscoveryElapsedNanoseconds.parse(0).refined()
        val references = CanonicalQueryReferences()
        listOf(fileRequest to file, textRequest to textCandidate).forEach { (request, candidate) ->
            val batch =
                SymbolDiscoveryBatch.create(
                        request,
                        listOf(candidate),
                        candidate.projectedUtf8Size(),
                        SymbolDiscoveryWorkCount.parse(1).refined(),
                        SymbolDiscoveryTimings(elapsed, elapsed),
                    )
                    .refined()
            val issued = (references.issueCandidates(batch) as CandidateSelectorIssuance.Issued).selectors.single()
            assertTrue(issued.value.startsWith("candidate:v3:"))
            val restored = (references.restoreCandidate(issued, lease) as CanonicalSelectorDecoding.Decoded).value
            assertEquals(scope, restored.scope)
            assertEquals(request.constraints, restored.constraints)
            assertEquals(
                SourceReadScope.Constrained(scope, request.constraints),
                SourceReadAnchor.Candidate(restored).readScope(),
            )
            val downgraded = text(issued.value.replaceFirst("candidate:v3:", "candidate:v2:"))
            assertEquals(
                CanonicalSelectorDecodingFailure.UNSUPPORTED_REFERENCE_VERSION,
                (references.restoreCandidate(downgraded, lease) as CanonicalSelectorDecoding.Rejected).failure,
            )
        }
    }

    @Test
    fun `historical file references retain exact file semantics and bytes`() {
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                        root,
                        Path.of("/workspace/Subject.kt"),
                    )
                    .refined()
            )
        val selector = CandidateSelector.restoreFile(lease, file)
        val issued = (CanonicalSelectorCodec.encodeCandidate(selector) as CanonicalSelectorEncoding.Encoded).token
        assertTrue(issued.value.startsWith("candidate:v2:"))
        val restored =
            (CanonicalSelectorCodec.decodeCandidate(issued, lease) as CanonicalSelectorDecoding.Decoded).value
        assertEquals(
            SymbolSearchScope.ExactFile(
                file.path,
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.INCLUDE,
            ),
            restored.scope,
        )
        assertEquals(SymbolDiscoveryConstraints.None, restored.constraints)
        assertEquals(
            issued,
            (CanonicalSelectorCodec.encodeCandidate(restored) as CanonicalSelectorEncoding.Encoded).token,
        )
    }

    @Test
    fun `live detached identity rejects old host epoch root and version`() {
        val current =
            LiveSemanticReadReference(
                root,
                IdeReadHostLifetime.fromBoundary(UUID.randomUUID()),
                IdeReadEpochRevision.parse(4).refined(),
                IdeReadContentView.SAVED_PSI_COMMITTED,
                1,
            )
        val document = LiveSelectorAuthorityDocument(current.host.value.toString(), 4, current.contentView.name, 1)
        assertTrue(document.admitReference(root.value, current) is Refinement.Refined)
        assertEquals(
            CanonicalSelectorDecodingFailure.INCOMPATIBLE_WORKSPACE,
            document.admitReference("/foreign", current).rejected(),
        )
        assertEquals(
            CanonicalSelectorDecodingFailure.INCOMPATIBLE_AUTHORITY,
            document.copy(host = UUID.randomUUID().toString()).admitReference(root.value, current).rejected(),
        )
        assertEquals(
            CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
            document.copy(epoch = 3).admitReference(root.value, current).rejected(),
        )
        assertEquals(
            CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
            document.copy(contentView = "UNSAVED").admitReference(root.value, current).rejected(),
        )
        assertEquals(
            CanonicalSelectorDecodingFailure.UNSUPPORTED_REFERENCE_VERSION,
            document.copy(version = 2).admitReference(root.value, current).rejected(),
        )
        val raw =
            Json.encodeToString(
                CandidateSelectorDocument.serializer(),
                CandidateSelectorDocument.File(
                    root = root.value,
                    live = document,
                    file = "/workspace/Subject.kt",
                ),
            )
        assertEquals(
            CanonicalSelectorDecodingFailure.LIVE_AUTHORITY_REQUIRED,
            (CanonicalSelectorCodec.decodeCandidate(token(raw)) as CanonicalSelectorDecoding.Rejected).failure,
        )
    }

    @Test
    fun `specialist discovery uses supplied authority and intersects the host result cap`() {
        val request =
            SymbolDiscoverRequest(
                SymbolDiscoverTargetDocument.Name(
                    text("Subject"),
                    SymbolNameKindDocument.CLASS,
                    SymbolDiscoveryMatchDocument.EXACT_NAME,
                ),
                ProtocolCount.parse(20).refined(),
            )
        val maximum = SymbolDiscoveryBudget(budget.resources, SymbolDiscoveryByteLimit.parse(10000).refined())
        val admitted = admitDiscoveryRequest(lease, request, maximum) as DiscoveryRequestAdmission.Admitted
        assertSame(lease, admitted.request.scope.lease)
        assertEquals(10, admitted.request.budget.resources.resultLimit.value)
        assertEquals(maximum.resources.workUnitLimit, admitted.request.budget.resources.workUnitLimit)
    }

    @Test
    fun `specialist workspace discovery excludes libraries only for live authority`() {
        val reference =
            LiveSemanticReadReference(
                root,
                IdeReadHostLifetime.fromBoundary(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                IdeReadEpochRevision.parse(1).refined(),
                IdeReadContentView.SAVED_PSI_COMMITTED,
                1,
            )
        val liveScope = specialistDiscoveryWorkspaceScope(SemanticReadIdentity.Live(reference))
        assertEquals(
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            ),
            liveScope,
        )
        val publishedScope = specialistDiscoveryWorkspaceScope(lease.identity)
        assertEquals(
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.INCLUDE,
            ),
            publishedScope,
        )

        val maximum = SymbolDiscoveryBudget(budget.resources, SymbolDiscoveryByteLimit.parse(10000).refined())
        for (target in
            listOf(
                SymbolDiscoverTargetDocument.Name(
                    text("Child"),
                    SymbolNameKindDocument.SYMBOL,
                    SymbolDiscoveryMatchDocument.EXACT_NAME,
                ),
                SymbolDiscoverTargetDocument.Text(text("Child"), SymbolTextScopeDocument.Workspace),
            )) {
            val request = SymbolDiscoverRequest(target, ProtocolCount.parse(10).refined())
            val admitted = admitDiscoveryRequest(lease, request, maximum) as DiscoveryRequestAdmission.Admitted
            assertSame(lease, admitted.request.scope.lease)
            assertEquals(publishedScope, admitted.request.scope.scope)
        }
    }

    @Test
    fun `specialist source admission intersects both host projection caps`() = runTest {
        val request = sourceRequest()
        var executed = false
        val operations =
            io.github.amichne.kast.source.contract.SourceReadOperations { read ->
                executed = true
                assertEquals(2, read.entityLimit.value)
                assertEquals(64L, read.textByteLimit.value)
                assertEquals(10L, read.resources.workUnitLimit.value)
                assertEquals(100L, read.resources.elapsedTimeLimit.value)
                io.github.amichne.kast.source.contract.SourceReadResult.Rejected(
                    io.github.amichne.kast.source.contract.SourceReadRejection.WORKSPACE_NOT_READY
                )
            }
        val outcome =
            CanonicalSourceReadProtocol(operations, CanonicalQueryReferences())
                .execute(
                    request,
                    lease,
                    sourceProjectionBudget(),
                )
        assertTrue(executed)
        assertTrue(outcome is OperationOutcome.Rejected)
    }

    @Test
    fun `canonical source projection retains every finite domain rejection`() = runTest {
        val request = sourceRequest()
        for (reason in io.github.amichne.kast.source.contract.SourceReadRejection.entries) {
            val rejected =
                CanonicalSourceReadProtocol(
                        io.github.amichne.kast.source.contract.SourceReadOperations {
                            io.github.amichne.kast.source.contract.SourceReadResult.Rejected(reason)
                        },
                        CanonicalQueryReferences(),
                    )
                    .execute(request, lease, sourceProjectionBudget()) as OperationOutcome.Rejected
            when (val cause = rejected.reason) {
                is io.github.amichne.kast.protocol.contract.SourceReadRejection -> assertEquals(reason.name, cause.name)
                is io.github.amichne.kast.protocol.contract.SourceReadFailureDetail.InternalContractFailure ->
                    assertTrue(reason.name.startsWith("INTERNAL_") || reason.name == "CONTRACT_VIOLATION")
                else -> error("Unexpected source rejection $cause")
            }
        }
    }

    private fun sourceRequest(): SourceReadRequest {
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                        root,
                        Path.of("/workspace/Subject.kt"),
                    )
                    .refined()
            )
        val token =
            (CanonicalSelectorCodec.encodeCandidate(CandidateSelector.restoreFile(lease, file))
                    as CanonicalSelectorEncoding.Encoded)
                .token
        return SourceReadRequest(
            SourceReadAnchorDocument.Candidate(token),
            SourceRegionSelectionDocument.Anchor,
            SourceEntitySelectionDocument.None,
            SourceTextRequestDocument.None,
            SourceEntityLimitDocument.parse(200).refined(),
            SourceTextByteLimitDocument.parse(2000).refined(),
            SourceReadPageDocument.First,
        )
    }

    private fun sourceProjectionBudget() =
        SourceProtocolBudget(
            io.github.amichne.kast.kernel.ResourceBudget(
                io.github.amichne.kast.kernel.ResultLimit.parse(2).refined(),
                io.github.amichne.kast.kernel.WorkUnitLimit.parse(10).refined(),
                io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(100).refined(),
            ),
            io.github.amichne.kast.source.contract.SourceTextByteLimit.parse(64).refined(),
        )

    private fun token(payload: String): ProtocolText {
        val bytes = payload.toByteArray()
        val digest =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return ProtocolText.parse(
                "candidate:v3:${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}:$digest"
            )
            .refined()
    }

    private fun request() =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(bounded(listOf(text("main"), text("test"))), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                )
            ),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(emptyList())),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun <Value, Failure> Refinement<Value, Failure>.rejected(): Failure =
        when (this) {
            is Refinement.Refined -> error("Expected rejection")
            is Refinement.Rejected -> failure
        }
}
