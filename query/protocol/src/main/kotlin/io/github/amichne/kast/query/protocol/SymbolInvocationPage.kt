package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.continuationToken
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryInvestigationCompletion
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryWorkUsage

/** A semantic receipt paired with the exact canonical page it produced; replays have no fresh receipt. */
internal sealed class SymbolInvocationPage(
    val payload: QueryRunResult,
    val result: QueryResult,
    val work: QueryWorkUsage.Observed,
    val items: List<QueryResultItemDocument>,
) {
    abstract val execution: QueryExecutionResult
    open val issuedToken: QueryExecutionContinuation?
        get() = null

    class Complete
    internal constructor(
        override val execution: QueryExecutionResult.Complete,
        payload: QueryRunResult,
        work: QueryWorkUsage.Observed,
        items: List<QueryResultItemDocument>,
    ) : SymbolInvocationPage(payload, execution.result, work, items)

    class Qualified
    internal constructor(
        override val execution: QueryExecutionResult.Qualified,
        payload: QueryRunResult,
        work: QueryWorkUsage.Observed,
        items: List<QueryResultItemDocument>,
        val qualification: QueryRunQualification,
    ) : SymbolInvocationPage(payload, execution.result, work, items) {
        override val issuedToken
            get() = qualification.progress.continuationToken
    }

    companion object {
        fun admit(
            observed: QueryInvocationPage,
            request: QueryRunRequest.Run,
        ): Refinement<SymbolInvocationPage, QueryInvocationTransition.Stopped> {
            val page = observed.page
            if (page is OperationOutcome.Rejected) return rejectPage(page.reason)
            val raw =
                observed.execution
                    ?: return Refinement.Rejected(QueryInvocationTransition.Stopped(QueryInvocationStop.NON_ADVANCING))
            val usage = raw.workUsage as? QueryWorkUsage.Observed ?: return Refinement.Rejected(invalidInvocation())
            return when (page) {
                is OperationOutcome.Complete -> {
                    if (raw !is QueryExecutionResult.Complete) return Refinement.Rejected(invalidInvocation())
                    refine(
                        Complete(
                            raw,
                            page.evidence.payload,
                            usage,
                            page.evidence.payload.items.values,
                        ),
                        request,
                    )
                }
                is OperationOutcome.Qualified -> {
                    if (raw !is QueryExecutionResult.Qualified) return Refinement.Rejected(invalidInvocation())
                    refine(
                        Qualified(
                            raw,
                            page.evidence.payload,
                            usage,
                            page.evidence.payload.items.values,
                            page.qualification,
                        ),
                        request,
                    )
                }
                is OperationOutcome.Rejected -> Refinement.Rejected(invalidInvocation(page.reason))
            }
        }

        private fun refine(
            page: SymbolInvocationPage,
            request: QueryRunRequest.Run,
        ): Refinement<SymbolInvocationPage, QueryInvocationTransition.Stopped> {
            val rows = page.result.rows
            val count =
                when (rows) {
                    is QueryRows.Symbols -> rows.values.size
                    is QueryRows.Occurrences -> rows.values.size
                    is QueryRows.Bindings -> rows.values.size
                    is QueryRows.ValuePaths -> rows.values.size
                    is QueryRows.ImpactWitness -> return Refinement.Rejected(invalidInvocation())
                }
            if (!preservesOriginalCompletion(page, rows)) return Refinement.Rejected(invalidInvocation())
            val validItems =
                page.items.all { item ->
                    when (request.output) {
                        is QueryOutputDocument.Symbols -> item is QueryResultItemDocument.ExactSymbol
                        QueryOutputDocument.Occurrences ->
                            item is QueryResultItemDocument.Occurrence ||
                                item is QueryResultItemDocument.ReferenceOccurrence
                        QueryOutputDocument.TraversalRecords -> item is QueryResultItemDocument.TraversalRecord
                        QueryOutputDocument.BindingRows -> item is QueryResultItemDocument.BindingRow
                        QueryOutputDocument.ValuePaths -> item is QueryResultItemDocument.ValuePath
                        is QueryOutputDocument.ImpactWitness -> false
                    }
                }
            if (!validItems) return Refinement.Rejected(invalidInvocation())
            return if (
                page.payload.question != QueryQuestionDocument.from(request) ||
                    page.items.size != page.payload.items.values.size ||
                    count != page.items.size
            )
                Refinement.Rejected(invalidInvocation())
            else Refinement.Refined(page)
        }

        private fun preservesOriginalCompletion(page: SymbolInvocationPage, rows: QueryRows): Boolean {
            val qualified = page.execution as? QueryExecutionResult.Qualified
            val completion = qualified?.investigationCompletion
            if (completion is QueryInvestigationCompletion.Established) {
                val original = completion.original.result.rows as? QueryRows.ValuePaths ?: return false
                val selected = rows as? QueryRows.ValuePaths ?: return false
                val admittedLedger = (original.accounting as? QueryValuePathAccounting.Investigated)?.ledger
                val selectedLedger = (selected.accounting as? QueryValuePathAccounting.Investigated)?.ledger
                if (
                    admittedLedger == null ||
                        admittedLedger !== selectedLedger ||
                        completion.original.coverage.resultCount != qualified.coverage.knownMinimum
                )
                    return false
            }
            return true
        }

        private fun rejectPage(reason: QueryRunRejection): Refinement.Rejected<QueryInvocationTransition.Stopped> =
            Refinement.Rejected(
                if (
                    reason ==
                        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.NON_ADVANCING_CONTINUATION)
                )
                    QueryInvocationTransition.Stopped(QueryInvocationStop.NON_ADVANCING, reason)
                else invalidInvocation(reason)
            )
    }
}
