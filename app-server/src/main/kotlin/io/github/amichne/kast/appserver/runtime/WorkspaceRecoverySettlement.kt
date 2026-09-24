package io.github.amichne.kast.appserver.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class ResolvedMutationRecovery {
    PRIOR_STATE,
    ROLLED_BACK,
}

internal sealed interface WorkspaceRecoveryEvidence {
    data object Unproven : WorkspaceRecoveryEvidence

    data class Proven(val recovery: ResolvedMutationRecovery) : WorkspaceRecoveryEvidence
}

/** Carries a proved post-cancellation recovery from a provider through the cancelled execution lane. */
internal class WorkspaceRecoverySettlement : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceRecoverySettlement>

    @Volatile
    var evidence: WorkspaceRecoveryEvidence = WorkspaceRecoveryEvidence.Unproven
        private set

    fun confirm(recovery: ResolvedMutationRecovery) {
        evidence = WorkspaceRecoveryEvidence.Proven(recovery)
    }
}
