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
    fun `failed or mixed process states cannot prove exit`() {
        assertEquals(ObservedProcessState.UNAVAILABLE, admitObservedProcessState(1, "Z"))
        assertEquals(ObservedProcessState.UNAVAILABLE, admitObservedProcessState(0, "Z\nS"))
        assertEquals(ObservedProcessState.UNAVAILABLE, admitObservedProcessState(0, ""))
    }
}
