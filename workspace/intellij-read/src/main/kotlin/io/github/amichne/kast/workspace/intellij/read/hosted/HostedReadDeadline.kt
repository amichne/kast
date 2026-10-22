package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import java.util.concurrent.TimeUnit

/** The admitted variant preserves whether semantic execution has a time limit. */
sealed interface HostedSemanticTimeAllowance {
    val executionBudget: AdmittedExecutionBudget
    val diagnosticScope: ElapsedTimeLimitMillis

    class Bounded
    internal constructor(
        override val executionBudget: AdmittedExecutionBudget.Bounded,
        override val diagnosticScope: ElapsedTimeLimitMillis,
    ) : HostedSemanticTimeAllowance {
        val semantic: ElapsedTimeLimitMillis
            get() = executionBudget.elapsed.effective
    }

    class RunnerControlled
    internal constructor(
        override val executionBudget: AdmittedExecutionBudget.RunnerControlled,
        override val diagnosticScope: ElapsedTimeLimitMillis,
    ) : HostedSemanticTimeAllowance

    companion object {
        internal fun admit(
            limits: ReadLimits,
            availableMillis: Long,
            request: HostedExecutionBudgetRequest = HostedExecutionBudgetRequest(),
        ): Refinement<HostedSemanticTimeAllowance, HostedQueryFailure> {
            when (val requested = request.requested) {
                is RequestedExecutionBudget.RunnerControlled ->
                    return when (
                        val diagnostic =
                            ElapsedTimeLimitMillis.parse(
                                limits[ReadLimitParameter.DIAGNOSTIC_SCOPE_MILLIS].value.toLong()
                            )
                    ) {
                        is Refinement.Rejected -> Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                        is Refinement.Refined ->
                            Refinement.Refined(
                                RunnerControlled(
                                    admitHostedRunnerExecutionBudget(
                                        limits,
                                        requested,
                                        request.profile,
                                        request.maximumResults,
                                    ),
                                    diagnostic.value,
                                )
                            )
                    }
                is RequestedExecutionBudget.Bounded -> Unit
            }
            val available =
                when (val result = ElapsedTimeLimitMillis.parse(availableMillis)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                }
            val diagnosticMillis =
                minOf(limits[ReadLimitParameter.DIAGNOSTIC_SCOPE_MILLIS].value.toLong(), availableMillis)
            val diagnostic =
                when (val result = ElapsedTimeLimitMillis.parse(diagnosticMillis)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                }
            return Refinement.Refined(
                when (val grant = admitHostedExecutionBudget(limits, request, available)) {
                    is AdmittedExecutionBudget.Bounded -> Bounded(grant, diagnostic)
                    is AdmittedExecutionBudget.RunnerControlled -> RunnerControlled(grant, diagnostic)
                }
            )
        }
    }
}

/** The bounded deadline includes dispatch and model work before semantic admission. */
internal class HostedReadDeadline(
    private val limits: ReadLimits,
    private val clock: () -> Long,
    private val request: HostedExecutionBudgetRequest = HostedExecutionBudgetRequest(),
    private val publication: HostedReadPublicationAdmission = HostedReadPublicationAdmission.Containment,
) {
    private val started = clock()
    // Bounded execution reserves time for cooperative overrun, freshness revalidation, and detachment.
    private val completionReserveMillis =
        minOf(250L, (limits[ReadLimitParameter.HOST_QUERY_MILLIS].value / 8L).coerceAtLeast(1L))

    fun admit(diagnostics: HostedReadDiagnostics?): Refinement<HostedSemanticTimeAllowance, HostedQueryFailure> {
        val initialRemaining = remainingMillis()
        if (request.requested is RequestedExecutionBudget.RunnerControlled)
            return admitRunnerControlled(initialRemaining, diagnostics)
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
                    when (val allowance = result.value) {
                        is HostedSemanticTimeAllowance.Bounded ->
                            HostedSemanticBudgetObservation.Admitted(
                                remaining,
                                completionReserveMillis,
                                allowance.semantic.value,
                                allowance.diagnosticScope.value,
                            )
                        is HostedSemanticTimeAllowance.RunnerControlled ->
                            HostedSemanticBudgetObservation.RunnerControlled
                    }
                candidate is Refinement.Refined && checked is Refinement.Rejected ->
                    HostedSemanticBudgetObservation.PublicationRejected(
                        ExecutionBudgetReport.from(candidate.value.executionBudget)
                    )
                else -> HostedSemanticBudgetObservation.Exhausted(remaining, completionReserveMillis)
            }
        )
        return result
    }

    private fun admitRunnerControlled(
        initialRemaining: Long,
        diagnostics: HostedReadDiagnostics?,
    ): Refinement<HostedSemanticTimeAllowance, HostedQueryFailure> {
        val candidate = HostedSemanticTimeAllowance.admit(limits, initialRemaining, request)
        val result =
            when (candidate) {
                is Refinement.Rejected -> candidate
                is Refinement.Refined ->
                    when (val admitted = admitPublication(candidate.value)) {
                        is Refinement.Rejected -> admitted
                        is Refinement.Refined -> candidate
                    }
            }
        diagnostics?.budget(
            when (result) {
                is Refinement.Refined -> HostedSemanticBudgetObservation.RunnerControlled
                is Refinement.Rejected ->
                    when (candidate) {
                        is Refinement.Refined ->
                            HostedSemanticBudgetObservation.PublicationRejected(
                                ExecutionBudgetReport.from(candidate.value.executionBudget)
                            )
                        is Refinement.Rejected -> HostedSemanticBudgetObservation.Exhausted(initialRemaining, 0L)
                    }
            }
        )
        return result
    }

    fun admitCompletion(allowance: HostedSemanticTimeAllowance, policy: HostedReadCompletionPolicy) =
        HostedAdmittedRead(
            allowance,
            when (allowance) {
                is HostedSemanticTimeAllowance.RunnerControlled -> HostedReadCompletion.RunnerControlled
                is HostedSemanticTimeAllowance.Bounded ->
                    when (policy) {
                        HostedReadCompletionPolicy.HOST_CONTAINMENT -> HostedReadCompletion.HostContainment
                        HostedReadCompletionPolicy.CALLER_ELAPSED ->
                            HostedReadCompletion.CallerElapsed(clock(), allowance.semantic)
                    }
            },
        )

    fun validateCompletion(completion: HostedReadCompletion): Refinement<Unit, HostedQueryFailure> =
        when (completion) {
            HostedReadCompletion.HostContainment,
            HostedReadCompletion.RunnerControlled -> Refinement.Refined(Unit)
            is HostedReadCompletion.CallerElapsed -> {
                val elapsed = clock() - completion.startedNanos
                if (elapsed < 0L || TimeUnit.NANOSECONDS.toMillis(elapsed) >= completion.limit.value)
                    Refinement.Rejected(HostedQueryFailure.BUDGET_EXCEEDED)
                else Refinement.Refined(Unit)
            }
        }

    private fun remainingMillis(): Long {
        val elapsedNanos = (clock() - started).coerceAtLeast(0L)
        val elapsedMillis = elapsedNanos / 1_000_000L + if (elapsedNanos % 1_000_000L == 0L) 0L else 1L
        return (limits[ReadLimitParameter.HOST_QUERY_MILLIS].value - elapsedMillis).coerceAtLeast(0L)
    }

    private fun admitPublication(candidate: HostedSemanticTimeAllowance): Refinement<Unit, HostedQueryFailure> {
        val checked = publication.admit(ExecutionBudgetReport.from(candidate.executionBudget), limits)
        return when (candidate) {
            is HostedSemanticTimeAllowance.RunnerControlled -> checked
            is HostedSemanticTimeAllowance.Bounded -> admitBoundedPublication(candidate, checked)
        }
    }

    private fun admitBoundedPublication(
        candidate: HostedSemanticTimeAllowance.Bounded,
        checked: Refinement<Unit, HostedQueryFailure>,
    ): Refinement<Unit, HostedQueryFailure> {
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
