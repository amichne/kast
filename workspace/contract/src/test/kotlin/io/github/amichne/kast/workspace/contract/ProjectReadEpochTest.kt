package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProjectReadEpochTest {
    @Test
    fun `stable and moved observations compare within one source`() {
        val source = FixtureEpochSource(FixtureEpochState.stable())
        val before = source.observeEpoch()

        assertEquals(ProjectReadEpochRelation.SAME, before.relationTo(source.observeEpoch()))

        listOf(
            FixtureEpochState.stable().copy(projectModel = 2),
            FixtureEpochState.stable().copy(psi = 2),
            FixtureEpochState.stable().copy(vfs = 2),
            FixtureEpochState.stable().copy(rootModel = 2),
            FixtureEpochState.stable().copy(dumbCycle = 3),
        ).forEach { moved ->
            source.result = Refinement.Refined(moved)
            assertEquals(ProjectReadEpochRelation.MOVED, before.relationTo(source.observeEpoch()))
        }
    }

    @Test
    fun `equal observations from different project runtime sources are incomparable`() {
        val state = FixtureEpochState.stable()
        val first = FixtureEpochSource(state).observeEpoch()
        val second = FixtureEpochSource(state).observeEpoch()

        assertEquals(ProjectReadEpochRelation.INCOMPARABLE, first.relationTo(second))
        assertEquals(ProjectReadEpochRelation.INCOMPARABLE, second.relationTo(first))
    }

    @Test
    fun `every finite observation rejection remains exact`() {
        val failures = listOf(
            ProjectReadEpochObservationFailure.WrongThread,
            ProjectReadEpochObservationFailure.ProjectDisposed,
            ProjectReadEpochObservationFailure.ProjectNotOpen,
            ProjectReadEpochObservationFailure.ProjectNotInitialized,
            ProjectReadEpochObservationFailure.ProjectRootUnavailable,
            ProjectReadEpochObservationFailure.ProjectRootMalformed,
            ProjectReadEpochObservationFailure.DumbMode,
            ProjectReadEpochObservationFailure.GradleModelUnavailable,
            ProjectReadEpochObservationFailure.GradleModelIncomplete,
            ProjectReadEpochObservationFailure.GradleModelAmbiguous,
            ProjectReadEpochObservationFailure.GradleRootUnavailable,
            ProjectReadEpochObservationFailure.GradleRootMalformed,
            ProjectReadEpochObservationFailure.ImportTimestampsIncoherent,
            ProjectReadEpochObservationFailure.VfsBatchLimitExceeded,
            ProjectReadEpochObservationFailure.VfsPathMalformed,
            ProjectReadEpochObservationFailure.SignalExhausted,
            ProjectReadEpochObservationFailure.ReadPreempted,
        ) + ProjectReadEpochObservationStage.entries.map(
            ProjectReadEpochObservationFailure::ObservationFailed,
        )

        failures.forEach { failure ->
            val source = FixtureEpochSource(FixtureEpochState.stable()).apply {
                result = Refinement.Rejected(failure)
            }
            val rejected = assertInstanceOf(
                ProjectReadEpochObservation.Rejected::class.java,
                source.observe(),
            )
            assertEquals(failure, rejected.failure)
        }
    }
}

internal data class FixtureEpochState(
    val projectModel: Long,
    val psi: Long,
    val vfs: Long,
    val rootModel: Long,
    val dumbCycle: Long,
) {
    companion object {
        fun stable() = FixtureEpochState(
            projectModel = 1,
            psi = 1,
            vfs = 1,
            rootModel = 1,
            dumbCycle = 1,
        )
    }
}

internal class FixtureEpochSource(
    initial: FixtureEpochState,
) {
    var result: Refinement<FixtureEpochState, ProjectReadEpochObservationFailure> =
        Refinement.Refined(initial)
    var observationCount: Int = 0
        private set

    private val source = ProjectReadEpoch.Source.create {
        observationCount += 1
        result
    }

    fun observe(): ProjectReadEpochObservation = source.observe()

    fun observeEpoch(): ProjectReadEpoch<*> = when (val observation = source.observe()) {
        is ProjectReadEpochObservation.Observed -> observation.epoch
        is ProjectReadEpochObservation.Rejected -> error("unexpected ${observation.failure}")
    }
}
