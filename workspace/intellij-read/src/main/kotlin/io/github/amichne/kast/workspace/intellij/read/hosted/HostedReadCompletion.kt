package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis

/** Selects whether the admitted caller time also binds detached response publication. */
enum class HostedReadCompletionPolicy {
    HOST_CONTAINMENT,
    CALLER_ELAPSED,
}

/** Request-local proof; no live semantic object or continuation storage enters this value. */
internal data class HostedAdmittedRead(
    val allowance: HostedSemanticTimeAllowance,
    val completion: HostedReadCompletion,
)

internal sealed interface HostedReadCompletion {
    data object HostContainment : HostedReadCompletion

    data class CallerElapsed(val startedNanos: Long, val limit: ElapsedTimeLimitMillis) : HostedReadCompletion
}

internal fun <Value> HostedExecution<HostedSemanticRead<Value>>.semanticReadResult(): HostedSemanticReadResult<Value> =
    when (this) {
        is HostedExecution.Rejected -> HostedSemanticReadResult.Rejected(failure, stage, executionBudget)
        is HostedExecution.Completed ->
            when (val result = value) {
                is HostedSemanticRead.Resolved -> HostedSemanticReadResult.Completed(result.evidence)
                is HostedSemanticRead.Rejected ->
                    HostedSemanticReadResult.Rejected(result.failure, stage, executionBudget)
            }
    }
