package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.workspace.contract.ProjectReadEnvironmentRelation
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectReadEnvironmentTest {
    private val stable =
        ProjectReadEpochBoundary(
            ProjectReadEpochSignalSample.Value(1),
            fixtureProjectEpochRoot("/workspace/kast"),
            fixtureGradleEpochRoot("/workspace/kast"),
            10,
            10,
            ProjectReadEpochSignalSample.Value(1),
            ProjectReadEpochSignalSample.Value(1),
            ProjectReadEpochSignalSample.Value(1),
            ProjectReadEpochSignalSample.Value(1),
            false,
        )

    @Test
    fun `only content signals may move without invalidating environment`() {
        var boundary = stable
        val source =
            ProjectReadEpoch.Source.createWithEnvironment(
                observer = { ProjectReadEpochState.admit(boundary) },
                beforeWriteObserver = { ProjectReadEpochState.admit(boundary) },
                environment = ProjectReadEpochState::environment,
            )
        val original = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        listOf(
                stable.copy(psiModificationCount = ProjectReadEpochSignalSample.Value(2)),
                stable.copy(rootFilteredVfsBatchCount = ProjectReadEpochSignalSample.Value(2)),
            )
            .forEach {
                boundary = it
                assertEquals(
                    ProjectReadEnvironmentRelation.SAME,
                    original.environmentRelationTo((source.observe() as ProjectReadEpochObservation.Observed).epoch),
                )
            }
        listOf(
                stable.copy(projectModelRevision = ProjectReadEpochSignalSample.Value(2)),
                stable.copy(projectRoot = fixtureProjectEpochRoot("/workspace/moved")),
                stable.copy(gradleRoot = fixtureGradleEpochRoot("/workspace/moved")),
                stable.copy(lastImportTimestamp = 11),
                stable.copy(lastImportTimestamp = 11, lastSuccessfulImportTimestamp = 11),
                stable.copy(rootModelModificationCount = ProjectReadEpochSignalSample.Value(2)),
                stable.copy(dumbModeModificationCount = ProjectReadEpochSignalSample.Value(2)),
            )
            .forEach {
                boundary = it
                assertEquals(
                    ProjectReadEnvironmentRelation.CHANGED,
                    original.environmentRelationTo((source.observe() as ProjectReadEpochObservation.Observed).epoch),
                )
            }
    }

    @Test
    fun `equal environment metadata from another source is incomparable`() {
        fun observe(): ProjectReadEpoch<*> {
            val source =
                ProjectReadEpoch.Source.createWithEnvironment(
                    observer = { ProjectReadEpochState.admit(stable) },
                    beforeWriteObserver = { ProjectReadEpochState.admit(stable) },
                    environment = ProjectReadEpochState::environment,
                )
            return (source.observe() as ProjectReadEpochObservation.Observed).epoch
        }
        assertEquals(ProjectReadEnvironmentRelation.INCOMPARABLE, observe().environmentRelationTo(observe()))
    }
}
