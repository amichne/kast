package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfiguredModelLimitsTest {
    @Test
    fun `complete imported model admits more than 256 modules without configuration`() {
        val observation =
            DetachedModelObservation.Observed(detachedModelBoundary(modules = (0..258).map(::detachedModuleBoundary)))
        val admitted = DetachedIdeWorkspaceModel.admit(FIXTURE_ROOT, DETACHED_FIXTURE_COMPATIBILITY, observation)
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
        assertEquals(
            ProjectReadEpochVfsBatchObservation.RelevanceUnknown,
            observeProjectReadEpochVfsBatch(ProjectReadEpochVfsRoot.from(FIXTURE_ROOT), events, limits),
        )
    }
}
