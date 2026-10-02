package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifecycleIngressTest {
    @Test
    fun `stop force selects the existing bounded termination mode`() {
        val stop = parseManagementCommand(listOf("stop", "--force", "--json"))
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Lifecycle(LifecycleOperation.STOP_FORCE, true)),
            stop,
        )
        val help = parseManagementCommand(listOf("stop", "--help")) as ManagementParsing.Print
        assertFalse(help.error)
        assertTrue(help.text.contains("--force"))
    }

    @Test
    fun `kill is no longer a public command`() {
        val removed = parseManagementCommand(listOf("kill")) as ManagementParsing.Print
        assertTrue(removed.error)
    }

    @Test
    fun `uninstall and reinstall accept force without installation discovery`() {
        listOf("uninstall", "reinstall").forEach { command ->
            assertTrue(parseManagementCommand(listOf(command, "--force", "--json")) is ManagementParsing.Selected)
            val help = parseManagementCommand(listOf(command, "--help")) as ManagementParsing.Print
            assertFalse(help.error)
            assertTrue(help.text.contains("--force"))
        }
    }

    @Test
    fun `ordinary stop and reinstall still select verified lifecycle`() {
        listOf("stop" to LifecycleOperation.STOP, "reinstall" to LifecycleOperation.REINSTALL).forEach {
            (name, operation) ->
            assertEquals(
                ManagementParsing.Selected(ManagementCommand.Lifecycle(operation, true)),
                parseManagementCommand(listOf(name, "--json")),
            )
        }
    }
}
