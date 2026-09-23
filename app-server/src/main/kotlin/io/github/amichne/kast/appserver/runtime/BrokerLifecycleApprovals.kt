package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.protocol.codex.CloseApprovalEnvelope
import io.github.amichne.kast.appserver.protocol.codex.CloseApprovalParams
import io.github.amichne.kast.appserver.protocol.codex.CloseApprovalResponse
import io.github.amichne.kast.appserver.protocol.codex.CloseCompleted
import io.github.amichne.kast.appserver.protocol.codex.CloseCompletedEnvelope
import io.github.amichne.kast.appserver.protocol.codex.CloseDecision
import io.github.amichne.kast.appserver.protocol.codex.CloseItem
import io.github.amichne.kast.appserver.protocol.codex.CloseItemStatus
import io.github.amichne.kast.appserver.protocol.codex.CloseResolved
import io.github.amichne.kast.appserver.protocol.codex.CloseResolvedEnvelope
import io.github.amichne.kast.appserver.protocol.codex.CloseStarted
import io.github.amichne.kast.appserver.protocol.codex.CloseStartedEnvelope
import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Uses the existing controller lease, native approval exchange, and enrolled signing authority. */
internal class BrokerLifecycleApprovals(
    private val scope: CoroutineScope,
    private val tasks: SharedTaskSessions,
    private val options: KtorBrokerServerOptions,
    private val send: (ClientConnectionId, String) -> PlanApprovalSend,
) {
    private class Pending(
        val source: ClientConnectionId,
        val controller: ClientConnectionId,
        val lease: ControllerLeaseId,
        val context: BrokerInvocationContext,
        val request: WorkspaceLifecycleRequest.RequestUserClose,
        val response: CompletableDeferred<CloseDecision> = CompletableDeferred(),
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun submit(
        source: ClientConnectionId,
        binding: WorkspaceInvocationBinding,
        params: JsonObject,
        document: JsonObject,
        execute: (BrokerInvocationApproval) -> Deferred<WorkspaceExecutionResult>,
    ): Deferred<WorkspaceExecutionResult>? {
        val request = selectedRequest(params) ?: return null
        val entry =
            when (val admitted = admit(source, binding, request)) {
                is Refinement.Rejected -> return CompletableDeferred(rejected(document, admitted.failure))
                is Refinement.Refined -> admitted.value
            }
        return scope.async { run(entry, document, execute) }
    }

    private fun selectedRequest(params: JsonObject): WorkspaceLifecycleRequest.RequestUserClose? {
        val tool =
            options.sessionBootstrap?.tools?.definitions?.singleOrNull {
                it.name.value == (params["tool"] as? JsonPrimitive)?.content
            }
        if (tool?.operation != io.github.amichne.kast.protocol.contract.CanonicalOperation.WORKSPACE_LIFECYCLE)
            return null
        return try {
            json.decodeFromJsonElement<WorkspaceLifecycleRequest>(params["arguments"] ?: JsonNull)
                as? WorkspaceLifecycleRequest.RequestUserClose
        } catch (_: SerializationException) {
            null
        }
    }

    private fun admit(
        source: ClientConnectionId,
        binding: WorkspaceInvocationBinding,
        request: WorkspaceLifecycleRequest.RequestUserClose,
    ): Refinement<Pending, HostedPlanApprovalFailure> {
        val context =
            when (
                val admitted =
                    BrokerInvocationContext.admit(
                        binding.identity.thread.value,
                        binding.identity.turn.value,
                        binding.identity.call.value,
                        binding.workspace.root.path,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(HostedPlanApprovalFailure.INVALID_REQUEST)
            }
        return synchronized(tasks) {
            val view = tasks.views().singleOrNull { it.thread == context.threadId }
            val controller =
                view?.controller
                    ?: return@synchronized Refinement.Rejected(HostedPlanApprovalFailure.CONTROLLER_UNAVAILABLE)
            val lease =
                view.lease ?: return@synchronized Refinement.Rejected(HostedPlanApprovalFailure.CONTROLLER_UNAVAILABLE)
            if (tasks.authorize(context.threadId, controller) != ControlResult.Accepted)
                Refinement.Rejected(HostedPlanApprovalFailure.CONTROLLER_REJECTED)
            else Refinement.Refined(Pending(source, controller, lease, context, request))
        }
    }

    private suspend fun run(
        entry: Pending,
        document: JsonObject,
        execute: (BrokerInvocationApproval) -> Deferred<WorkspaceExecutionResult>,
    ): WorkspaceExecutionResult {
        val id = "kast-project-close-${UUID.randomUUID()}"
        synchronized(pending) {
            if (pending.size >= MAX_PENDING)
                return rejected(document, HostedPlanApprovalFailure.REQUEST_CAPACITY_EXCEEDED)
            pending[id] = entry
        }
        val item =
            CloseItem(
                id,
                "Kast workspace_lifecycle close '" +
                    json.encodeToString<WorkspaceLifecycleRequest>(entry.request).replace("'", "'\"'\"'") +
                    "'",
                entry.request.target.root,
                CloseItemStatus.IN_PROGRESS,
            )
        var status = CloseItemStatus.FAILED
        try {
            val grant =
                when (val approval = approve(entry, item)) {
                    is Refinement.Rejected -> {
                        if (approval.failure == HostedPlanApprovalFailure.DECLINED) status = CloseItemStatus.DECLINED
                        return rejected(document, approval.failure)
                    }
                    is Refinement.Refined -> approval.value
                }
            val result = execute(BrokerInvocationApproval.ProjectClose(grant)).await()
            status = CloseItemStatus.COMPLETED
            return result
        } finally {
            pending.remove(id)
            complete(entry, item.copy(status = status))
        }
    }

    private suspend fun approve(
        entry: Pending,
        item: CloseItem,
    ): Refinement<ProjectCloseApprovalGrant, HostedPlanApprovalFailure> {
        when (val delivered = deliver(entry, item)) {
            is Refinement.Rejected -> return delivered
            is Refinement.Refined -> Unit
        }
        val decision = withTimeoutOrNull(options.planApprovalWaitMillis) { entry.response.await() }
        when (decision) {
            CloseDecision.ACCEPT -> Unit
            CloseDecision.CANCEL -> return Refinement.Rejected(HostedPlanApprovalFailure.CANCELLED)
            CloseDecision.SESSION,
            CloseDecision.DECLINE -> return Refinement.Rejected(HostedPlanApprovalFailure.DECLINED)
            null -> return Refinement.Rejected(HostedPlanApprovalFailure.TIMED_OUT)
        }
        val proof =
            synchronized(tasks) {
                val view = tasks.views().singleOrNull { it.thread == entry.context.threadId }
                if (
                    view?.controller != entry.controller ||
                        view.lease != entry.lease ||
                        tasks.authorize(entry.context.threadId, entry.controller) != ControlResult.Accepted
                )
                    return@synchronized Refinement.Rejected(HostedPlanApprovalFailure.CONTROLLER_REJECTED)
                Refinement.Refined(AcceptedProjectClose(entry.request, entry.context))
            }
        return when (proof) {
            is Refinement.Rejected -> proof
            is Refinement.Refined -> options.projectCloseSigner(proof.value)
        }
    }

    private fun deliver(entry: Pending, item: CloseItem): Refinement<Unit, HostedPlanApprovalFailure> {
        val context = entry.context
        val started = CloseStarted(context.threadId.value, context.turnId.value, System.currentTimeMillis(), item)
        val target = entry.request.target
        val reason =
            "Close exactly this IDEA project: ${target.root}; application ${target.host}; " +
                "project incarnation ${target.project}. Unsaved documents and native vetoes still block closure."
        val approval =
            CloseApprovalParams(
                context.threadId.value,
                context.turnId.value,
                item.id,
                System.currentTimeMillis(),
                item.command,
                item.cwd,
                reason,
            )
        if (
            options.contracts.admit(CodexOwnedSchema.ITEM_STARTED_NOTIFICATION, json.encodeToJsonElement(started)) !is
                Validation.Validated ||
                options.contracts.admit(
                    CodexOwnedSchema.COMMAND_EXECUTION_REQUEST_APPROVAL_PARAMS,
                    json.encodeToJsonElement(approval),
                ) !is Validation.Validated
        )
            return Refinement.Rejected(HostedPlanApprovalFailure.NATIVE_SCHEMA_REJECTED)
        if (
            send(entry.controller, json.encodeToString(CloseStartedEnvelope(params = started))) ==
                PlanApprovalSend.UNAVAILABLE
        )
            return Refinement.Rejected(HostedPlanApprovalFailure.DISCONNECTED)
        return when (send(entry.controller, json.encodeToString(CloseApprovalEnvelope(item.id, approval)))) {
            PlanApprovalSend.UNAVAILABLE -> Refinement.Rejected(HostedPlanApprovalFailure.DISCONNECTED)
            PlanApprovalSend.SENT -> Refinement.Refined(Unit)
        }
    }

    private fun complete(entry: Pending, item: CloseItem) {
        val context = entry.context
        val completed = CloseCompleted(context.threadId.value, context.turnId.value, System.currentTimeMillis(), item)
        if (
            options.contracts.admit(CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION, json.encodeToJsonElement(completed))
                is Validation.Validated
        )
            send(entry.controller, json.encodeToString(CloseCompletedEnvelope(params = completed)))
        send(
            entry.controller,
            json.encodeToString(CloseResolvedEnvelope(params = CloseResolved(context.threadId.value, item.id))),
        )
    }

    private fun rejected(document: JsonObject, failure: HostedPlanApprovalFailure) =
        WorkspaceExecutionResult.Completed(
            ProtocolRouting.ReplyUpstream(toolFailure(document, "PROJECT_CLOSE_${failure.name}"))
        )

    private companion object {
        const val MAX_PENDING = 64
    }

    fun respond(client: ClientConnectionId, document: JsonObject): BrokerPlanApprovalReply {
        val id = (document["id"] as? JsonPrimitive)?.content ?: return BrokerPlanApprovalReply.Unowned
        if (!id.startsWith("kast-project-close-")) return BrokerPlanApprovalReply.Unowned
        val entry = pending[id] ?: return BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.UNKNOWN_REQUEST)
        if (entry.controller != client)
            return BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.NOT_RESPONSIBLE_CONTROLLER)
        val result =
            document["result"] ?: return BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.MALFORMED_RESPONSE)
        if (
            document.keys != setOf("id", "result") ||
                options.contracts.admit(CodexOwnedSchema.COMMAND_EXECUTION_REQUEST_APPROVAL_RESPONSE, result) !is
                    Validation.Validated
        )
            return BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.MALFORMED_RESPONSE)
        val response =
            try {
                json.decodeFromJsonElement<CloseApprovalResponse>(result)
            } catch (_: SerializationException) {
                return BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.MALFORMED_RESPONSE)
            }
        return if (entry.response.complete(response.decision)) BrokerPlanApprovalReply.Handled
        else BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.ALREADY_RESOLVED)
    }

    fun disconnect(client: ClientConnectionId) {
        pending.values
            .filter { it.controller == client || it.source == client }
            .forEach { it.response.complete(CloseDecision.CANCEL) }
    }

    fun cancel(thread: BrokerThreadId, turn: BrokerTurnId) {
        pending.values
            .filter { it.context.threadId == thread && it.context.turnId == turn }
            .forEach { it.response.complete(CloseDecision.CANCEL) }
    }
}

private class AcceptedProjectClose(
    override val request: WorkspaceLifecycleRequest.RequestUserClose,
    override val invocation: BrokerInvocationContext,
) : ControllerApprovedProjectClose
