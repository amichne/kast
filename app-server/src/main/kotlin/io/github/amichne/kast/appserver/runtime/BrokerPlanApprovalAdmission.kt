package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class BrokerPlanApprovalAdmission(
    private val options: KtorBrokerServerOptions,
    private val approvedExecutions: BrokerApprovedExecutions,
) {
    fun submit(
        id: ClientConnectionId,
        binding: WorkspaceInvocationBinding,
        params: JsonObject,
        document: JsonObject,
        submit: (BrokerInvocationApproval) -> Deferred<WorkspaceExecutionResult>,
    ): Deferred<WorkspaceExecutionResult> {
        val operation =
            when (
                options.sessionBootstrap
                    ?.tools
                    ?.definitions
                    ?.singleOrNull { it.name.value == (params["tool"] as? JsonPrimitive)?.content }
                    ?.operation
            ) {
                io.github.amichne.kast.protocol.contract.CanonicalOperation.CHANGE_APPLY ->
                    HostedChangeApprovalOperation.APPLY
                io.github.amichne.kast.protocol.contract.CanonicalOperation.CHANGE_RECOVER ->
                    HostedChangeApprovalOperation.RECOVER
                else -> return submit(BrokerInvocationApproval.Absent)
            }
        fun rejected(failure: HostedPlanApprovalFailure): WorkspaceExecutionResult =
            WorkspaceExecutionResult.Completed(
                ProtocolRouting.ReplyUpstream(toolFailure(document, "PLAN_APPROVAL_${failure.name}"))
            )
        if (
            options.contracts.admit(
                io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema.DYNAMIC_TOOL_CALL_PARAMS,
                params,
            ) !is io.github.amichne.kast.kernel.Validation.Validated || document["id"] !is JsonPrimitive
        )
            return CompletableDeferred(rejected(HostedPlanApprovalFailure.INVALID_REQUEST))
        val context =
            when (
                val admitted =
                    io.github.amichne.kast.appserver.core.BrokerInvocationContext.admit(
                        threadId = binding.identity.thread.value,
                        turnId = binding.identity.turn.value,
                        callId = binding.identity.call.value,
                        workingDirectory = binding.workspace.root.path,
                    )
            ) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> admitted.value
                is io.github.amichne.kast.kernel.Refinement.Rejected ->
                    return CompletableDeferred(rejected(HostedPlanApprovalFailure.INVALID_REQUEST))
            }
        val request =
            when (val admitted = HostedPlanApprovalRequest.admit(operation, context, params["arguments"] ?: JsonNull)) {
                is io.github.amichne.kast.kernel.Refinement.Refined -> admitted.value
                is io.github.amichne.kast.kernel.Refinement.Rejected ->
                    return CompletableDeferred(rejected(admitted.failure))
            }
        return approvedExecutions.submit(source = id, request = request, rejected = ::rejected, execute = submit)
    }
}

/** Preserves both the selected registered workspace and the admitted call identity across approval admission. */
internal data class WorkspaceInvocationBinding(
    val workspace: io.github.amichne.kast.appserver.WorkspaceRegistration,
    val identity: InvocationIdentity,
)
