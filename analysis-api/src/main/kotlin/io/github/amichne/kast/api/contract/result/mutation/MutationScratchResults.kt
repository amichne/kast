package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.contract.MutationAttemptId
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
enum class MutationScratchOwnership {
    OWNED,
    UNOWNED,
}

@Serializable
enum class MutationScratchRole {
    QUARANTINE,
    PREPARED,
    PREPARED_CLEANUP,
    QUARANTINE_CLEANUP,
    UNOWNED_INTERNAL,
}

@Serializable
enum class MutationScratchState {
    ABSENT,
    PRESENT,
    UNSAFE,
}

@Serializable
data class MutationScratchObservation(
    @DocField(description = "Normalized absolute internal scratch path.")
    val filePath: String,
    @DocField(description = "Whether this exact path is journal-owned by the admitted attempt.")
    val ownership: MutationScratchOwnership,
    @DocField(description = "Closed journal role, or UNOWNED_INTERNAL for discovered foreign entries.")
    val role: MutationScratchRole,
    @DocField(description = "Descriptor-secure exact entry state.")
    val state: MutationScratchState,
    @DocField(description = "Exact SHA-256, required only for PRESENT regular files.")
    val sha256: ExactFileImageSha256? = null,
) {
    init {
        require((state == MutationScratchState.PRESENT) == (sha256 != null)) {
            "Mutation scratch SHA-256 is required if and only if state is PRESENT"
        }
        require(ownership == MutationScratchOwnership.OWNED || state != MutationScratchState.ABSENT) {
            "Unowned scratch observations cannot assert absence"
        }
        require(
            (ownership == MutationScratchOwnership.UNOWNED) ==
                (role == MutationScratchRole.UNOWNED_INTERNAL),
        ) { "Mutation scratch ownership and role must agree" }
    }
}

@Serializable
data class MutationScratchInspectResult(
    val mutationAttemptId: MutationAttemptId,
    val observations: List<MutationScratchObservation>,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(observations.zipWithNext().all { (left, right) -> left.filePath < right.filePath }) {
            "Mutation scratch inspection observations must be strictly sorted and unique by filePath"
        }
    }
}

@Serializable
enum class MutationScratchRecoveryOutcome {
    RESTORED_PREIMAGE,
    FINALIZED_POSTIMAGE,
}

@Serializable
enum class MutationScratchTargetState {
    ABSENT,
    PRESENT,
}

@Serializable
data class MutationScratchRecoveryResult(
    val mutationAttemptId: MutationAttemptId,
    val action: MutationScratchRecoveryAction,
    val outcome: MutationScratchRecoveryOutcome,
    val targetState: MutationScratchTargetState,
    val targetSha256: ExactFileImageSha256? = null,
    val scratchObservations: List<MutationScratchObservation>,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require((targetState == MutationScratchTargetState.PRESENT) == (targetSha256 != null)) {
            "Mutation scratch recovery target SHA-256 is required if and only if target is PRESENT"
        }
        require(
            scratchObservations.map(MutationScratchObservation::role) == listOf(
                MutationScratchRole.QUARANTINE,
                MutationScratchRole.PREPARED,
                MutationScratchRole.PREPARED_CLEANUP,
                MutationScratchRole.QUARANTINE_CLEANUP,
            ),
        ) { "Mutation scratch recovery must return exactly four owned observations in role order" }
        require(scratchObservations.all { observation ->
            observation.ownership == MutationScratchOwnership.OWNED &&
                observation.state == MutationScratchState.ABSENT
        }) { "Successful mutation scratch recovery must remove every supplied scratch role" }
        require(
            (action == MutationScratchRecoveryAction.RESTORE_PREIMAGE) ==
                (outcome == MutationScratchRecoveryOutcome.RESTORED_PREIMAGE),
        ) { "Mutation scratch recovery action and outcome must agree" }
    }
}
