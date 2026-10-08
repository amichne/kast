package io.github.amichne.kast.appserver.runtime

internal sealed interface HostedPlanApprovalRejection {
    data class Workspace(val cause: WorkspaceDemandFailure) : HostedPlanApprovalRejection
}

internal enum class HostedPlanApprovalFailure : HostedPlanApprovalRejection {
    INVALID_REQUEST,
    CONTROLLER_UNAVAILABLE,
    CONTROLLER_REJECTED,
    REQUEST_CAPACITY_EXCEEDED,
    NATIVE_SCHEMA_REJECTED,
    DECLINED,
    CANCELLED,
    DISCONNECTED,
    TIMED_OUT,
}

internal sealed interface BrokerInvocationApproval {
    data object Absent : BrokerInvocationApproval

    data class ProjectClose(val grant: ProjectCloseApprovalGrant) : BrokerInvocationApproval
}
