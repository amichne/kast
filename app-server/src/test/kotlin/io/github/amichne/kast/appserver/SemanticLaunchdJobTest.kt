package io.github.amichne.kast.appserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticLaunchdJobTest {
    @Test
    fun `a job still listed while its coordinator drains is withdrawn once launchd reports it absent`() {
        val observed =
            scripted(LaunchctlInvocation.Completed, LaunchctlInvocation.Completed, LaunchctlInvocation.Absent)
        var pauses = 0
        assertTrue(awaitSupervisorAbsence(observed, 5) { pauses++ })
        assertEquals(2, pauses)
    }

    @Test
    fun `withdrawal is unproven when the job outlives every observation or launchd fails`() {
        var pauses = 0
        assertFalse(awaitSupervisorAbsence({ LaunchctlInvocation.Completed }, 3) { pauses++ })
        assertEquals(2, pauses, "no pause follows the final observation")
        for (failure in
            listOf(
                LaunchctlInvocation.Rejected,
                LaunchctlInvocation.TimedOut,
                LaunchctlInvocation.Interrupted,
            )) assertFalse(awaitSupervisorAbsence(scripted(failure), 5) { error("a failed observation is final") })
    }

    private fun scripted(vararg observations: LaunchctlInvocation): () -> LaunchctlInvocation {
        val remaining = ArrayDeque(observations.toList())
        return { remaining.removeFirstOrNull() ?: error("an unexpected extra observation") }
    }
}
