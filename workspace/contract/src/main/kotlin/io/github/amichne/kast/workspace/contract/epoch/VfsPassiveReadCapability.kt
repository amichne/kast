package io.github.amichne.kast.workspace.contract

/**
 * Detached proof that one exact workspace root was admitted at one current IDE-visible epoch.
 *
 * Construction remains compiler-confined to the hosted adapter. Consumers may retain the strong
 * root and epoch evidence for later read execution and revalidation, but cannot mint, parse, copy,
 * or alter the capability.
 */
class VfsPassiveReadCapability private constructor(
    val canonicalRoot: CanonicalWorkspaceRoot,
    val admittedEpoch: ProjectReadEpoch<*>,
) {
    companion object {
        /**
         * Proof transition: `(CanonicalWorkspaceRoot, ProjectReadEpoch<*>) ->
         * VfsPassiveReadCapability`.
         *
         * Preserves the exact root and current same-source epoch established by KVP-019 freshness
         * admission. Construction is permitted only from the friend hosted adapter after its sole
         * live epoch observation; raw IDE state is never accepted or retained here.
         */
        @JvmSynthetic
        internal fun issue(
            canonicalRoot: CanonicalWorkspaceRoot,
            admittedEpoch: ProjectReadEpoch<*>,
        ): VfsPassiveReadCapability = VfsPassiveReadCapability(canonicalRoot, admittedEpoch)
    }
}

/** Closed result of admitting one expected epoch as current IDE-visible state. */
sealed interface VfsPassiveReadAdmission {
    data class Admitted(
        val capability: VfsPassiveReadCapability,
    ) : VfsPassiveReadAdmission

    data class Rejected(
        val failure: VfsPassiveReadAdmissionFailure,
    ) : VfsPassiveReadAdmission
}

/** Finite freshness failures before KVP-020 queue admission or semantic execution begins. */
sealed interface VfsPassiveReadAdmissionFailure {
    data object ProjectDisposed : VfsPassiveReadAdmissionFailure
    data object DumbMode : VfsPassiveReadAdmissionFailure
    data object Moved : VfsPassiveReadAdmissionFailure
    data object Incomparable : VfsPassiveReadAdmissionFailure

    data class Unavailable(
        val cause: VfsPassiveReadUnavailableCause,
    ) : VfsPassiveReadAdmissionFailure
}

/** Finite unavailable causes that exclude the dedicated disposed and dumb freshness states. */
sealed interface VfsPassiveReadUnavailableCause {
    data object WrongThread : VfsPassiveReadUnavailableCause
    data object ProjectNotOpen : VfsPassiveReadUnavailableCause
    data object ProjectNotInitialized : VfsPassiveReadUnavailableCause
    data object ProjectRootUnavailable : VfsPassiveReadUnavailableCause
    data object ProjectRootMalformed : VfsPassiveReadUnavailableCause
    data object GradleModelUnavailable : VfsPassiveReadUnavailableCause
    data object GradleModelIncomplete : VfsPassiveReadUnavailableCause
    data object GradleModelAmbiguous : VfsPassiveReadUnavailableCause
    data object GradleRootUnavailable : VfsPassiveReadUnavailableCause
    data object GradleRootMalformed : VfsPassiveReadUnavailableCause
    data object ImportTimestampsIncoherent : VfsPassiveReadUnavailableCause
    data object VfsBatchLimitExceeded : VfsPassiveReadUnavailableCause
    data object VfsPathMalformed : VfsPassiveReadUnavailableCause
    data object SignalExhausted : VfsPassiveReadUnavailableCause
    data object ReadPreempted : VfsPassiveReadUnavailableCause

    data class ObservationFailed(
        val stage: ProjectReadEpochObservationStage,
    ) : VfsPassiveReadUnavailableCause
}
