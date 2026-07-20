package io.github.amichne.kast.server.mutation.coordination

import io.github.amichne.kast.api.contract.mutation.KastWorkspaceTaskId

internal data class MutationFinishBarrierResult(
    val workspaceTaskId: KastWorkspaceTaskId,
    val coordinationToken: MutationFinishCoordinationToken,
    val state: MutationFinishBarrierState,
)
