package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfiguredModelLimitsTest {
    @Test
    fun `configured module capacity admits the same complete model that defaults reject`() {
        val observation =
            DetachedModelObservation.Observed(detachedModelBoundary(modules = (0..258).map(::detachedModuleBoundary)))
        val defaults = DetachedIdeWorkspaceModel.admit(FIXTURE_ROOT, DETACHED_FIXTURE_COMPATIBILITY, observation)
        assertEquals(
            DetachedModelCaptureFailure.TOO_MANY_MODULES,
            (defaults as DetachedModelCapture.Rejected).failures.single(),
        )
        val limits = (ReadLimits.resolve(mapOf("KAST_READ_MODEL_MODULES" to "512")) as Refinement.Refined).value
        val admitted =
            DetachedIdeWorkspaceModel.admit(FIXTURE_ROOT, DETACHED_FIXTURE_COMPATIBILITY, observation, limits)
        assertEquals(259, (admitted as DetachedModelCapture.Captured).model.modules.size)
    }

    @Test
    fun `path identity and VFS batch bounds use the same retained policy`() {
        val limits =
            (ReadLimits.resolve(
                    mapOf("KAST_READ_MODEL_IDENTITY_CHARACTERS" to "4", "KAST_READ_EPOCH_VFS_EVENTS" to "1")
                ) as Refinement.Refined)
                .value
        assertTrue(refineIdentity("module") is Refinement.Refined)
        assertEquals(TextFailure.TOO_LONG, (refineIdentity("module", limits) as Refinement.Rejected).failure)
        val events =
            listOf(
                ProjectReadEpochVfsEvent.Change("/workspace/kast/a"),
                ProjectReadEpochVfsEvent.Change("/workspace/kast/b"),
            )
        assertEquals(
            ProjectReadEpochVfsBatchObservation.TouchesRoot,
            observeProjectReadEpochVfsBatch(ProjectReadEpochVfsRoot.from(FIXTURE_ROOT), events),
        )
        assertTrue(
            observeProjectReadEpochVfsBatch(ProjectReadEpochVfsRoot.from(FIXTURE_ROOT), events, limits)
                is ProjectReadEpochVfsBatchObservation.Rejected
        )
    }
}
