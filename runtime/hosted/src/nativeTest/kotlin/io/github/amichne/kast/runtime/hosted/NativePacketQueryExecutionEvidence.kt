package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows

/** One detached execution receipt distinguishes the interpreter from its public projection. */
internal fun NativePacketQueryServices.executionSummary(): String {
    val execution =
        when (val result = lastExecution) {
            null -> "NOT_RUN"
            is QueryExecutionResult.Rejected -> "REJECTED:${result.reason}"
            is QueryExecutionResult.ImpactRejected -> "IMPACT_REJECTED:${result.failure}"
            is QueryExecutionResult.Complete -> "COMPLETE:${result.result.valuePathCount()}"
            is QueryExecutionResult.Qualified ->
                "QUALIFIED:${result.result.valuePathCount()}:${result.coverage.limitations}"
        }
    return "interpreter=$execution nativeFlowReads=$nativeFlowReads retention=$lastRetention"
}

private fun QueryResult.valuePathCount(): Int = (rows as? QueryRows.ValuePaths)?.values?.size ?: 0
