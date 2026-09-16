package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.protocol.contract.ProjectCloseApprovalPayload
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest

/** The implementation is private to controller admission; model input cannot construct this proof. */
internal sealed interface ControllerApprovedProjectClose {
    val request: WorkspaceLifecycleRequest.RequestUserClose
    val invocation: BrokerInvocationContext
}

internal class ProjectCloseApprovalGrant
private constructor(
    val approval: ControllerApprovedProjectClose,
    val assertion: String,
) {
    fun matches(context: BrokerInvocationContext, request: WorkspaceLifecycleRequest.RequestUserClose): Boolean =
        approval.invocation.threadId == context.threadId &&
            approval.invocation.turnId == context.turnId &&
            approval.invocation.callId == context.callId &&
            approval.request == request

    companion object {
        fun signed(approval: ControllerApprovedProjectClose, assertion: String) =
            ProjectCloseApprovalGrant(approval, assertion)
    }
}

internal fun ControllerApprovedProjectClose.payload() =
    ProjectCloseApprovalPayload(
        request.target,
        request.requestId,
        invocation.threadId.value,
        invocation.threadId.value,
        invocation.turnId.value,
        invocation.callId.value,
    )
