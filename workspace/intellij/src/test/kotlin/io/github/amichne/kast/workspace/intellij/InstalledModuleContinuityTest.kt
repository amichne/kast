package io.github.amichne.kast.workspace.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class InstalledModuleContinuityTest {
    @Test
    fun `one delayed project-model replacement may be recovered exactly once`() {
        val grace = Duration.ofSeconds(5)
        val continuity = InstalledModuleContinuity(grace)

        assertEquals(
            InstalledModuleContinuityAction.AVAILABLE,
            continuity.observe(available = true, monotonicNanos = 0),
        )
        assertEquals(
            InstalledModuleContinuityAction.REMATERIALIZE,
            continuity.observe(available = false, monotonicNanos = 1),
        )
        assertEquals(
            InstalledModuleContinuityAction.WAITING,
            continuity.observe(available = false, monotonicNanos = grace.toNanos() - 1),
        )
        assertEquals(
            InstalledModuleContinuityAction.AVAILABLE,
            continuity.observe(available = true, monotonicNanos = grace.toNanos()),
        )
        assertEquals(
            InstalledModuleContinuityAction.FAILED,
            continuity.observe(available = false, monotonicNanos = grace.toNanos() + 1),
        )
    }

    @Test
    fun `unrestored module materialization fails after the finite grace interval`() {
        val grace = Duration.ofSeconds(5)
        val continuity = InstalledModuleContinuity(grace)

        assertEquals(
            InstalledModuleContinuityAction.REMATERIALIZE,
            continuity.observe(available = false, monotonicNanos = 10),
        )
        assertEquals(
            InstalledModuleContinuityAction.WAITING,
            continuity.observe(
                available = false,
                monotonicNanos = 10 + grace.toNanos() - 1,
            ),
        )
        assertEquals(
            InstalledModuleContinuityAction.FAILED,
            continuity.observe(
                available = false,
                monotonicNanos = 10 + grace.toNanos(),
            ),
        )
    }
}
