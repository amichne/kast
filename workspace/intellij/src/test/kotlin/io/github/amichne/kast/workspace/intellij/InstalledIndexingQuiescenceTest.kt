package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class InstalledIndexingQuiescenceTest {
    @Test
    fun `stable smart scanner evidence admits a retained skipped-task marker`() {
        val required = Duration.ofMillis(1_500)
        val quiescence = InstalledIndexingQuiescence(required)
        val skippedMarker = InstalledIndexingObservation(
            smart = true,
            scannerRunning = true,
            scannerQueued = true,
            scannerRevision = 7,
            projectRootsRevision = InstalledProjectRootsRevision(0),
            modulesReady = true,
        )

        assertEquals(
            InstalledIndexingStability.WAITING,
            quiescence.observe(skippedMarker, 10_000),
        )
        assertEquals(
            InstalledIndexingStability.STABLE,
            quiescence.observe(skippedMarker, 10_000 + required.toNanos()),
        )
    }

    @Test
    fun `running scanner or scanner transition restarts the stability interval`() {
        val required = Duration.ofMillis(1_500)
        val quiescence = InstalledIndexingQuiescence(required)
        val idle = InstalledIndexingObservation(
            smart = true,
            scannerRunning = false,
            scannerQueued = false,
            scannerRevision = 3,
            projectRootsRevision = InstalledProjectRootsRevision(0),
            modulesReady = true,
        )

        assertEquals(InstalledIndexingStability.WAITING, quiescence.observe(idle, 0))
        assertEquals(
            InstalledIndexingStability.WAITING,
            quiescence.observe(idle.copy(scannerRunning = true), required.toNanos()),
        )
        assertEquals(
            InstalledIndexingStability.WAITING,
            quiescence.observe(idle, required.toNanos() * 2),
        )
        assertEquals(
            InstalledIndexingStability.WAITING,
            quiescence.observe(
                idle.copy(scannerRevision = 4),
                required.toNanos() * 3,
            ),
        )
        assertEquals(
            InstalledIndexingStability.STABLE,
            quiescence.observe(
                idle.copy(scannerRevision = 4),
                required.toNanos() * 4,
            ),
        )
    }

    @Test
    fun `project roots revision restarts quiescence for SDK and language level changes`() {
        val required = Duration.ofMillis(1_500)
        val quiescence = InstalledIndexingQuiescence(required)
        val settled = InstalledIndexingObservation(
            smart = true,
            scannerRunning = false,
            scannerQueued = false,
            scannerRevision = 12,
            projectRootsRevision = InstalledProjectRootsRevision(40),
            modulesReady = true,
        )

        assertEquals(InstalledIndexingStability.WAITING, quiescence.observe(settled, 0))
        assertEquals(
            InstalledIndexingStability.WAITING,
            quiescence.observe(
                settled.copy(projectRootsRevision = InstalledProjectRootsRevision(41)),
                required.toNanos(),
            ),
        )
        assertEquals(
            InstalledIndexingStability.STABLE,
            quiescence.observe(
                settled.copy(projectRootsRevision = InstalledProjectRootsRevision(41)),
                required.toNanos() * 2,
            ),
        )
    }
}
