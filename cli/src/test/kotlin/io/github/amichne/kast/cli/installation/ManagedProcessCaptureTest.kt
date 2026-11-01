package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.ObservedProcessState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagedProcessCaptureTest {
    private val unknown = ManagedProcessMetadata(42, true, "owner", null, 100)

    @Test
    fun `live missing executable needs native state rather than assumed absence`() {
        assertEquals(ManagedProcessCapture.NeedsState(42), admitManagedProcess(unknown, "/selected/idea", "owner"))
        assertEquals(ManagedProcessCapture.Unavailable, admitMissingManagedExecutable(ObservedProcessState.ACTIVE))
        assertEquals(ManagedProcessCapture.Unavailable, admitMissingManagedExecutable(ObservedProcessState.UNAVAILABLE))
    }

    @Test
    fun `a proven zombie cannot represent a running selected IDEA`() {
        assertEquals(ManagedProcessCapture.Exited, admitMissingManagedExecutable(ObservedProcessState.EXITED))
    }

    @Test
    fun `known owner and executable exclusions precede native state inspection`() {
        assertEquals(
            ManagedProcessCapture.OtherUser,
            admitManagedProcess(unknown.copy(owner = "other"), "/selected/idea", "owner"),
        )
        assertEquals(
            ManagedProcessCapture.OtherExecutable,
            admitManagedProcess(unknown.copy(executable = "/other/command"), "/selected/idea", "owner"),
        )
    }

    @Test
    fun `a selected process keeps exact incarnation and rejects missing owner`() {
        val selected = unknown.copy(executable = "/selected/idea")
        assertEquals(
            ManagedProcessCapture.Captured(ManagedIdeProcess(42, 100, "/selected/idea")),
            admitManagedProcess(selected, "/selected/idea", "owner"),
        )
        assertEquals(
            ManagedProcessCapture.Unavailable,
            admitManagedProcess(selected.copy(owner = null), "/selected/idea", "owner"),
        )
    }

    @Test
    fun `bounded ps adapter identifies its own live test JVM`() {
        assertEquals(ObservedProcessState.ACTIVE, observeManagedProcessState(ProcessHandle.current().pid()))
    }
}
