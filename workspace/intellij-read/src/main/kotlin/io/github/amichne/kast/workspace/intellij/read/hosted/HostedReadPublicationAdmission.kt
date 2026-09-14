package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause

/** Refines a calculated allowance before its grant is recorded or any semantic provider is called. */
fun interface HostedReadPublicationAdmission {
    fun admit(report: ExecutionBudgetReport, limits: ReadLimits): Refinement<Unit, HostedQueryFailure>

    companion object {
        val Containment = HostedReadPublicationAdmission { report, limits ->
            val budget = ExecutionBudgetPresence.Present(report)
            val capacity = limits[ReadLimitParameter.HOST_RESPONSE_BYTES].value
            if (
                containmentFailures.all { failure ->
                    HostedQueryStage.entries.all { stage ->
                        HostedQueryWire.encode(HostedQueryResult.Rejected(failure, stage, budget))
                            .toByteArray(Charsets.UTF_8)
                            .size <= capacity
                    }
                }
            )
                Refinement.Refined(Unit)
            else Refinement.Rejected(HostedQueryFailure.RESULT_LIMIT_EXCEEDED)
        }
    }
}

// Exact typed containment encodings, including the largest recovery-bearing freshness envelope.
// These witnesses do not change the failure emitted by a later boundary.
private val containmentFailures =
    listOf(
        HostedQueryFailure.BUDGET_EXCEEDED,
        HostedQueryFailure.CANCELLED,
        HostedQueryFailure.READ_PREEMPTED,
        HostedQueryFailure.RETIRED,
        HostedQueryFailure.STALE_REQUEST,
        HostedQueryFailure.Platform(HostedPlatformFailureCause.RUNTIME),
        HostedQueryFailure.Platform(HostedPlatformFailureCause.LINKAGE),
        HostedQueryFailure.Freshness(
            VfsPassiveReadAdmissionFailure.Unavailable(VfsPassiveReadUnavailableCause.GradleModelUnavailable)
        ),
    )
