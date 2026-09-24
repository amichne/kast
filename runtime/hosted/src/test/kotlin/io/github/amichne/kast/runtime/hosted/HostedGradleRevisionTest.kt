package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedGradleRevisionTest {
    @Test
    fun `an import discharges only changes observed before it began`() {
        val revision = HostedGradleRevision()
        assertEquals(false, revision.pending())
        assertEquals(true, revision.needsOpeningImport())
        val initial = revision.current()
        revision.changed()
        revision.acknowledge(initial)
        assertEquals(true, revision.pending())
        revision.acknowledge(revision.current())
        assertEquals(false, revision.pending())
        assertEquals(false, revision.needsOpeningImport())
        revision.changed()
        assertEquals(true, revision.pending())
        assertEquals(true, revision.needsOpeningImport())
    }

    @Test
    fun `root containment excludes sibling paths`() {
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/work/project")) as Refinement.Refined).value
        assertEquals(true, "/work/project/build.gradle.kts".isWithin(root))
        assertEquals(false, "/work/project-other/build.gradle.kts".isWithin(root))
    }
}
