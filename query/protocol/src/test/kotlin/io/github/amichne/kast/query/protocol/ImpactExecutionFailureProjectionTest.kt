package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ImpactExecutionAccountingCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionBoundaryCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionLedgerCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionModelHistoryCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionPathCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionRepresentationCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionRowIdentityCause
import io.github.amichne.kast.protocol.contract.ImpactExecutionSelectionCause
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactExecutionFailure
import io.github.amichne.kast.query.contract.QueryImpactLedgerFailure
import io.github.amichne.kast.query.contract.QueryImpactModelHistoryFailure
import io.github.amichne.kast.query.contract.QueryImpactPathFailure
import io.github.amichne.kast.query.contract.QueryImpactRowIdentityFailure
import io.github.amichne.kast.query.contract.QueryRetainedResultFailure
import io.github.amichne.kast.query.contract.QueryValuePathAccountingFailure
import io.github.amichne.kast.relation.contract.BoundaryModelFailure
import io.github.amichne.kast.relation.contract.RepresentationPropagationFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactExecutionFailureProjectionTest {
    @Test
    fun `typed interpreter failures survive actual outcome projection without retention`() {
        val symbols = RelationPagingFixture.published()
        val request =
            QueryRunRequest.Run(
                QueryFromDocument.References(
                    BoundedProtocolList.create(listOf(QueryReferenceDocument.ExactSymbol(symbols.exact))).value()
                ),
                BoundedProtocolList.create(emptyList<QueryStepDocument>()).value(),
                QueryOutputDocument.ValuePaths,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            )
        val state = QueryStateStore(clock = { error("Rejected execution must not acquire retained state") })
        val projector = QueryOutcomeProjection(symbols.references, state)
        val cases =
            listOf(
                QueryImpactExecutionFailure.Path(QueryImpactPathFailure.TERMINAL_UNPROVEN) to
                    ImpactExecutionFailureDocument.Path(ImpactExecutionPathCause.TERMINAL_UNPROVEN),
                QueryImpactExecutionFailure.Ledger(QueryImpactLedgerFailure.MISSING_BRANCH) to
                    ImpactExecutionFailureDocument.Ledger(ImpactExecutionLedgerCause.MISSING_BRANCH),
                QueryImpactExecutionFailure.Accounting(QueryValuePathAccountingFailure.DUPLICATE_PATH) to
                    ImpactExecutionFailureDocument.Accounting(ImpactExecutionAccountingCause.DUPLICATE_PATH),
                QueryImpactExecutionFailure.Representation(RepresentationPropagationFailure.CALLABLE_MISMATCH) to
                    ImpactExecutionFailureDocument.Representation(ImpactExecutionRepresentationCause.CALLABLE_MISMATCH),
                QueryImpactExecutionFailure.Boundary(BoundaryModelFailure.KIND_MISMATCH) to
                    ImpactExecutionFailureDocument.Boundary(ImpactExecutionBoundaryCause.KIND_MISMATCH),
                QueryImpactExecutionFailure.ModelHistory(QueryImpactModelHistoryFailure.MISSING_APPLICATION) to
                    ImpactExecutionFailureDocument.ModelHistory(ImpactExecutionModelHistoryCause.MISSING_APPLICATION),
                QueryImpactExecutionFailure.Selection(QueryRetainedResultFailure.PRESENTATION_ONLY_ROWS) to
                    ImpactExecutionFailureDocument.Selection(ImpactExecutionSelectionCause.PRESENTATION_ONLY_ROWS),
                QueryImpactExecutionFailure.RowIdentity(QueryImpactRowIdentityFailure.CHANGED_RETAINED_ROWS) to
                    ImpactExecutionFailureDocument.RowIdentity(ImpactExecutionRowIdentityCause.CHANGED_RETAINED_ROWS),
                QueryImpactExecutionFailure.PresentationOnly to ImpactExecutionFailureDocument.PresentationOnly,
            )
        for ((failure, expected) in cases) assertEquals(
            OperationOutcome.Rejected(QueryRunRejection.ImpactExecutionRejected(expected)),
            projector.projectExecution(request, symbols.authority, QueryExecutionResult.ImpactRejected(failure)),
        )
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
