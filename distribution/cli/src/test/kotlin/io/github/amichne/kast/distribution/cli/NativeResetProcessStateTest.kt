package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeResetProcessStateTest {
    @Test
    fun `only a successful single zombie state establishes process exit`() {
        for (state in listOf("Z", "Z+", "Zs")) {
            assertEquals(ResetObservedProcessState.EXITED, admitResetProcessState(ProcessObservation.Exited(0, state)))
        }
        for (state in listOf("S", "R+", "I", "T")) {
            assertEquals(ResetObservedProcessState.ACTIVE, admitResetProcessState(ProcessObservation.Exited(0, state)))
        }
        for (state in listOf("", "?", "Z\nS", "Z extra", "Z0", "Zombie", "Z?")) {
            assertEquals(
                ResetObservedProcessState.UNAVAILABLE,
                admitResetProcessState(ProcessObservation.Exited(0, state)),
            )
        }
        assertEquals(ResetObservedProcessState.UNAVAILABLE, admitResetProcessState(ProcessObservation.Exited(1, "Z")))
        assertEquals(ResetObservedProcessState.UNAVAILABLE, admitResetProcessState(ProcessObservation.Unavailable))
    }
}
