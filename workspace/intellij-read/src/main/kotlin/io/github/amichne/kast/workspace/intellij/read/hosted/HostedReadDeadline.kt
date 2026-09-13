package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement

/** Positive child allowances bounded below the host time remaining at semantic admission. */
class HostedSemanticTimeAllowance
private constructor(
    val semantic: ElapsedTimeLimitMillis,
    val diagnosticScope: ElapsedTimeLimitMillis,
) {
    internal companion object {
        fun admit(
            limits: ReadLimits,
            availableMillis: Long,
        ): Refinement<HostedSemanticTimeAllowance, HostedQueryFailure> {
            fun bounded(parameter: ReadLimitParameter) =
                ElapsedTimeLimitMillis.parse(minOf(limits[parameter].value.toLong(), availableMillis))
            val semantic =
                when (val result = bounded(ReadLimitParameter.SEMANTIC_MILLIS)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                }
            val diagnostic =
                when (val result = bounded(ReadLimitParameter.DIAGNOSTIC_SCOPE_MILLIS)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                }
            return Refinement.Refined(HostedSemanticTimeAllowance(semantic, diagnostic))
        }
    }
}

/** The clock starts before scheduling the hard timer, conservatively including dispatch and model work. */
internal class HostedReadDeadline(private val limits: ReadLimits, private val clock: () -> Long) {
    private val started = clock()
    // Reserve for cooperative overrun, freshness revalidation and detachment. The hard timer still bounds all work.
    private val completionReserveMillis =
        minOf(250L, (limits[ReadLimitParameter.HOST_QUERY_MILLIS].value / 8L).coerceAtLeast(1L))

    fun admit(diagnostics: HostedReadDiagnostics?): Refinement<HostedSemanticTimeAllowance, HostedQueryFailure> {
        val elapsedNanos = (clock() - started).coerceAtLeast(0L)
        val elapsedMillis = elapsedNanos / 1_000_000L + if (elapsedNanos % 1_000_000L == 0L) 0L else 1L
        val remaining = (limits[ReadLimitParameter.HOST_QUERY_MILLIS].value - elapsedMillis).coerceAtLeast(0L)
        val result = HostedSemanticTimeAllowance.admit(limits, remaining - completionReserveMillis)
        diagnostics?.budget(
            when (result) {
                is Refinement.Refined ->
                    HostedSemanticBudgetObservation.Admitted(
                        remaining,
                        completionReserveMillis,
                        result.value.semantic.value,
                        result.value.diagnosticScope.value,
                    )
                is Refinement.Rejected -> HostedSemanticBudgetObservation.Exhausted(remaining, completionReserveMillis)
            }
        )
        return result
    }
}
