package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement

enum class CurrentWorkspaceEpochFailure {
    NOT_POSITIVE,
}

/**
 * Detached identity of one current imported-model and compiler epoch.
 *
 * The runtime authority owns monotonic issuance and must revalidate the epoch after live work;
 * this value prevents a current compiler epoch from being confused with a persisted evidence
 * generation.
 */
@JvmInline
value class CurrentWorkspaceEpoch private constructor(
    val value: Long,
) {
    companion object {
        /**
         * Proof transition:
         * `Long -> Refinement<CurrentWorkspaceEpoch, CurrentWorkspaceEpochFailure>`.
         *
         * Establishes a positive current-workspace epoch. [CurrentWorkspaceEpochFailure] is the
         * closed expected failure. Raw epoch values may enter only at the runtime-lane adapter and
         * may be extracted only at canonical identity, ordering, status, trace, or transport
         * boundaries.
         */
        fun parse(
            raw: Long,
        ): Refinement<CurrentWorkspaceEpoch, CurrentWorkspaceEpochFailure> =
            if (raw > 0L) {
                Refinement.Refined(CurrentWorkspaceEpoch(raw))
            } else {
                Refinement.Rejected(CurrentWorkspaceEpochFailure.NOT_POSITIVE)
            }
    }
}

/**
 * Detached proof that live compiler or PSI work was admitted for one exact canonical workspace
 * and one current imported-model/compiler epoch.
 *
 * The issuing runtime must revalidate this exact epoch after the live operation before serving its
 * detached result. This lease cannot represent a persisted source, reference, or graph generation.
 */
data class CurrentWorkspaceReadLease(
    val workspaceRoot: CanonicalWorkspaceRoot,
    val epoch: CurrentWorkspaceEpoch,
)
