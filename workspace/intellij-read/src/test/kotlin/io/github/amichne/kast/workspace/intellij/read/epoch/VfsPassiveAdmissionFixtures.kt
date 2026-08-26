package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability

internal class RecordingFreshnessEpochSource(
    var observation: () -> Refinement<Int, ProjectReadEpochObservationFailure> = {
        Refinement.Refined(1)
    },
) {
    var observationCount: Int = 0
        private set

    val source: ProjectReadEpoch.Source<Int> = ProjectReadEpoch.Source.create {
        observationCount += 1
        observation()
    }

    fun observeEpoch(): ProjectReadEpoch<*> = when (val observed = source.observe()) {
        is ProjectReadEpochObservation.Observed -> observed.epoch
        is ProjectReadEpochObservation.Rejected -> error("unexpected ${observed.failure}")
    }
}

internal fun admittedFreshnessProject(
    source: RecordingFreshnessEpochSource,
): AdmittedIdeProject = when (val result = AdmittedIdeProject.admitObserved(
    opaqueProject(),
    FIXTURE_ROOT,
    FIXTURE_COMPATIBILITY,
    FIXTURE_COMPATIBILITY_POLICY,
    RecordingProjectObservation(),
    ExistingProjectReadEpochSourceFactory { _, _ -> Refinement.Refined(source.source) },
)) {
    is ExistingProjectAdmission.Admitted -> result.project
    is ExistingProjectAdmission.Rejected -> error("unexpected ${result.failure}")
}

internal fun admittedFreshnessCapability(
    admission: VfsPassiveReadAdmission,
): VfsPassiveReadCapability = when (admission) {
    is VfsPassiveReadAdmission.Admitted -> admission.capability
    is VfsPassiveReadAdmission.Rejected -> error("unexpected ${admission.failure}")
}

internal fun rejectedFreshnessFailure(
    admission: VfsPassiveReadAdmission,
): VfsPassiveReadAdmissionFailure = when (admission) {
    is VfsPassiveReadAdmission.Admitted -> error("unexpected admission")
    is VfsPassiveReadAdmission.Rejected -> admission.failure
}
