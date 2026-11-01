package io.github.amichne.kast.distribution.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservedProcessStateTest {
    @Test
    fun `zombie state is exited and sleeping process stays active`() {
        assertEquals(ObservedProcessState.EXITED, admitObservedProcessState(0, "Z\n"))
        assertEquals(ObservedProcessState.EXITED, admitObservedProcessState(0, "Z+"))
        assertEquals(ObservedProcessState.ACTIVE, admitObservedProcessState(0, "S+"))
    }

    @Test
    fun `Linux multithreaded process flag preserves the primary process state`() {
        // procps ps(1) documents lowercase l as the multithreaded STAT flag.
        for (state in listOf("Sl", "Rl", "Sl+", "Ssl")) {
            assertEquals(ObservedProcessState.ACTIVE, admitObservedProcessState(0, state), state)
        }
        assertEquals(ObservedProcessState.EXITED, admitObservedProcessState(0, "Zl"))
    }

    @Test
    fun `unknown flags and multiple Linux process states remain unavailable`() {
        for (state in listOf("l", "Slq", "Sl\nR", "Zl\nSl")) {
            assertEquals(ObservedProcessState.UNAVAILABLE, admitObservedProcessState(0, state), state)
        }
        assertEquals(ObservedProcessState.UNAVAILABLE, admitObservedProcessState(1, "Zl"))
    }

    @Test
    fun `failed or mixed process states cannot prove exit`() {
        assertEquals(ObservedProcessState.UNAVAILABLE, admitObservedProcessState(1, "Z"))
        assertEquals(ObservedProcessState.UNAVAILABLE, admitObservedProcessState(0, "Z\nS"))
        assertEquals(ObservedProcessState.UNAVAILABLE, admitObservedProcessState(0, ""))
    }
}
