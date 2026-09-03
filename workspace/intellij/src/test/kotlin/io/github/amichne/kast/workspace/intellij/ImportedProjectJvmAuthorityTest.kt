package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class ImportedProjectJvmAuthorityTest {
    @Test
    fun `settled imported model does not require the bootstrap sidecar SDK`() {
        val required = Duration.ofSeconds(2)
        val quiescence = InstalledIndexingQuiescence(required)
        val imported = InstalledIndexingObservation(
            smart = true,
            scannerRunning = false,
            scannerQueued = false,
            scannerRevision = 7,
            projectRootsRevision = InstalledProjectRootsRevision(11),
            modulesReady = true,
        )

        assertEquals(InstalledIndexingStability.WAITING, quiescence.observe(imported, 0))
        assertEquals(
            InstalledIndexingStability.STABLE,
            quiescence.observe(imported, required.toNanos()),
        )
    }
}
