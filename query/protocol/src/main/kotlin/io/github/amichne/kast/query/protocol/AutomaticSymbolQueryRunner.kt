package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryInvestigationCompletion
import io.github.amichne.kast.query.contract.QueryTerminalReason
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Effect capabilities are invocation-owned. Byte measurement uses the actual public item serializer. */
class QueryInvocationPolicy(
    val previewRows: ResultLimit,
    val previewBytesLimit: QueryByteLimit,
    val retainedBytes: QueryByteLimit,
    val previewBytes: (List<QueryResultItemDocument>) -> Long,
    val nanoTime: () -> Long = System::nanoTime,
    val cancelled: () -> Boolean = { false },
    val inlinePresentation: (QueryPublishedPage) -> QueryInlinePresentation = { QueryInlinePresentation.FITS },
    val retentionObservation: QueryInvocationRetentionObservation = QueryInvocationRetentionObservation.None,
)

enum class QueryInlinePresentation {
    FITS,
    RETENTION_REQUIRED,
    INVALID,
}

internal data class QueryInvocationPage(val page: QueryPublishedPage, val execution: QueryExecutionResult?)

internal data class AccumulatedSymbolQuery(
    val execution: QueryExecutionResult,
    val items: List<QueryResultItemDocument>,
    val stop: QueryInvocationStop,
    val failure: QueryRunRejection?,
    val issuedProgress: QueryQualifiedProgressDocument.Resumable? = null,
)

internal sealed interface QueryInvocationTransition {
    data class Continue(val token: QueryExecutionContinuation.Pipeline) : QueryInvocationTransition

    data class Rejected(val reason: QueryRunRejection) : QueryInvocationTransition

    data class Stopped(val reason: QueryInvocationStop, val failure: QueryRunRejection? = null) :
        QueryInvocationTransition
}

internal fun invalidInvocation(
    failure: QueryRunRejection =
        QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION)
) = QueryInvocationTransition.Stopped(QueryInvocationStop.INVALID_STATE, failure)

/** Follows only issued resumable progress; it never reacquires a source or increases an allowance. */
internal class AutomaticSymbolQueryRunner(
    private val state: QueryStateStore,
    private val lease: SemanticReadAuthority,
    private val policy: QueryInvocationPolicy,
    private val execute: suspend (QueryRunRequest, QueryBudget) -> QueryInvocationPage,
) {
    suspend fun run(
        request: QueryRunRequest.Run,
        allowance: QueryBudget,
    ): Refinement<AccumulatedSymbolQuery, QueryRunRejection> = Invocation(request, allowance).run()

    private inner class Invocation(private val request: QueryRunRequest.Run, private val allowance: QueryBudget) {
        private val facts = QueryInvocationFacts(lease, policy)
        private val started = policy.nanoTime()
        private var remainingWork = allowance.resources.workUnitLimit.value
        private val consumed = mutableSetOf<QueryExecutionContinuation.Pipeline>()
        private val issued = mutableSetOf<QueryExecutionContinuation>()
        private var pending: QueryExecutionContinuation.Pipeline? = null
        private var pendingProgress: QueryQualifiedProgressDocument.Resumable? = null

        suspend fun run(): Refinement<AccumulatedSymbolQuery, QueryRunRejection> {
            var action: QueryRunRequest = request
            while (true) {
                when (val transition = step(action)) {
                    is QueryInvocationTransition.Continue -> action = QueryRunRequest.Resume(transition.token)
                    is QueryInvocationTransition.Rejected -> return Refinement.Rejected(transition.reason)
                    is QueryInvocationTransition.Stopped -> {
                        if (!facts.hasObservedRowShape && request.output !is QueryOutputDocument.Symbols)
                            return Refinement.Rejected(transition.unobservedFailure())
                        val progress = validProgress(transition.reason)
                        return Refinement.Refined(
                            facts
                                .finish(transition, progress)
                                .copy(
                                    issuedProgress =
                                        if (progress is QueryContinuationState.Resumable) pendingProgress else null
                                )
                        )
                    }
                }
            }
        }

        private suspend fun step(action: QueryRunRequest): QueryInvocationTransition {
            val budget =
                when (val admitted = nextBudget()) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted.failure
                }
            val token = (action as? QueryRunRequest.Resume)?.continuation as? QueryExecutionContinuation.Pipeline
            if (token != null && !consumed.add(token))
                return QueryInvocationTransition.Stopped(QueryInvocationStop.NON_ADVANCING)
            val observed = execute(action, budget)
            if (action is QueryRunRequest.Run && observed.page is OperationOutcome.Rejected)
                return QueryInvocationTransition.Rejected(observed.page.reason)
            val page =
                when (val admitted = SymbolInvocationPage.admit(observed, request)) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected -> return admitted.failure
                }
            page.issuedToken?.let(issued::add)
            if (page.work.count.value > remainingWork) return invalidInvocation()
            remainingWork -= page.work.count.value
            val capacity =
                when (val admitted = retentionAdmission(QueryInvocationRetentionStage.BEFORE_FACTS).capacity) {
                    is QueryInvocationRetentionCapacity.Available -> admitted.bytes.value + facts.retainedBytes
                    QueryInvocationRetentionCapacity.Exhausted ->
                        return QueryInvocationTransition.Stopped(QueryInvocationStop.RETAINED_BYTES_LIMIT)
                }
            when (val appended = facts.append(page, capacity)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return appended.failure
            }
            return advance(page)
        }

        private fun nextBudget(): Refinement<QueryBudget, QueryInvocationTransition.Stopped> {
            if (policy.cancelled()) return stopped(QueryInvocationStop.CANCELLED)
            val elapsed = (policy.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L
            val millis = allowance.resources.elapsedTimeLimit.value - elapsed
            if (millis <= 0L) return stopped(QueryInvocationStop.TIME_LIMIT)
            if (remainingWork <= 1L) return stopped(QueryInvocationStop.WORK_LIMIT)
            val bytes =
                when (val admitted = retentionAdmission(QueryInvocationRetentionStage.BEFORE_EXECUTION).capacity) {
                    is QueryInvocationRetentionCapacity.Available -> admitted.bytes.value
                    QueryInvocationRetentionCapacity.Exhausted ->
                        return stopped(QueryInvocationStop.RETAINED_BYTES_LIMIT)
                }
            val checkpoint = bytes / 2
            val output = (bytes - checkpoint - QUERY_PAGE_RESERVATION_OVERHEAD) / QUERY_PAGE_RESERVATION_MULTIPLIER
            if (output < 1L || checkpoint < 1L) return stopped(QueryInvocationStop.RETAINED_BYTES_LIMIT)
            remainingWork-- // One unit pays for every runner transition, including empty or replayed pages.
            return Refinement.Refined(
                allowance.copy(
                    resources =
                        allowance.resources.copy(
                            workUnitLimit = WorkUnitLimit.parse(remainingWork).required(),
                            // Native inventory preparation cannot checkpoint an unfinished search. Preserve the
                            // invocation's remaining grant through that effect and charge every successor below.
                            elapsedTimeLimit = ElapsedTimeLimitMillis.parse(millis).required(),
                        ),
                    returnedBytes = QueryByteLimit.parse(minOf(allowance.returnedBytes.value, output)).required(),
                    checkpointBytes =
                        QueryByteLimit.parse(minOf(allowance.checkpointBytes.value, checkpoint)).required(),
                )
            )
        }

        private fun retentionAdmission(stage: QueryInvocationRetentionStage): QueryInvocationRetentionAdmission =
            QueryInvocationRetentionAdmission(
                    stage,
                    policy.retainedBytes,
                    QueryRetentionByteCount.measured(facts.retainedBytes),
                    state.invocationRetainedBytes(issued),
                    QueryRetentionByteCount.measured(request.accountedRequestBytes()),
                )
                .also(policy.retentionObservation::observe)

        private fun advance(page: SymbolInvocationPage): QueryInvocationTransition =
            when (page) {
                is SymbolInvocationPage.Complete -> QueryInvocationTransition.Stopped(QueryInvocationStop.COMPLETED)
                is SymbolInvocationPage.Qualified ->
                    if (page.execution.investigationCompletion is QueryInvestigationCompletion.Established)
                        QueryInvocationTransition.Stopped(QueryInvocationStop.COMPLETED)
                    else
                        when (val progress = page.qualification.progress) {
                            is QueryQualifiedProgressDocument.Resumable -> resume(page, progress)
                            is QueryQualifiedProgressDocument.TerminalIncomplete -> {
                                facts.terminal(page, QueryTerminalReason.valueOf(progress.reason.name))
                                QueryInvocationTransition.Stopped(QueryInvocationStop.TERMINAL_INCOMPLETE)
                            }
                            is QueryQualifiedProgressDocument.RetentionUnavailable ->
                                QueryInvocationTransition.Stopped(QueryInvocationStop.RETENTION_FAILED)
                        }
            }

        private fun resume(
            page: SymbolInvocationPage.Qualified,
            progress: QueryQualifiedProgressDocument.Resumable,
        ): QueryInvocationTransition {
            val token = (progress.checkpoint as? QueryCheckpointDocument.Upstream)?.token ?: return invalidInvocation()
            val next = page.execution.continuation as? QueryContinuationState.Resumable ?: return invalidInvocation()
            pending = token
            pendingProgress = progress
            facts.progress = next
            return if (progress.nextAction == ReadResumeActionDocument.RESUME) QueryInvocationTransition.Continue(token)
            else QueryInvocationTransition.Stopped(QueryInvocationStop.BUDGET_INCREASE_REQUIRED)
        }

        private fun validProgress(stop: QueryInvocationStop): QueryContinuationState {
            if (stop == QueryInvocationStop.TERMINAL_INCOMPLETE) return facts.progress
            val resumableStops =
                setOf(
                    QueryInvocationStop.WORK_LIMIT,
                    QueryInvocationStop.TIME_LIMIT,
                    QueryInvocationStop.CANCELLED,
                    QueryInvocationStop.BUDGET_INCREASE_REQUIRED,
                )
            val token = pending
            if (
                stop in resumableStops &&
                    token != null &&
                    state.restoreCheckpoint(token, lease) is QueryCheckpointRestoration.Restored
            )
                return facts.progress
            return QueryContinuationState.Terminal(
                if (stop == QueryInvocationStop.NON_ADVANCING) QueryTerminalReason.NO_PROGRESS
                else QueryTerminalReason.UPSTREAM_INCOMPLETE
            )
        }

        private fun stopped(stop: QueryInvocationStop) = Refinement.Rejected(QueryInvocationTransition.Stopped(stop))
    }
}

internal fun <T, F> Refinement<T, F>.required(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Proven query invocation bound was lost: $failure")
    }
