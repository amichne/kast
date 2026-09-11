package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.recovery.AddDeclarationRecoveryService
import io.github.amichne.kast.change.recovery.AppliedAddDeclarationRecovery
import io.github.amichne.kast.change.recovery.RecordAppliedAddDeclarationResult

sealed interface LiveAppliedDurabilityState {
    data object Pending : LiveAppliedDurabilityState

    data class Recorded(val recovery: AppliedAddDeclarationRecovery) : LiveAppliedDurabilityState

    data object Rejected : LiveAppliedDurabilityState
}

/** One attempt's exact applied-record durability barrier; no callback can spend it twice. */
class LiveAppliedDurability(
    private val authority: LiveMutationAuthority,
    private val service: AddDeclarationRecoveryService,
) : MutationDurabilityBarrier {
    var state: LiveAppliedDurabilityState = LiveAppliedDurabilityState.Pending
        private set

    @Synchronized
    override fun recordApplied(): MutationDurabilityResult {
        if (state != LiveAppliedDurabilityState.Pending)
            return MutationDurabilityResult.Rejected(MutationDurabilityFailure.ALREADY_DECIDED)
        state = LiveAppliedDurabilityState.Rejected
        return when (val result = service.recordApplied(authority.recovery)) {
            is RecordAppliedAddDeclarationResult.Recorded -> {
                state = LiveAppliedDurabilityState.Recorded(result.recovery)
                MutationDurabilityResult.Durable
            }
            is RecordAppliedAddDeclarationResult.Rejected ->
                MutationDurabilityResult.Rejected(MutationDurabilityFailure.RECOVERY_EVIDENCE_REJECTED)
        }
    }
}
