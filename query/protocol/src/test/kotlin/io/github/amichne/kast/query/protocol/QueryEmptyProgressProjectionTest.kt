package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryActiveInput
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteCount
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryInputRevision
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualifications
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRemainder
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryEmptyProgressProjectionTest {
    @Test
    fun `zero output advancing producer checkpoint resumes with the same execution allowance`() = runTest {
        val fixture = EmptyProgressFixture()
        val first =
            fixture.protocol.execute(fixture.request(), fixture.lease, fixture.budget) as OperationOutcome.Qualified
        assertTrue(first.evidence.payload.items.values.isEmpty())
        val progress =
            assertInstanceOf(QueryQualifiedProgressDocument.Resumable::class.java, first.qualification.progress)
        val checkpoint = assertInstanceOf(QueryCheckpointDocument.Upstream::class.java, progress.checkpoint)
        assertEquals(ReadResumeActionDocument.RESUME, progress.nextAction)
        assertInstanceOf(
            OperationOutcome.Complete::class.java,
            fixture.protocol.execute(QueryRunRequest.Resume(checkpoint.token), fixture.lease, fixture.budget),
        )
        assertEquals(listOf(null, 956), fixture.positions)
        assertEquals(listOf(512L, 512L), fixture.workGrants)
    }

    @Test
    fun `retained zero row presentation preserves the usable upstream action`() = runTest {
        val fixture = EmptyProgressFixture()
        val first =
            fixture.protocol.execute(
                fixture.request().copy(retention = QueryRetentionModeDocument.RETAIN),
                fixture.lease,
                fixture.budget,
            ) as OperationOutcome.Qualified
        val reference = (first.evidence.payload.retention as QueryResultRetention.Retained).reference
        val presentation =
            fixture.protocol.execute(
                QueryRunRequest.ReadResult.symbols(
                    reference,
                    output = QueryOutputDocument.Symbols(bounded(emptyList())),
                ),
                fixture.lease,
                fixture.budget,
            ) as OperationOutcome.Qualified
        val progress = presentation.qualification.progress as QueryQualifiedProgressDocument.Resumable
        assertInstanceOf(QueryCheckpointDocument.Upstream::class.java, progress.checkpoint)
        assertEquals(ReadResumeActionDocument.RESUME, progress.nextAction)
        assertEquals(first.qualification.progress, progress)
        assertEquals(listOf(null), fixture.positions)
    }

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refinedEmptyProgress()
}

/** Only native discovery observations are virtual; the query scheduler and checkpoint projection are production. */
private class EmptyProgressFixture {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refinedEmptyProgress()
    val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refinedEmptyProgress())
    val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(1).refinedEmptyProgress(),
                WorkUnitLimit.parse(512).refinedEmptyProgress(),
                ElapsedTimeLimitMillis.parse(1000).refinedEmptyProgress(),
            ),
            QueryByteLimit.parse(10000).refinedEmptyProgress(),
        )
    val positions = mutableListOf<Int?>()
    val workGrants = mutableListOf<Long>()
    val protocol =
        CanonicalQueryProtocol(
            QueryService(
                discovery = SymbolDiscoveryOperations(::discover),
                exact = unexpectedExact,
                source = SourceReadOperations { error("Source read was not expected") },
                relations = RelationOperations { error("Relation read was not expected") },
                traversal = TraversalOperations { error("Traversal was not expected") },
                traversalCeiling = traversalCeiling(),
                clock = QueryNanoClock { 0L },
            ),
            CanonicalQueryReferences(),
        )

    fun request() =
        QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(
                    QueryMatchDocument.All,
                    QueryScopeDocument(bounded(listOf(ProtocolText.parse("main").refinedEmptyProgress())), null, null),
                    bounded(listOf(QueryDeclarationKindDocument.CLASS)),
                )
            ),
            bounded(emptyList()),
            QueryOutputDocument.Symbols(bounded(emptyList())),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )

    private fun discover(request: SymbolDiscoveryRequest): SymbolDiscoveryResult {
        val offset = request.remainder?.nextOffset?.value
        positions += offset
        workGrants += request.budget.resources.workUnitLimit.value
        check(positions.size <= 2) { "Discovery repeated exhausted input" }
        val work = if (offset == null) request.budget.resources.workUnitLimit.value else 0L
        val batch =
            SymbolDiscoveryBatch.create(
                    request,
                    emptyList(),
                    SymbolDiscoveryByteCount.parse(0).refinedEmptyProgress(),
                    SymbolDiscoveryWorkCount.parse(work).refinedEmptyProgress(),
                    SymbolDiscoveryTimings(
                        SymbolDiscoveryElapsedNanoseconds.Zero,
                        SymbolDiscoveryElapsedNanoseconds.Zero,
                    ),
                )
                .refinedEmptyProgress()
        val outcome =
            if (offset == null)
                SymbolDiscoveryOutcome.Qualified(
                    batch,
                    SymbolDiscoveryQualifications.from(setOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED))
                        .refinedEmptyProgress(),
                    SymbolDiscoveryProgress.Resumable(remainder(request)),
                )
            else {
                check(offset == 956 && request.remainder?.inputRevision?.value == 1L) {
                    "Wrong retained source position"
                }
                SymbolDiscoveryOutcome.Complete(batch)
            }
        return SymbolDiscoveryResult.Discovered(outcome)
    }

    private fun remainder(request: SymbolDiscoveryRequest): SymbolDiscoveryRemainder =
        SymbolDiscoveryRemainder.fromPendingInput(
                request,
                emptyList(),
                SymbolDiscoveryActiveInput.Scanning(
                    SymbolDiscoveryFileIdentity.Workspace(
                        CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/Declarations.kt"))
                            .refinedEmptyProgress()
                    ),
                    SymbolDiscoverySourceOffset.parse(956).refinedEmptyProgress(),
                ),
                SymbolDiscoveryInputRevision.parse(1).refinedEmptyProgress(),
                discoveredFiles = SymbolDiscoveryWorkCount.parse(1).refinedEmptyProgress(),
            )
            .refinedEmptyProgress()

    private fun traversalCeiling() =
        TraversalBudget(
            budget.resources.resultLimit,
            TraversalByteLimit.parse(budget.returnedBytes.value).refinedEmptyProgress(),
            budget.resources.workUnitLimit,
            budget.resources.elapsedTimeLimit,
            TraversalDepthLimit.parse(1).refinedEmptyProgress(),
            TraversalFrontierLimit.parse(1).refinedEmptyProgress(),
            RelationBudget(
                budget.resources,
                RelationByteLimit.parse(budget.returnedBytes.value).refinedEmptyProgress(),
            ),
        )

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refinedEmptyProgress()
}

private val unexpectedExact =
    object : SymbolExactOperations {
        override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
            error("Exact resolution was not expected")

        override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult =
            error("Description was not expected")
    }

private fun <Value, Failure> Refinement<Value, Failure>.refinedEmptyProgress(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
