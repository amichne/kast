package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.AdmittedQueryPlan
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryCheckpoint
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.query.contract.QueryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolDescription
import org.junit.jupiter.api.Assertions.assertEquals

internal open class AutomaticSymbolQueryCase {
    protected val fixture = RelationPagingFixture.published()
    protected val output = QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME)))
    protected val request =
        QueryRunRequest.Run(
            QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(fixture.exact)))),
            bounded(emptyList()),
            output,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )
    protected val budget =
        QueryBudget(
            ResourceBudget(
                ResultLimit.parse(2).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            QueryByteLimit.parse(100_000).refined(),
        )
    protected val row = QuerySymbol(SymbolDescription.from(fixture.selector), emptyList())

    protected fun policy(
        rows: Int = 100,
        byteLimit: Long = 100_000,
        retainedBytes: Long = 1_000_000,
        nanoTime: () -> Long = { 0L },
        cancelled: () -> Boolean = { false },
        inlinePresentation: (QueryPublishedPage) -> QueryInlinePresentation = { QueryInlinePresentation.FITS },
    ) =
        QueryInvocationPolicy(
            ResultLimit.parse(rows).refined(),
            QueryByteLimit.parse(byteLimit).refined(),
            QueryByteLimit.parse(retainedBytes).refined(),
            previewBytes = CanonicalQueryCliDocuments::symbolPreviewBytes,
            nanoTime = nanoTime,
            cancelled = cancelled,
            inlinePresentation = inlinePresentation,
        )

    protected inner class Script(
        private val pages: List<List<QuerySymbol>>,
        private val staleAfterFirst: Boolean = false,
        private val repeatCheckpoint: Boolean = false,
        private val terminal: Boolean = false,
        private val observedWork: Boolean = true,
        private val afterPage: () -> Unit = {},
        private val resultForPage: (List<QuerySymbol>, Int) -> QueryResult = { rows, _ ->
            QueryResult(QueryRows.Symbols.of(rows), emptyList())
        },
    ) {
        private var unexpectedCalls = 0
        var calls = 0
        val grants = mutableListOf<Long>()
        val timeGrants = mutableListOf<Long>()
        val operations = QueryOperations { execution ->
            if (calls >= pages.size) {
                unexpectedCalls++
                error("Unexpected semantic execution")
            }
            assertEquals(fixture.authority, execution.lease)
            if (calls > 0) assertEquals(calls, (execution.checkpoint as Checkpoint).page)
            grants += execution.budget.resources.workUnitLimit.value
            timeGrants += execution.budget.resources.elapsedTimeLimit.value
            val rows = pages[calls++]
            if (staleAfterFirst && calls > 1)
                return@QueryOperations QueryExecutionResult.Rejected(QueryExecutionRejection.REFERENCE_STALE)
            val result = resultForPage(rows, calls)
            val count = QueryCount.parse(pages.take(calls).sumOf { it.size }).refined()
            val outcome =
                if (calls == pages.size && !terminal && !repeatCheckpoint)
                    QueryExecutionResult.Complete.create(result, QueryCoverage.Complete(count))
                else
                    QueryExecutionResult.Qualified(
                        result,
                        QueryCoverage.Qualified.create(
                                count,
                                setOf(
                                    if (terminal && calls == pages.size) QueryLimitation.RELATION_INCOMPLETE
                                    else QueryLimitation.RESULT_LIMIT_REACHED
                                ),
                            )
                            .refined(),
                        if (terminal && calls == pages.size)
                            QueryContinuationState.Terminal(QueryTerminalReason.UPSTREAM_INCOMPLETE)
                        else
                            QueryContinuationState.Resumable(
                                Checkpoint(execution.plan, execution.lease, if (repeatCheckpoint) 1 else calls)
                            ),
                    )
            afterPage()
            if (observedWork) outcome.observedWork(QueryWorkCount.parse(7).refined()) else outcome
        }

        fun assertDrained() {
            assertEquals(0, unexpectedCalls, "Unexpected semantic execution")
            assertEquals(pages.size, calls, "Unconsumed semantic expectations")
        }
    }

    protected data class Checkpoint(
        override val plan: AdmittedQueryPlan,
        override val lease: io.github.amichne.kast.workspace.contract.SemanticReadAuthority,
        val page: Int,
    ) : QueryCheckpoint {
        override val retainedBytes = 100L
    }

    protected fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    protected fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}
