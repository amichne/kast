package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.query.contract.QueryContinuationState
import io.github.amichne.kast.query.contract.QueryExecutionRequest
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowStep

/** Advances only the evaluator's clock, after a real compiler observation; no semantic result is substituted. */
internal class NativePacketQueryCheckpoint(private val after: (ValueFlowStep) -> Boolean) : QueryNanoClock {
    private var nanos = 0L
    private val executions = mutableListOf<QueryExecutionResult>()
    val matched = mutableListOf<ValueFlowStep>()
    var resumed = 0
        private set

    override fun now(): Long = nanos

    fun observed(read: ValueFlowRead) {
        if (matched.isEmpty() && read is ValueFlowRead.Observed && after(read.step)) {
            matched += read.step
            nanos = EXPIRED_EXECUTION_NANOS
        }
    }

    fun entering(request: QueryExecutionRequest) {
        if (executions.isEmpty()) {
            check(request.checkpoint == null)
        } else {
            val previous = executions.last()
            check(previous is QueryExecutionResult.Qualified) { "Resume did not follow a qualified execution" }
            val continuation = previous.continuation
            check(continuation is QueryContinuationState.Resumable) { "No semantic checkpoint was produced" }
            check(request.checkpoint === continuation.checkpoint) { "Protocol did not restore the exact checkpoint" }
            check(request.plan == continuation.checkpoint.plan && request.lease == continuation.checkpoint.lease)
            resumed++
        }
    }

    fun completed(result: QueryExecutionResult) {
        executions += result
    }

    fun assertResumed() {
        check(matched.size == 1) { "The required compiler work never triggered the checkpoint" }
        check(executions.size == 2 && resumed == 1) { "Expected exactly one semantic suspension/resume: $executions" }
        val suspended = executions.first()
        check(suspended is QueryExecutionResult.Qualified)
        check(QueryLimitation.TIME_LIMIT_REACHED in suspended.coverage.limitations)
        check(suspended.continuation is QueryContinuationState.Resumable)
        val rows = suspended.result.rows
        check(rows is QueryRows.ValuePaths && rows.values.isEmpty()) { "Checkpoint was only output pagination" }
        check(executions.last() is QueryExecutionResult.Complete) { "Resumed execution did not complete" }
        println(
            "COMPILER_COVERAGE_CHECKPOINT suspensions=1 resumes=$resumed " +
                "preCheckpointRows=0 matchedReads=${matched.size}"
        )
    }

    private companion object {
        const val EXPIRED_EXECUTION_NANOS = 61_000_000_000L
    }
}
