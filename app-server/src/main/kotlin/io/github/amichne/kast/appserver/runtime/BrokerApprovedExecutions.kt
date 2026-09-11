package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.PlanApprovalItemCompletion
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.Refinement
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Owns approval waiting separately from workspace execution, including cancellation before a workspace is queued. */
internal class BrokerApprovedExecutions(private val scope: CoroutineScope, private val approvals: BrokerPlanApprovals) {
    private class Work(
        val source: ClientConnectionId,
        val request: HostedPlanApprovalRequest,
        val result: Deferred<WorkspaceExecutionResult>,
    )

    private val work = ConcurrentHashMap<String, Work>()

    fun submit(
        source: ClientConnectionId,
        request: HostedPlanApprovalRequest,
        rejected: (HostedPlanApprovalFailure) -> WorkspaceExecutionResult,
        execute: (BrokerInvocationApproval) -> Deferred<WorkspaceExecutionResult>,
    ): Deferred<WorkspaceExecutionResult> {
        val key = request.invocation.invocationId
        val result =
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    when (val admitted = approvals.approve(source, request)) {
                        is Refinement.Rejected -> rejected(admitted.failure)
                        is Refinement.Refined -> {
                            val approved = admitted.value
                            var completion = PlanApprovalItemCompletion.FAILED
                            try {
                                currentCoroutineContext().ensureActive()
                                val completed = execute(BrokerInvocationApproval.Granted(approved.grant)).await()
                                completion = completed.approvalItemCompletion()
                                completed
                            } finally {
                                approvals.complete(approved, completion)
                            }
                        }
                    }
                } finally {
                    work.remove(key)
                }
            }
        val previous = work.putIfAbsent(key, Work(source, request, result))
        if (previous != null) {
            result.cancel()
            return previous.result
        }
        result.start()
        return result
    }

    fun cancel(thread: BrokerThreadId, turn: BrokerTurnId) {
        approvals.cancel(thread, turn)
        work.values
            .filter { it.request.invocation.threadId == thread && it.request.invocation.turnId == turn }
            .forEach { it.result.cancel() }
    }

    fun retire(source: ClientConnectionId) {
        approvals.retire(source)
        work.values.filter { it.source == source }.forEach { it.result.cancel() }
    }

    private fun WorkspaceExecutionResult.approvalItemCompletion(): PlanApprovalItemCompletion {
        if (this !is WorkspaceExecutionResult.Completed) return PlanApprovalItemCompletion.FAILED
        val reply = routing as? ProtocolRouting.ReplyUpstream ?: return PlanApprovalItemCompletion.FAILED
        if (reply.certainty != InvocationCertainty.KNOWN) return PlanApprovalItemCompletion.FAILED
        val document =
            try {
                Json.parseToJsonElement(reply.message) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        return if ((document?.get("result") as? JsonObject)?.get("success") == JsonPrimitive(true))
            PlanApprovalItemCompletion.COMPLETED
        else PlanApprovalItemCompletion.FAILED
    }
}
