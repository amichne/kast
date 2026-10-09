package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PendingImpactReadResultTest {
    @Test
    fun `pending retained paths remain qualified and findings unavailable without consuming exact checkpoint`() =
        runTest {
            val f = PendingImpactRetentionFixture()
            val initial =
                f.projection.projectExecution(f.request, f.symbols.authority, f.pending())
                    as OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>
            val reference = (initial.evidence.payload.retention as QueryResultRetention.Retained).reference
            var executions = 0
            val protocol =
                CanonicalQueryProtocol(
                    QueryOperations {
                        executions++
                        error("Retained pending presentation must not execute semantic work")
                    },
                    f.symbols.references,
                    f.store,
                )
            val budget = QueryBudget(f.symbols.budget.resources, QueryByteLimit.parse(1_000_000).value())
            val page =
                protocol.executePage(QueryRunRequest.ReadResult.valuePaths(reference), f.symbols.authority, budget)
            assertInstanceOf(OperationOutcome.Qualified::class.java, page)
            val qualified = page as OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>
            assertPendingEvidence(f, initial, qualified)
            val findings =
                protocol.executePage(
                    QueryRunRequest.ReadResult.impactWitness(reference, ImpactWitnessSectionDocument.FINDINGS),
                    f.symbols.authority,
                    budget,
                )
            assertEquals(
                OperationOutcome.Rejected(
                    QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE)
                ),
                findings,
            )
            val token =
                ((initial.qualification.progress as QueryQualifiedProgressDocument.Resumable).checkpoint
                        as QueryCheckpointDocument.Upstream)
                    .token
            val restored = f.store.restoreCheckpoint(token, f.symbols.authority) as QueryCheckpointRestoration.Restored
            assertSame(f.checkpoint, restored.checkpoint)
            assertEquals(0, executions)
        }

    private fun assertPendingEvidence(
        fixture: PendingImpactRetentionFixture,
        initial: OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>,
        retained: OperationOutcome.Qualified<QueryRunResult, QueryRunQualification>,
    ) {
        assertEquals(emptyList<Any>(), retained.evidence.payload.items.values)
        assertEquals(initial.evidence.payload.impactAccounting, retained.evidence.payload.impactAccounting)
        assertInstanceOf(
            ImpactAccountingDocument.EvidenceOnly::class.java,
            retained.evidence.payload.impactAccounting,
        )
        assertEquals(QueryQuestionDocument.from(fixture.request), retained.evidence.payload.question)
        assertInstanceOf(
            QueryQualifiedProgressDocument.TerminalIncomplete::class.java,
            retained.qualification.progress,
        )
        val interpretation =
            retained.evidence.payload.interpretation
                as io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument.EvidenceOnly
        val original =
            interpretation.originalCoverage
                as io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument.Qualified
        assertEquals(initial.qualification.progress, original.progress)
    }
}

private fun <T> io.github.amichne.kast.kernel.Refinement<T, *>.value(): T =
    (this as io.github.amichne.kast.kernel.Refinement.Refined).value
