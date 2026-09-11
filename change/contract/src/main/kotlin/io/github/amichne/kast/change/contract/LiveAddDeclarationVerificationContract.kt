package io.github.amichne.kast.change.contract

/** Live-state replacements for the two obligations whose published-generation meaning does not apply. */
enum class LiveAddDeclarationObligation {
    ORIGINAL_OWNER_EPOCH_MODEL_UNCHANGED_BEFORE_WRITE,
    RESULT_SAVED_COMMITTED_LIVE_STATE_OBSERVED,
}

/** Preserves every semantic obligation of the published path without manufacturing a generation. */
class LiveAddDeclarationVerificationContract private constructor() {
    val semanticObligations: List<AddDeclarationObligation> =
        AddDeclarationObligation.entries.filter { obligation ->
            when (obligation) {
                AddDeclarationObligation.GENERATION_UNCHANGED,
                AddDeclarationObligation.RESULT_GENERATION_PUBLISHED -> false
                AddDeclarationObligation.TARGET_PREIMAGE_UNCHANGED,
                AddDeclarationObligation.OWNER_AND_PROVENANCE_UNCHANGED,
                AddDeclarationObligation.DECLARED_WRITE_SET_CLOSED,
                AddDeclarationObligation.EXPECTED_POSTIMAGE_OBSERVED,
                AddDeclarationObligation.DECLARATION_IDENTITY_OBSERVED,
                AddDeclarationObligation.COMPILER_COLLISION_REMAINS_ABSENT,
                AddDeclarationObligation.OUTBOUND_BINDINGS_PRESERVED,
                AddDeclarationObligation.EXISTING_BINDINGS_PRESERVED,
                AddDeclarationObligation.COMPILER_DIAGNOSTICS_CLEAR -> true
            }
        }
    val liveObligations: List<LiveAddDeclarationObligation> = LiveAddDeclarationObligation.entries

    companion object {
        val required: LiveAddDeclarationVerificationContract = LiveAddDeclarationVerificationContract()
    }
}
