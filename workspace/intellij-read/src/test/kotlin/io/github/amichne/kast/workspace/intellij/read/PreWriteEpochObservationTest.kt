package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class PreWriteEpochObservationTest {
    @Test
    fun `write-boundary observation shares the exact source while ordinary EDT reads still reject`() {
        val execution = RecordingProjectReadEpochExecution()
        val model = ProjectReadEpochMetadataCounter()
        val source =
            LiveProjectReadEpochSource(
                    platform = RecordingProjectReadEpochPlatform(),
                    projectModelCounter = model,
                    vfsCounter = ProjectReadEpochMetadataCounter(),
                    execution = execution,
                )
                .source
        val prior = assertInstanceOf<ProjectReadEpochObservation.Observed>(source.observe()).epoch
        assertEquals(
            ProjectReadEpochObservationFailure.WrongThread,
            assertInstanceOf<ProjectReadEpochObservation.Rejected>(source.observeBeforeWrite()).failure,
        )
        execution.dispatchThread = true
        assertEquals(
            ProjectReadEpochObservationFailure.WrongThread,
            assertInstanceOf<ProjectReadEpochObservation.Rejected>(source.observeBeforeWrite()).failure,
        )
        execution.writeAccess = true
        val current = assertInstanceOf<ProjectReadEpochObservation.Observed>(source.observeBeforeWrite()).epoch
        assertEquals(ProjectReadEpochRelation.SAME, prior.relationTo(current))
        assertEquals(
            ProjectReadEpochObservationFailure.WrongThread,
            assertInstanceOf<ProjectReadEpochObservation.Rejected>(source.observe()).failure,
        )
        model.advance()
        val moved = assertInstanceOf<ProjectReadEpochObservation.Observed>(source.observeBeforeWrite()).epoch
        assertEquals(ProjectReadEpochRelation.MOVED, prior.relationTo(moved))
    }
}
