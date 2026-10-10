package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.appserver.protocol.codex.WorkspaceInspectionProjectionFailure
import io.github.amichne.kast.appserver.protocol.codex.appendWorkspaceInspection
import io.github.amichne.kast.kernel.Refinement

/** Pure wire projection after the explicit transport effect returns its native observation. */
internal fun projectWorkspaceInspection(
    dispatched: ProtocolRouting,
    access: WorkspaceExecutionAccess,
    observation: () -> WorkspaceExecutionLaneSnapshot,
    maximumBytes: Int,
    maximumResultBytes: Int,
    rejected: (WorkspaceInspectionProjectionFailure) -> ProtocolRouting.ReplyUpstream,
): ProtocolRouting {
    if (access != WorkspaceExecutionAccess.Observation || dispatched !is ProtocolRouting.ReplyUpstream)
        return dispatched
    return when (
        val projected = appendWorkspaceInspection(dispatched, observation(), maximumBytes, maximumResultBytes)
    ) {
        is Refinement.Refined -> projected.value
        is Refinement.Rejected -> rejected(projected.failure)
    }
}
