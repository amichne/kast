package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshEffectResult
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedGradleRevisionTest {
    @Test
    fun `an import discharges only changes observed before it began`() {
        val revision = HostedGradleRevision()
        assertEquals(false, revision.pending())
        val initial = revision.beginOwnedImport()
        revision.changed()
        initial.settled(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(true, revision.pending())
        revision.beginOwnedImport().settled(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(false, revision.pending())
        revision.changed()
        assertEquals(true, revision.pending())
    }

    @Test
    fun `root containment excludes sibling paths`() {
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/work/project")) as Refinement.Refined).value
        assertEquals(true, "/work/project/build.gradle.kts".isWithin(root))
        assertEquals(false, "/work/project-other/build.gradle.kts".isWithin(root))
    }
}
