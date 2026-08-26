package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import io.github.amichne.kast.workspace.contract.VfsPassiveReadUnavailableCause

/**
 * Proof transition: `(CanonicalWorkspaceRoot, ProjectReadEpoch<*>,
 * ProjectReadEpochObservation) -> VfsPassiveReadAdmission`.
 *
 * Establishes a capability only when the sole current observation is from the same source and has
 * equal state. [VfsPassiveReadAdmissionFailure] closes every expected moved or unavailable state.
 * Raw IDE extraction is permitted only by `AdmittedIdeProject.observeReadEpoch` before this pure
 * boundary; this transition performs no refresh, import, repair, listener, or semantic work.
 */
internal fun admitVfsPassiveReadObservation(
    canonicalRoot: CanonicalWorkspaceRoot,
    expectedEpoch: ProjectReadEpoch<*>,
    current: ProjectReadEpochObservation,
): VfsPassiveReadAdmission = when (current) {
    is ProjectReadEpochObservation.Observed -> when (
        expectedEpoch.relationTo(current.epoch)
    ) {
        ProjectReadEpochRelation.SAME -> VfsPassiveReadAdmission.Admitted(
            VfsPassiveReadCapability.issue(canonicalRoot, current.epoch),
        )
        ProjectReadEpochRelation.MOVED -> VfsPassiveReadAdmission.Rejected(
            VfsPassiveReadAdmissionFailure.Moved,
        )
        ProjectReadEpochRelation.INCOMPARABLE -> VfsPassiveReadAdmission.Rejected(
            VfsPassiveReadAdmissionFailure.Incomparable,
        )
    }
    is ProjectReadEpochObservation.Rejected -> VfsPassiveReadAdmission.Rejected(
        when (val failure = current.failure) {
            ProjectReadEpochObservationFailure.ProjectDisposed ->
                VfsPassiveReadAdmissionFailure.ProjectDisposed
            ProjectReadEpochObservationFailure.DumbMode ->
                VfsPassiveReadAdmissionFailure.DumbMode
            ProjectReadEpochObservationFailure.WrongThread -> unavailable(
                VfsPassiveReadUnavailableCause.WrongThread,
            )
            ProjectReadEpochObservationFailure.ProjectNotOpen -> unavailable(
                VfsPassiveReadUnavailableCause.ProjectNotOpen,
            )
            ProjectReadEpochObservationFailure.ProjectNotInitialized -> unavailable(
                VfsPassiveReadUnavailableCause.ProjectNotInitialized,
            )
            ProjectReadEpochObservationFailure.ProjectRootUnavailable -> unavailable(
                VfsPassiveReadUnavailableCause.ProjectRootUnavailable,
            )
            ProjectReadEpochObservationFailure.ProjectRootMalformed -> unavailable(
                VfsPassiveReadUnavailableCause.ProjectRootMalformed,
            )
            ProjectReadEpochObservationFailure.GradleModelUnavailable -> unavailable(
                VfsPassiveReadUnavailableCause.GradleModelUnavailable,
            )
            ProjectReadEpochObservationFailure.GradleModelIncomplete -> unavailable(
                VfsPassiveReadUnavailableCause.GradleModelIncomplete,
            )
            ProjectReadEpochObservationFailure.GradleModelAmbiguous -> unavailable(
                VfsPassiveReadUnavailableCause.GradleModelAmbiguous,
            )
            ProjectReadEpochObservationFailure.GradleRootUnavailable -> unavailable(
                VfsPassiveReadUnavailableCause.GradleRootUnavailable,
            )
            ProjectReadEpochObservationFailure.GradleRootMalformed -> unavailable(
                VfsPassiveReadUnavailableCause.GradleRootMalformed,
            )
            ProjectReadEpochObservationFailure.ImportTimestampsIncoherent -> unavailable(
                VfsPassiveReadUnavailableCause.ImportTimestampsIncoherent,
            )
            ProjectReadEpochObservationFailure.VfsBatchLimitExceeded -> unavailable(
                VfsPassiveReadUnavailableCause.VfsBatchLimitExceeded,
            )
            ProjectReadEpochObservationFailure.VfsPathMalformed -> unavailable(
                VfsPassiveReadUnavailableCause.VfsPathMalformed,
            )
            ProjectReadEpochObservationFailure.SignalExhausted -> unavailable(
                VfsPassiveReadUnavailableCause.SignalExhausted,
            )
            ProjectReadEpochObservationFailure.ReadPreempted -> unavailable(
                VfsPassiveReadUnavailableCause.ReadPreempted,
            )
            is ProjectReadEpochObservationFailure.ObservationFailed -> unavailable(
                VfsPassiveReadUnavailableCause.ObservationFailed(failure.stage),
            )
        },
    )
}

private fun unavailable(
    cause: VfsPassiveReadUnavailableCause,
): VfsPassiveReadAdmissionFailure.Unavailable =
    VfsPassiveReadAdmissionFailure.Unavailable(cause)
