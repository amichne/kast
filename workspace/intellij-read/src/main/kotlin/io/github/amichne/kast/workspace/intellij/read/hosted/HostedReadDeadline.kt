package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement

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
        val elapsedNanos = (clock() - started).coerceAtLeast(0L)
        val elapsedMillis = elapsedNanos / 1_000_000L + if (elapsedNanos % 1_000_000L == 0L) 0L else 1L
        val remaining = (limits[ReadLimitParameter.HOST_QUERY_MILLIS].value - elapsedMillis).coerceAtLeast(0L)
        val candidate = HostedSemanticTimeAllowance.admit(limits, remaining - completionReserveMillis, request)
        val result =
            when (candidate) {
                is Refinement.Rejected -> candidate
                is Refinement.Refined ->
                    when (
                        val admitted =
                            publication.admit(
                                io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.from(
                                    candidate.value.executionBudget
                                ),
                                limits,
                            )
                    ) {
                        is Refinement.Refined -> candidate
                        is Refinement.Rejected -> admitted
                    }
            }
        diagnostics?.budget(
            when (result) {
                is Refinement.Refined ->
                    HostedSemanticBudgetObservation.Admitted(
                        remaining,
                        completionReserveMillis,
                        result.value.semantic.value,
                        result.value.diagnosticScope.value,
                    )
                is Refinement.Rejected ->
                    when (candidate) {
                        is Refinement.Rejected ->
                            HostedSemanticBudgetObservation.Exhausted(remaining, completionReserveMillis)
                        is Refinement.Refined ->
                            HostedSemanticBudgetObservation.PublicationRejected(
                                io.github.amichne.kast.protocol.contract.ExecutionBudgetReport.from(
                                    candidate.value.executionBudget
                                )
                            )
                    }
            }
        )
        return result
    }
}
