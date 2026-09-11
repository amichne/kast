package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalProjection
import io.github.amichne.kast.appserver.protocol.codex.PlanApprovalItemCompletion
import io.github.amichne.kast.kernel.Refinement
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred

internal enum class PlanApprovalSend {
    SENT,
    UNAVAILABLE,
}

internal sealed interface BrokerPlanApprovalReply {
    data object Unowned : BrokerPlanApprovalReply

    data object Handled : BrokerPlanApprovalReply

    data class Rejected(val failure: PlanApprovalFailure) : BrokerPlanApprovalReply
}

internal class ApprovedBrokerPlanInvocation(
    val grant: HostedPlanApprovalGrant,
    internal val pending: BrokerPlanApprovalPending,
)

internal class BrokerPlanApprovalPending(
    val id: String,
    val source: ClientConnectionId,
    val decision: PendingExactPlanApproval,
    val projection: CodexPlanApprovalProjection,
    private val tasks: SharedTaskSessions,
    private val delivery: BrokerPlanApprovalDelivery,
) {
    val outcome = CompletableDeferred<ExactPlanApprovalOutcome>()

    private sealed interface Completion {
        data object Pending : Completion

        data object Published : Completion
    }

    private var completion: Completion = Completion.Pending

    fun resolve(resolution: ExactPlanApprovalResolution) {
        if (resolution !is ExactPlanApprovalResolution.Resolved) return
        delivery.send(decision.controller, projection.resolved.toString())
        tasks.resolved(decision.invocation.threadId, id)
        outcome.complete(resolution.outcome)
    }

    fun terminate(reason: PlanApprovalTermination) = resolve(decision.terminate(reason))

    @Synchronized
    fun complete(status: PlanApprovalItemCompletion): Refinement<Unit, HostedPlanApprovalFailure> {
        if (completion == Completion.Published) return Refinement.Refined(Unit)
        completion = Completion.Published
        return when (val projected = projection.completed(status, delivery.clock.instant())) {
            is Refinement.Refined -> {
                delivery.send(decision.controller, projected.value.toString())
                Refinement.Refined(Unit)
            }
            is Refinement.Rejected -> projected
        }
    }
}

/** Native notification delivery and time are explicit effect capabilities shared by one approval lifecycle. */
internal class BrokerPlanApprovalDelivery(
    val send: (ClientConnectionId, String) -> PlanApprovalSend,
    val clock: Clock = Clock.systemUTC(),
)
