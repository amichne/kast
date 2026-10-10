package io.github.amichne.kast.workspace.contract

import java.util.UUID

/** Diagnostic ownership identity; it cannot grant project, epoch, or execution authority. */
sealed interface WorkspaceReadOperationIdentity {
    /** An exact in-process invocation whose transport diagnostic identity was not supplied. */
    class Opaque internal constructor() : WorkspaceReadOperationIdentity

    data class Traced(val trace: UUID) : WorkspaceReadOperationIdentity
}

/** Native execution owner observation, independent of model validity and caller waiting deadlines. */
sealed interface WorkspaceNativeReadSettlement {
    data object Quiescent : WorkspaceNativeReadSettlement

    class Running internal constructor(val operations: List<WorkspaceReadOperationIdentity>) :
        WorkspaceNativeReadSettlement

    /** Retirement denies admission but is not proof that outstanding native operations have terminated. */
    class Retired internal constructor(val operations: List<WorkspaceReadOperationIdentity>) :
        WorkspaceNativeReadSettlement
}

/** Preserve observed model evidence while truthful unsettled execution prevents preparation admission. */
fun observeWorkspaceReadSettlement(
    readiness: WorkspaceCapabilityReadiness,
    settlement: WorkspaceNativeReadSettlement,
): WorkspaceCapabilityReadiness =
    when (settlement) {
        WorkspaceNativeReadSettlement.Quiescent -> readiness
        is WorkspaceNativeReadSettlement.Running ->
            if (readiness is WorkspaceCapabilityReadiness.Blocked && readiness.reason.isRetired())
                readiness.copy(detail = WorkspaceReadinessDetail.UnsettledReads(readiness, settlement.operations))
            else
                WorkspaceCapabilityReadiness.Pending(
                    readiness.identity,
                    WorkspaceReadinessReason.NATIVE_WORK,
                    WorkspaceReadinessNextAction.OBSERVE_SETTLEMENT,
                    WorkspaceReadinessDetail.UnsettledReads(readiness, settlement.operations),
                )
        is WorkspaceNativeReadSettlement.Retired ->
            if (readiness is WorkspaceCapabilityReadiness.Blocked && readiness.reason.isRetired())
                readiness.copy(detail = WorkspaceReadinessDetail.UnsettledReads(readiness, settlement.operations))
            else
                WorkspaceCapabilityReadiness.Blocked(
                    readiness.identity,
                    WorkspaceReadinessReason.RETIRED_INCARNATION,
                    WorkspaceReadinessNextAction.ATTACH_HOST,
                    WorkspaceReadinessDetail.UnsettledReads(readiness, settlement.operations),
                )
    }

private fun WorkspaceReadinessReason.isRetired(): Boolean =
    this == WorkspaceReadinessReason.PROJECT_DISPOSED || this == WorkspaceReadinessReason.RETIRED_INCARNATION
