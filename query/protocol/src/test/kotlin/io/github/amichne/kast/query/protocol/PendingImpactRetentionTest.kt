package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionSelectionCause
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryTerminalReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PendingImpactRetentionTest {
    @Test
    fun `pending impact work cutoff retains presented empty snapshot and protects exact upstream checkpoint`() {
        val f = PendingImpactRetentionFixture()
        val execution = f.pending()
        assertEquals(
            Refinement.Rejected(QueryRetainedResultFailure.INCONSISTENT_COVERAGE),
            QueryRetainedResult.captureInvestigation(f.symbols.authority, execution),
        )
        val projected = f.projection.projectExecution(f.request, f.symbols.authority, execution)
        val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, projected)
        val payload = projectedPayload(projected)
        assertEquals(QueryQuestionDocument.from(f.request), payload.question)
        assertEquals(
            ImpactAccountingDocument.EvidenceOnly(
                io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument.parse(0).value()
            ),
            payload.impactAccounting,
        )
        val retained = payload.retention as QueryResultRetention.Retained
        val restored = f.store.restoreResult(retained.reference, f.symbols.authority) as QueryResultRestoration.Restored
        assertEquals(0, restored.result.rowCount)
        val progress = qualified.qualification as io.github.amichne.kast.protocol.contract.QueryRunQualification
        val checkpoint =
            ((progress.progress as QueryQualifiedProgressDocument.Resumable).checkpoint
                    as QueryCheckpointDocument.Upstream)
                .token
        val original = f.store.restoreCheckpoint(checkpoint, f.symbols.authority) as QueryCheckpointRestoration.Restored
        assertSame(f.checkpoint, original.checkpoint)
        assertEquals(
            listOf(
                QueryResultRetentionEvidence.CaptureStarted(QueryResultRetentionScope.PENDING_IMPACT),
                QueryResultRetentionEvidence.Captured(QueryResultRetentionScope.PENDING_IMPACT),
                QueryResultRetentionEvidence.Issuance(QueryResultRetentionIssue.ISSUED),
            ),
            f.observed,
        )
    }

    @Test
    fun `pending evidence-only terminal cannot acquire original investigation retention`() {
        val f = PendingImpactRetentionFixture()
        val execution = f.pending(continuation = QueryContinuationState.Terminal(QueryTerminalReason.NO_PROGRESS))
        val rejected =
            assertInstanceOf(
                OperationOutcome.Rejected::class.java,
                f.projection.projectExecution(f.request, f.symbols.authority, execution),
            )
        assertEquals(selectionRejection(), rejected.reason)
        assertEquals(
            listOf(QueryResultRetentionEvidence.CaptureRejected(QueryRetainedResultFailure.INCONSISTENT_COVERAGE)),
            f.observed,
        )
    }

    @Test
    fun `evidence-only completed path cannot stand in for a pending empty investigation page`() {
        val f = PendingImpactRetentionFixture()
        val execution = f.pending(QueryRows.ValuePaths.of(listOf(f.path())))
        val rejected =
            assertInstanceOf(
                OperationOutcome.Rejected::class.java,
                f.projection.projectExecution(f.request, f.symbols.authority, execution),
            )
        assertEquals(selectionRejection(), rejected.reason)
        assertEquals(
            listOf(QueryResultRetentionEvidence.CaptureRejected(QueryRetainedResultFailure.INCONSISTENT_COVERAGE)),
            f.observed,
        )
    }

    @Test
    fun `finalized investigation retains original ledger scope and emits exact capture and issuance evidence`() {
        val f = PendingImpactRetentionFixture()
        val execution =
            f.pending(f.investigatedRows(), QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE))
        val outcome = f.projection.projectExecution(f.request, f.symbols.authority, execution)
        assertInstanceOf(OperationOutcome.Qualified::class.java, outcome)
        val payload = projectedPayload(outcome)
        assertInstanceOf(ImpactAccountingDocument.Investigated::class.java, payload.impactAccounting)
        assertEquals(
            listOf(
                QueryResultRetentionEvidence.CaptureStarted(QueryResultRetentionScope.ORIGINAL_INVESTIGATION),
                QueryResultRetentionEvidence.Captured(QueryResultRetentionScope.ORIGINAL_INVESTIGATION),
                QueryResultRetentionEvidence.Issuance(QueryResultRetentionIssue.ISSUED),
            ),
            f.observed,
        )
    }

    private fun selectionRejection() =
        QueryRunRejection.ImpactExecutionRejected(
            ImpactExecutionFailureDocument.Selection(ImpactExecutionSelectionCause.INCONSISTENT_COVERAGE)
        )
}

private fun projectedPayload(
    value:
        io.github.amichne.kast.kernel.OperationOutcome<
            io.github.amichne.kast.protocol.contract.QueryRunResult,
            io.github.amichne.kast.protocol.contract.QueryRunQualification,
            QueryRunRejection,
        >
) = (value as OperationOutcome.Qualified).evidence.payload

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
