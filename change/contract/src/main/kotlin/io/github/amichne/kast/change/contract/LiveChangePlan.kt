package io.github.amichne.kast.change.contract

import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash

/** Detached historical proof. A plan never grants current read or write authority. */
sealed interface LiveChangePlan {
    val planId: ChangePlanId
    val basis: ChangePlanningBasis.Live
    val target: PlannedDeclarationIdentity
    val content: WorkspaceSourceContentHash
    val writes: PlannedMutationWriteSet
}
