package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport

/** Positive child allowances bounded below the host time remaining at semantic admission. */
class HostedSemanticTimeAllowance
private constructor(
    val executionBudget: io.github.amichne.kast.kernel.AdmittedExecutionBudget,
    val diagnosticScope: ElapsedTimeLimitMillis,
) {
    val semantic: ElapsedTimeLimitMillis
        get() = executionBudget.elapsed.effective

    internal companion object {
        fun admit(
            limits: ReadLimits,
            availableMillis: Long,
            request: HostedExecutionBudgetRequest = HostedExecutionBudgetRequest(),
        ): Refinement<HostedSemanticTimeAllowance, HostedQueryFailure> {
            fun bounded(parameter: ReadLimitParameter) =
                ElapsedTimeLimitMillis.parse(minOf(limits[parameter].value.toLong(), availableMillis))
            val semantic =
                when (val result = ElapsedTimeLimitMillis.parse(availableMillis)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                }
            val diagnostic =
                when (val result = bounded(ReadLimitParameter.DIAGNOSTIC_SCOPE_MILLIS)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                }
            return Refinement.Refined(
                HostedSemanticTimeAllowance(admitHostedExecutionBudget(limits, request, semantic), diagnostic)
            )
        }
    }
}

/** The clock starts before scheduling the hard timer, conservatively including dispatch and model work. */
internal class HostedReadDeadline(
    private val limits: ReadLimits,
    private val clock: () -> Long,
    private val request: HostedExecutionBudgetRequest = HostedExecutionBudgetRequest(),
    private val publication: HostedReadPublicationAdmission = HostedReadPublicationAdmission.Containment,
) {
    private val started = clock()
    // Reserve for cooperative overrun, freshness revalidation and detachment. The hard timer still bounds all work.
    private val completionReserveMillis =
        minOf(250L, (limits[ReadLimitParameter.HOST_QUERY_MILLIS].value / 8L).coerceAtLeast(1L))

    fun admit(diagnostics: HostedReadDiagnostics?): Refinement<HostedSemanticTimeAllowance, HostedQueryFailure> {
        val initialRemaining = remainingMillis()
        val candidate = HostedSemanticTimeAllowance.admit(limits, initialRemaining - completionReserveMillis, request)
        val checked =
            when (candidate) {
                is Refinement.Rejected -> candidate
                is Refinement.Refined -> admitPublication(candidate.value)
            }
        // Encoding is synchronous work under the hard deadline. Observe it before committing a grant.
        val remaining = minOf(initialRemaining, remainingMillis())
        val result =
            when (checked) {
                is Refinement.Rejected -> checked
                is Refinement.Refined ->
                    HostedSemanticTimeAllowance.admit(limits, remaining - completionReserveMillis, request)
            }
        diagnostics?.budget(
            when {
                result is Refinement.Refined ->
                    HostedSemanticBudgetObservation.Admitted(
                        remaining,
                        completionReserveMillis,
                        result.value.semantic.value,
                        result.value.diagnosticScope.value,
                    )
                candidate is Refinement.Refined && checked is Refinement.Rejected ->
                    HostedSemanticBudgetObservation.PublicationRejected(
                        ExecutionBudgetReport.from(candidate.value.executionBudget)
                    )
                else -> HostedSemanticBudgetObservation.Exhausted(remaining, completionReserveMillis)
            }
        )
        return result
    }

    fun admitCompletion(allowance: HostedSemanticTimeAllowance, policy: HostedReadCompletionPolicy) =
        HostedAdmittedRead(allowance, when (policy) {
            HostedReadCompletionPolicy.HOST_CONTAINMENT -> HostedReadCompletion.HostContainment
            HostedReadCompletionPolicy.CALLER_ELAPSED -> HostedReadCompletion.CallerElapsed(clock(), allowance.semantic)
        })

    fun validateCompletion(completion: HostedReadCompletion): Refinement<Unit, HostedQueryFailure> = Refinement.Refined(Unit)

    private fun remainingMillis(): Long {
        val elapsedNanos = (clock() - started).coerceAtLeast(0L)
        val elapsedMillis = elapsedNanos / 1_000_000L + if (elapsedNanos % 1_000_000L == 0L) 0L else 1L
        return (limits[ReadLimitParameter.HOST_QUERY_MILLIS].value - elapsedMillis).coerceAtLeast(0L)
    }

    private fun admitPublication(candidate: HostedSemanticTimeAllowance): Refinement<Unit, HostedQueryFailure> {
        val checked = publication.admit(ExecutionBudgetReport.from(candidate.executionBudget), limits)
        val elapsed = candidate.executionBudget.elapsed
        val selected =
            when (val supplied = elapsed.requested) {
                ExecutionAllowance.Default -> elapsed.configuredDefault.value
                is ExecutionAllowance.Requested -> supplied.value.value
            }
        if (checked is Refinement.Rejected || selected == 1L) return checked
        // A deadline clamp can appear while an operator ceiling keeps effective time unchanged.
        // This actual allowance has the largest positive effective time below the selected amount,
        // including that clamp. Any later report has no more digits or clamp causes; all other
        // dimensions stay fixed. The positive selected proof makes subtraction safe even at Long.MAX_VALUE.
        val witnessAvailable = minOf(candidate.semantic.value, selected - 1L)
        return when (val witness = HostedSemanticTimeAllowance.admit(limits, witnessAvailable, request)) {
            is Refinement.Rejected -> witness
            is Refinement.Refined ->
                publication.admit(ExecutionBudgetReport.from(witness.value.executionBudget), limits)
        }
    }
}
