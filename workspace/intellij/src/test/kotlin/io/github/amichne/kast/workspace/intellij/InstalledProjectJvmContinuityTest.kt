package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class InstalledProjectJvmContinuityTest {
    @Test
    fun `one delayed project JVM loss may be reasserted exactly once`() {
        val grace = Duration.ofSeconds(5)
        val continuity = InstalledProjectJvmContinuity(grace)

        assertEquals(
            InstalledProjectJvmContinuityAction.AVAILABLE,
            continuity.observe(available = true, monotonicNanos = 0),
        )
        assertEquals(
            InstalledProjectJvmContinuityAction.REASSERT,
            continuity.observe(available = false, monotonicNanos = 1),
        )
        assertEquals(
            InstalledProjectJvmContinuityAction.WAITING,
            continuity.observe(available = false, monotonicNanos = grace.toNanos() - 1),
        )
        assertEquals(
            InstalledProjectJvmContinuityAction.AVAILABLE,
            continuity.observe(available = true, monotonicNanos = grace.toNanos()),
        )
        assertEquals(
            InstalledProjectJvmContinuityAction.FAILED,
            continuity.observe(available = false, monotonicNanos = grace.toNanos() + 1),
        )
    }

    @Test
    fun `unrestored project JVM fails after the finite grace interval`() {
        val grace = Duration.ofSeconds(5)
        val continuity = InstalledProjectJvmContinuity(grace)

        assertEquals(
            InstalledProjectJvmContinuityAction.REASSERT,
            continuity.observe(available = false, monotonicNanos = 10),
        )
        assertEquals(
            InstalledProjectJvmContinuityAction.WAITING,
            continuity.observe(
                available = false,
                monotonicNanos = 10 + grace.toNanos() - 1,
            ),
        )
        assertEquals(
            InstalledProjectJvmContinuityAction.FAILED,
            continuity.observe(
                available = false,
                monotonicNanos = 10 + grace.toNanos(),
            ),
        )
    }
}
