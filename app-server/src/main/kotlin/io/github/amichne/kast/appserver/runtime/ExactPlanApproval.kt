package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Boundary identities only: possession of either digest does not establish approval. */
internal class ExactPlanApprovalSubject
private constructor(
    val planIdentity: String,
    val hostedChallenge: String,
    val operation: HostedChangeApprovalOperation,
    val root: CanonicalBrokerDirectory,
    val host: HostedApprovalOwnerId,
) {
    companion object {
        fun admit(
            planIdentity: String,
            hostedChallenge: String,
            operation: HostedChangeApprovalOperation = HostedChangeApprovalOperation.APPLY,
            root: CanonicalBrokerDirectory,
            host: HostedApprovalOwnerId,
        ): Refinement<ExactPlanApprovalSubject, PlanApprovalFailure> =
            if (!planIdentity.matches(Regex("[0-9a-f]{64}")))
                Refinement.Rejected(PlanApprovalFailure.INVALID_PLAN_IDENTITY)
            else if (!hostedChallenge.matches(Regex("[0-9a-f]{64}")))
                Refinement.Rejected(PlanApprovalFailure.INVALID_CHALLENGE)
            else Refinement.Refined(ExactPlanApprovalSubject(planIdentity, hostedChallenge, operation, root, host))
    }
}

@JvmInline
internal value class HostedApprovalOwnerId private constructor(val value: UUID) {
    companion object {
        fun admit(raw: String): Refinement<HostedApprovalOwnerId, HostedPlanApprovalFailure> =
            try {
                val id = UUID.fromString(raw)
                if (id.toString() == raw) Refinement.Refined(HostedApprovalOwnerId(id))
                else Refinement.Rejected(HostedPlanApprovalFailure.CHALLENGE_REJECTED)
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(HostedPlanApprovalFailure.CHALLENGE_REJECTED)
            }
    }
}

internal enum class PlanApprovalFailure {
    INVALID_PLAN_IDENTITY,
    INVALID_CHALLENGE,
    UNKNOWN_REQUEST,
    MALFORMED_RESPONSE,
    NOT_RESPONSIBLE_CONTROLLER,
    CONTROLLER_LEASE_CHANGED,
    ALREADY_RESOLVED,
    SESSION_APPROVAL_UNSUPPORTED,
}

internal enum class PlanApprovalTermination {
    DECLINED,
    CANCELLED,
    DISCONNECTED,
    TIMED_OUT,
    OWNER_RETIRED,
}

/** Proof of a controller response only; the hosted challenge owner must separately validate and consume it. */
internal sealed interface ControllerApprovedPlan {
    val subject: ExactPlanApprovalSubject
    val invocation: BrokerInvocationContext
    val controller: ClientConnectionId
    val lease: ControllerLeaseId
}

private class AcceptedControllerPlan(
    override val subject: ExactPlanApprovalSubject,
    override val invocation: BrokerInvocationContext,
    override val controller: ClientConnectionId,
    override val lease: ControllerLeaseId,
) : ControllerApprovedPlan

internal sealed interface ExactPlanApprovalOutcome {
    data class Approved(val proof: ControllerApprovedPlan) : ExactPlanApprovalOutcome

    data class Terminated(val reason: PlanApprovalTermination) : ExactPlanApprovalOutcome

    data class Rejected(val failure: PlanApprovalFailure) : ExactPlanApprovalOutcome

    data class ControlRejected(val failure: ControlFailure) : ExactPlanApprovalOutcome
}

internal sealed interface ExactPlanApprovalResolution {
    data class Resolved(val outcome: ExactPlanApprovalOutcome) : ExactPlanApprovalResolution

    data class Rejected(val failure: PlanApprovalFailure) : ExactPlanApprovalResolution
}

/** One broker-owned pending native request. The transport correlates its unguessable request id before calling here. */
internal class PendingExactPlanApproval
private constructor(
    val subject: ExactPlanApprovalSubject,
    val invocation: BrokerInvocationContext,
    val controller: ClientConnectionId,
    val lease: ControllerLeaseId,
    private val tasks: SharedTaskSessions,
) {
    companion object {
        fun open(
            subject: ExactPlanApprovalSubject,
            invocation: BrokerInvocationContext,
            tasks: SharedTaskSessions,
        ): Refinement<PendingExactPlanApproval, ControlFailure> =
            synchronized(tasks) {
                if (subject.root != invocation.workingDirectory)
                    return@synchronized Refinement.Rejected(ControlFailure.NOT_CONTROLLER)
                val view =
                    tasks.views().singleOrNull { it.thread == invocation.threadId }
                        ?: return@synchronized Refinement.Rejected(ControlFailure.TASK_UNKNOWN)
                val controller =
                    view.controller ?: return@synchronized Refinement.Rejected(ControlFailure.NOT_CONTROLLER)
                val lease = view.lease ?: return@synchronized Refinement.Rejected(ControlFailure.NOT_CONTROLLER)
                when (val admission = tasks.authorize(invocation.threadId, controller)) {
                    ControlResult.Accepted ->
                        Refinement.Refined(PendingExactPlanApproval(subject, invocation, controller, lease, tasks))
                    is ControlResult.Rejected -> Refinement.Rejected(admission.failure)
                }
            }
    }

    private sealed interface State {
        data object Pending : State

        data class Resolved(val outcome: ExactPlanApprovalOutcome) : State
    }

    private var state: State = State.Pending

    @Synchronized
    fun respond(
        responder: ClientConnectionId,
        result: JsonElement,
    ): ExactPlanApprovalResolution {
        if (state is State.Resolved) return ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.ALREADY_RESOLVED)
        if (responder != controller)
            return ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.NOT_RESPONSIBLE_CONTROLLER)
        synchronized(tasks) {
            val view = tasks.views().singleOrNull { it.thread == invocation.threadId }
            if (view?.lease != lease || view.controller != controller)
                return resolve(ExactPlanApprovalOutcome.Rejected(PlanApprovalFailure.CONTROLLER_LEASE_CHANGED))
            when (val admission = tasks.authorize(invocation.threadId, responder)) {
                ControlResult.Accepted -> Unit
                is ControlResult.Rejected -> return resolve(ExactPlanApprovalOutcome.ControlRejected(admission.failure))
            }
            val response = result as? JsonObject
            val decision = response?.get("decision") as? JsonPrimitive
            if (response?.keys != setOf("decision") || decision?.isString != true)
                return resolve(ExactPlanApprovalOutcome.Rejected(PlanApprovalFailure.MALFORMED_RESPONSE))
            return resolve(
                when (decision.content) {
                    "accept" ->
                        ExactPlanApprovalOutcome.Approved(
                            AcceptedControllerPlan(subject, invocation, controller, lease)
                        )
                    "acceptForSession" ->
                        ExactPlanApprovalOutcome.Rejected(PlanApprovalFailure.SESSION_APPROVAL_UNSUPPORTED)
                    "decline" -> ExactPlanApprovalOutcome.Terminated(PlanApprovalTermination.DECLINED)
                    "cancel" -> ExactPlanApprovalOutcome.Terminated(PlanApprovalTermination.CANCELLED)
                    else -> ExactPlanApprovalOutcome.Rejected(PlanApprovalFailure.MALFORMED_RESPONSE)
                }
            )
        }
    }

    @Synchronized
    fun terminate(reason: PlanApprovalTermination): ExactPlanApprovalResolution =
        if (state is State.Resolved) ExactPlanApprovalResolution.Rejected(PlanApprovalFailure.ALREADY_RESOLVED)
        else resolve(ExactPlanApprovalOutcome.Terminated(reason))

    private fun resolve(outcome: ExactPlanApprovalOutcome): ExactPlanApprovalResolution {
        state = State.Resolved(outcome)
        return ExactPlanApprovalResolution.Resolved(outcome)
    }
}
