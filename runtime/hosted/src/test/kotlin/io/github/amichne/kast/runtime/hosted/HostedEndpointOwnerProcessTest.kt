package io.github.amichne.kast.runtime.hosted

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedEndpointOwnerProcessTest {
    @Test
    fun `kernel state and start ticks bind a zombie even with parentheses in its name`() {
        assertEquals(HostedEndpointOwnerProcess.Zombie(12345), parseLinuxOwnerProcess(42, stat("Z")))
        assertEquals(HostedEndpointOwnerProcess.Active, parseLinuxOwnerProcess(42, stat("S")))
        assertEquals(HostedEndpointOwnerProcess.Active, parseLinuxOwnerProcess(42, stat("D")))
    }

    @Test
    fun `foreign malformed and unknown process observations cannot establish exit`() {
        for (value in
            listOf(stat("Z").replaceFirst("42", "43"), stat("?"), "42 (idea) Z", stat("Z").replace("12345", "-1"))) {
            assertEquals(HostedEndpointOwnerProcess.Unavailable, parseLinuxOwnerProcess(42, value), value)
        }
    }

    private fun stat(state: String): String =
        "42 (host (analysis)) $state " + List(18) { "0" }.joinToString(" ") + " 12345 0 0\n"
}
