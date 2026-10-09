package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionSelectionCause
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PendingImpactRetentionTest {
    @Test
    fun `invocation captures pending evidence once and retains exact original checkpoint`() = runTest {
        val f = PendingImpactRetentionFixture()
        val outcome = project(f, f.pending()) as OperationOutcome.Rejected
        val rejection = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, outcome.reason)
        val retained = assertInstanceOf(QueryCompletionEvidenceDocument.Retained::class.java, rejection.evidence)
        val restored = f.store.restoreResult(retained.result, f.symbols.authority) as QueryResultRestoration.Restored
        assertEquals(0, restored.result.rowCount)
        assertSame(f.checkpoint, (restored.result.producerProgress as QueryContinuationState.Resumable).checkpoint)
        assertEquals(successReceipts(), f.observed)
    }

    @Test
    fun `final invocation captures original investigation ledger and reports one issuance`() = runTest {
        val f = PendingImpactRetentionFixture()
        val execution =
            f.pending(f.investigatedRows(), QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE))
        val outcome = project(f, execution) as OperationOutcome.Rejected
        val rejection = assertInstanceOf(QueryRunRejection.CompletionUnproven::class.java, outcome.reason)
        val retained = assertInstanceOf(QueryCompletionEvidenceDocument.Retained::class.java, rejection.evidence)
        val restored = f.store.restoreResult(retained.result, f.symbols.authority) as QueryResultRestoration.Restored
        assertEquals(
            execution.result.rows,
            (restored.result as io.github.amichne.kast.query.contract.QueryRetainedResult.ValuePaths).rows,
        )
        assertEquals(successReceipts(), f.observed)
    }

    @Test
    fun `foreign checkpoint capture preserves finite impact selection cause and rejection signal`() = runTest {
        val f = PendingImpactRetentionFixture()
        val foreign =
            object : QueryCheckpoint by f.checkpoint {
                override val lease =
                    SemanticReadLease(f.symbols.authority.workspaceRoot, EvidenceGeneration.parse(8).value())
            }
        val outcome = project(f, f.pending(continuation = QueryContinuationState.Resumable(foreign)))
        assertEquals(
            OperationOutcome.Rejected(
                QueryRunRejection.ImpactExecutionRejected(
                    ImpactExecutionFailureDocument.Selection(ImpactExecutionSelectionCause.BASIS_MISMATCH)
                )
            ),
            outcome,
        )
        assertEquals(
            listOf(
                QueryResultRetentionEvidence.CaptureStarted,
                QueryResultRetentionEvidence.CaptureRejected(QueryRetainedResultFailure.BASIS_MISMATCH),
            ),
            f.observed,
        )
    }

    private suspend fun project(
        f: PendingImpactRetentionFixture,
        execution: QueryExecutionResult.Qualified,
    ): QueryPublishedPage {
        val policy =
            QueryInvocationPolicy(
                previewRows = ResultLimit.parse(100).value(),
                previewBytesLimit = QueryByteLimit.parse(100_000).value(),
                retainedBytes = QueryByteLimit.parse(1_000_000).value(),
                previewBytes = CanonicalQueryCliDocuments::previewBytes,
            )
        val items =
            (QueryItemProjector(f.symbols.references).projectItems(f.request.output, execution.result.rows)
                    as QueryProjection.Projected)
                .values
        val claim = (f.store.acquireInitial(f.symbols.authority) as QueryInitialAcquisition.Acquired).claim
        return QueryPagePublication(f.store, QueryExecutionPublication.Immediate).execute(claim) {
            QueryInvocationProjection(f.symbols.references, f.store, QueryResultRetentionObservation(f.observed::add))
                .project(
                    request = f.request,
                    lease = f.symbols.authority,
                    accumulated = AccumulatedSymbolQuery(execution, items, QueryInvocationStop.WORK_LIMIT, null),
                    policy = policy,
                    owner = claim,
                )
        }
    }

    private fun successReceipts() =
        listOf(
            QueryResultRetentionEvidence.CaptureStarted,
            QueryResultRetentionEvidence.Captured,
            QueryResultRetentionEvidence.Issuance(QueryResultRetentionIssue.ISSUED),
        )
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
