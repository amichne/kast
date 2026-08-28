package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class VfsPassiveAdmissionTest {
    @Test
    fun `generated report binds exact predecessor and success evidence`() {
        assertExactVfsPassiveReport()
    }

    @Test
    fun `one equal current observation issues exact root and epoch proof`() {
        val source = RecordingFreshnessEpochSource()
        val admittedProject = admittedFreshnessProject(source)
        val expectedEpoch = source.observeEpoch()

        val capability = admittedFreshnessCapability(
            admittedProject.admitVfsPassiveRead(expectedEpoch),
        )

        assertEquals(FIXTURE_ROOT, capability.canonicalRoot)
        assertEquals(
            ProjectReadEpochRelation.SAME,
            expectedEpoch.relationTo(capability.admittedEpoch),
        )
        assertNotSame(expectedEpoch, capability.admittedEpoch)
        assertEquals(2, source.observationCount)
    }
}
