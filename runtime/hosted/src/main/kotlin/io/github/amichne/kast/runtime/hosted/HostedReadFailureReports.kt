package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryFailure
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadPublicationAdmission

internal fun ExecutionBudgetReport?.presence(): ExecutionBudgetPresence =
    if (this == null) ExecutionBudgetPresence.Absent else ExecutionBudgetPresence.Present(this)

/** Publication failures retain the grant already carried by the semantic outcome, never a reconstructed default. */
internal fun HostedResponse.withReadBudget(budget: ExecutionBudgetPresence): HostedResponse =
    if (budget == ExecutionBudgetPresence.Absent) this
    else
        when (this) {
            is HostedResponse.Rejected -> HostedResponse.Rejected(failure, budget)
            is HostedResponse.Oversized -> HostedResponse.Oversized(operation, semantic, budget)
            is HostedResponse.EncodingRejected -> HostedResponse.EncodingRejected(operation, semantic, failure, budget)
            is HostedResponse.ReadRejected -> HostedResponse.ReadRejected(failure, stage, budget)
            is HostedResponse.Canonical<*, *, *>,
            is HostedResponse.Completed,
            is HostedResponse.ChangeRejected -> this
        }

/** The candidate is admitted only when every finite endpoint containment envelope fits the hard frame cap. */
internal val hostedReadPublicationAdmission = HostedReadPublicationAdmission { report, limits ->
    when (
        val workspace =
            HostedReadPublicationAdmission.Containment.admit(
                report,
                limits,
            )
    ) {
        is Refinement.Rejected -> workspace
        is Refinement.Refined ->
            if (
                HostedEndpointFailure.entries.all { failure ->
                    HostedRequests.rejected(failure, ExecutionBudgetPresence.Present(report))
                        .toByteArray(Charsets.UTF_8)
                        .size <= limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value
                }
            )
                workspace
            else Refinement.Rejected(HostedQueryFailure.RESULT_LIMIT_EXCEEDED)
    }
}
