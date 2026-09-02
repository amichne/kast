package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class InstalledProjectJvmContinuityTest {
    @Test
    fun `each delayed project JVM loss starts a fresh reassertion interval`() {
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
            InstalledProjectJvmContinuityAction.REASSERT,
            continuity.observe(available = false, monotonicNanos = grace.toNanos() + 1),
        )
        assertEquals(
            InstalledProjectJvmContinuityAction.WAITING,
            continuity.observe(available = false, monotonicNanos = grace.toNanos() * 2),
        )
    }

    @Test
    fun `unrestored project JVM schedules another reassertion after the finite grace interval`() {
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
            InstalledProjectJvmContinuityAction.REASSERT,
            continuity.observe(
                available = false,
                monotonicNanos = 10 + grace.toNanos(),
            ),
        )
        assertEquals(
            InstalledProjectJvmContinuityAction.WAITING,
            continuity.observe(
                available = false,
                monotonicNanos = 11 + grace.toNanos(),
            ),
        )
    }
}
