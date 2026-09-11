package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexPlanApprovalProjection
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.appserver.protocol.codex.PlanApprovalItemCompletion
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Service-owned explicit requests. Preparation and controller waiting never hold a workspace execution permit. */
internal class BrokerPlanApprovals(
    private val tasks: SharedTaskSessions,
    private val contracts: CodexProtocolContracts,
    private val gateway: HostedPlanApprovalGateway,
    private val delivery: BrokerPlanApprovalDelivery,
    private val publish: (SessionActivity) -> Unit,
    private val waitMillis: Long,
) {
    private val requests = ConcurrentHashMap<String, BrokerPlanApprovalPending>()

    suspend fun approve(
        source: ClientConnectionId,
        request: HostedPlanApprovalRequest,
    ): Refinement<ApprovedBrokerPlanInvocation, HostedPlanApprovalFailure> {
        val challenge =
            when (val prepared = prepare(source, request)) {
                is Refinement.Rejected -> return prepared
                is Refinement.Refined -> prepared.value
            }
        val pending =
            when (val registered = register(source, challenge)) {
                is Refinement.Rejected -> return registered
                is Refinement.Refined -> registered.value
            }
        return awaitApproval(pending)
    }

    private suspend fun prepare(
        source: ClientConnectionId,
        request: HostedPlanApprovalRequest,
    ): Refinement<HostedPlanApprovalChallenge, HostedPlanApprovalFailure> {
        publish(SessionActivity(source, SessionStage.APPROVAL_PREPARE, SessionOutcome.STARTED))
        val prepared =
            try {
                withTimeout(waitMillis) { gateway.prepare(request) }
            } catch (_: TimeoutCancellationException) {
                return rejected(source, SessionStage.APPROVAL_PREPARE, HostedPlanApprovalFailure.TIMED_OUT)
            } catch (cancelled: CancellationException) {
                rejected(source, SessionStage.APPROVAL_PREPARE, HostedPlanApprovalFailure.CANCELLED)
                throw cancelled
            } catch (_: RuntimeException) {
                return rejected(source, SessionStage.APPROVAL_PREPARE, HostedPlanApprovalFailure.UNAVAILABLE)
            }
        currentCoroutineContext().ensureActive()
        val challenge =
            when (prepared) {
                is Refinement.Refined -> prepared.value
                is Refinement.Rejected -> return rejected(source, SessionStage.APPROVAL_PREPARE, prepared.failure)
            }
        if (challenge.request !== request)
            return rejected(source, SessionStage.APPROVAL_PREPARE, HostedPlanApprovalFailure.CHALLENGE_REJECTED)
        publish(SessionActivity(source, SessionStage.APPROVAL_PREPARE, SessionOutcome.COMPLETED))
        return Refinement.Refined(challenge)
    }

    private fun register(
        source: ClientConnectionId,
        challenge: HostedPlanApprovalChallenge,
    ): Refinement<BrokerPlanApprovalPending, HostedPlanApprovalFailure> {
        val request = challenge.request
        val decision =
            when (val opened = PendingExactPlanApproval.open(challenge.subject, request.invocation, tasks)) {
                is Refinement.Refined -> opened.value
                is Refinement.Rejected ->
                    return rejected(
                        source,
                        SessionStage.APPROVAL_REQUEST,
                        HostedPlanApprovalFailure.CONTROLLER_UNAVAILABLE,
                    )
            }
        val id = "$REQUEST_PREFIX${UUID.randomUUID()}"
        val projection =
            when (
                val projected =
                    CodexPlanApprovalProjection.prepare(
                        challenge = challenge,
                        requestId = id,
                        startedAt = delivery.clock.instant(),
                        contracts = contracts,
                    )
            ) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> return rejected(source, SessionStage.APPROVAL_REQUEST, projected.failure)
            }
        val pending =
            BrokerPlanApprovalPending(
                id = id,
                source = source,
                decision = decision,
                projection = projection,
                tasks = tasks,
                delivery = delivery,
            )
        return publishRequest(pending)
    }

    private fun publishRequest(
        pending: BrokerPlanApprovalPending
    ): Refinement<BrokerPlanApprovalPending, HostedPlanApprovalFailure> {
        synchronized(requests) {
            if (requests.size >= BrokerOperationalLimits.maximumServerRequests)
                return rejected(
                    pending.source,
                    SessionStage.APPROVAL_REQUEST,
                    HostedPlanApprovalFailure.REQUEST_CAPACITY_EXCEEDED,
                )
            tasks.pending(pending.decision.invocation.threadId, pending.id)
            requests[pending.id] = pending
        }
        publish(SessionActivity(pending.source, SessionStage.APPROVAL_REQUEST, SessionOutcome.STARTED))
        if (
            delivery.send(pending.decision.controller, pending.projection.started.toString()) ==
                PlanApprovalSend.UNAVAILABLE ||
                delivery.send(pending.decision.controller, pending.projection.request.toString()) ==
                    PlanApprovalSend.UNAVAILABLE
        )
            pending.terminate(PlanApprovalTermination.DISCONNECTED)
        return Refinement.Refined(pending)
    }

    private suspend fun awaitApproval(
        pending: BrokerPlanApprovalPending
    ): Refinement<ApprovedBrokerPlanInvocation, HostedPlanApprovalFailure> {
        try {
            return withTimeout(waitMillis) {
                when (val outcome = pending.outcome.await()) {
                    is ExactPlanApprovalOutcome.Approved -> redeem(pending, outcome.proof)
                    is ExactPlanApprovalOutcome.Terminated -> finishRejected(pending, outcome.reason.failure())
                    is ExactPlanApprovalOutcome.Rejected,
                    is ExactPlanApprovalOutcome.ControlRejected ->
                        finishRejected(pending, HostedPlanApprovalFailure.CONTROLLER_REJECTED)
                }
            }
        } catch (_: TimeoutCancellationException) {
            pending.terminate(PlanApprovalTermination.TIMED_OUT)
            return finishRejected(pending, HostedPlanApprovalFailure.TIMED_OUT)
        } catch (cancelled: CancellationException) {
            pending.terminate(PlanApprovalTermination.CANCELLED)
            finishRejected(pending, HostedPlanApprovalFailure.CANCELLED)
            throw cancelled
        } catch (_: RuntimeException) {
            pending.terminate(PlanApprovalTermination.OWNER_RETIRED)
            return finishRejected(pending, HostedPlanApprovalFailure.UNAVAILABLE)
        }
    }

    private suspend fun redeem(
        pending: BrokerPlanApprovalPending,
        proof: ControllerApprovedPlan,
    ): Refinement<ApprovedBrokerPlanInvocation, HostedPlanApprovalFailure> {
        val source = pending.source
        publish(SessionActivity(source, SessionStage.APPROVAL_REQUEST, SessionOutcome.COMPLETED))
        publish(SessionActivity(source, SessionStage.APPROVAL_REDEEM, SessionOutcome.STARTED))
        currentCoroutineContext().ensureActive()
        return when (val redeemed = gateway.redeem(proof)) {
            is Refinement.Rejected -> finishRejected(pending, redeemed.failure)
            is Refinement.Refined -> {
                val grant = redeemed.value
                if (grant.approval !== proof) finishRejected(pending, HostedPlanApprovalFailure.GRANT_REJECTED)
                else {
                    publish(SessionActivity(source, SessionStage.APPROVAL_REDEEM, SessionOutcome.COMPLETED))
                    Refinement.Refined(ApprovedBrokerPlanInvocation(grant, pending))
                }
            }
        }
    }

    fun respond(client: ClientConnectionId, document: JsonObject): BrokerPlanApprovalReply {
        val id = document["id"] as? JsonPrimitive ?: return BrokerPlanApprovalReply.Unowned
        if (!id.isString || !id.content.startsWith(REQUEST_PREFIX)) return BrokerPlanApprovalReply.Unowned
        val pending =
            requests[id.content] ?: return BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.UNKNOWN_REQUEST)
        val result = document["result"] ?: JsonNull
        val admitted =
            if (
                document.keys == setOf("id", "result") &&
                    contracts.admit(CodexOwnedSchema.FILE_CHANGE_REQUEST_APPROVAL_RESPONSE, result) is
                        Validation.Validated
            )
                result
            else JsonNull
        return when (val resolution = pending.decision.respond(client, admitted)) {
            is ExactPlanApprovalResolution.Rejected -> BrokerPlanApprovalReply.Rejected(resolution.failure)
            is ExactPlanApprovalResolution.Resolved -> {
                pending.resolve(resolution)
                BrokerPlanApprovalReply.Handled
            }
        }
    }

    fun disconnect(client: ClientConnectionId) =
        requests.values
            .filter { it.decision.controller == client }
            .forEach { it.terminate(PlanApprovalTermination.DISCONNECTED) }

    fun retire(source: ClientConnectionId) =
        requests.values.filter { it.source == source }.forEach { it.terminate(PlanApprovalTermination.OWNER_RETIRED) }

    fun cancel(thread: BrokerThreadId, turn: BrokerTurnId) =
        requests.values
            .filter { it.decision.invocation.threadId == thread && it.decision.invocation.turnId == turn }
            .forEach { it.terminate(PlanApprovalTermination.CANCELLED) }

    fun complete(invocation: ApprovedBrokerPlanInvocation, status: PlanApprovalItemCompletion) {
        requests.remove(invocation.pending.id, invocation.pending)
        invocation.pending.complete(status)
    }

    private fun finishRejected(
        pending: BrokerPlanApprovalPending,
        failure: HostedPlanApprovalFailure,
    ): Refinement.Rejected<HostedPlanApprovalFailure> {
        requests.remove(pending.id, pending)
        pending.complete(
            if (failure in setOf(HostedPlanApprovalFailure.DECLINED, HostedPlanApprovalFailure.CANCELLED))
                PlanApprovalItemCompletion.DECLINED
            else PlanApprovalItemCompletion.FAILED
        )
        return rejected(pending.source, SessionStage.APPROVAL_REQUEST, failure)
    }

    private fun rejected(
        source: ClientConnectionId,
        stage: SessionStage,
        failure: HostedPlanApprovalFailure,
    ): Refinement.Rejected<HostedPlanApprovalFailure> {
        publish(SessionActivity(source, stage, SessionOutcome.REJECTED))
        return Refinement.Rejected(failure)
    }

    private fun PlanApprovalTermination.failure(): HostedPlanApprovalFailure =
        when (this) {
            PlanApprovalTermination.DECLINED -> HostedPlanApprovalFailure.DECLINED
            PlanApprovalTermination.CANCELLED -> HostedPlanApprovalFailure.CANCELLED
            PlanApprovalTermination.DISCONNECTED -> HostedPlanApprovalFailure.DISCONNECTED
            PlanApprovalTermination.TIMED_OUT -> HostedPlanApprovalFailure.TIMED_OUT
            PlanApprovalTermination.OWNER_RETIRED -> HostedPlanApprovalFailure.OWNER_RETIRED
        }

    private companion object {
        const val REQUEST_PREFIX = "kast-plan-approval-"
    }
}
