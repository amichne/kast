package io.github.amichne.kast.distribution.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifecycleIngressTest {
    @Test
    fun `stop selects exact workspace scope without persistent disablement`() {
        val stop = parseManagementCommand(listOf("stop", "--all", "--json"))
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Stop(WorkspaceScope.All, true)),
            stop,
        )
        val help = parseManagementCommand(listOf("stop", "--help")) as ManagementParsing.Print
        assertFalse(help.error)
        assertTrue(help.text.contains("--workspace"))
        assertTrue(
            (parseManagementCommand(listOf("stop", "--all", "--workspace", "/repo")) as ManagementParsing.Print).error
        )
        assertTrue((parseManagementCommand(listOf("stop", "--force")) as ManagementParsing.Print).error)
    }

    @Test
    fun `stop report names each stopped workspace`() {
        assertEquals(
            "Stopped 2 workspace Hosts; the next semantic request may start them.\n  /work/alpha\n  /work/beta",
            workspaceStopReport(WorkspaceStopOutcome.Stopped(listOf("/work/alpha", "/work/beta"))),
        )
        assertEquals(
            "Stopped 0 workspace Hosts; the next semantic request may start them.",
            workspaceStopReport(WorkspaceStopOutcome.Stopped(emptyList())),
        )
    }

    @Test
    fun `kill is no longer a public command`() {
        val removed = parseManagementCommand(listOf("kill")) as ManagementParsing.Print
        assertTrue(removed.error)
    }

    @Test
    fun `uninstall takes no harness because agent configuration belongs to the agent`() {
        val harnessUninstalls = listOf("codex", "skill", "pi").map { listOf("uninstall", it) }
        for (arguments in harnessUninstalls) {
            assertTrue((parseManagementCommand(arguments) as ManagementParsing.Print).error)
        }
    }

    @Test
    fun `retired management surfaces reject before installation discovery`() {
        for (arguments in
            listOf(
                listOf("container"),
                listOf("container", "create", "example"),
                listOf("reinstall"),
                listOf("reinstall", "--force"),
                listOf("disconnect", "pi"),
                listOf("upgrade", "--idea-home", "/idea"),
                listOf("upgrade", "--restart-ide"),
            )) {
            assertTrue((parseManagementCommand(arguments) as ManagementParsing.Print).error, arguments.toString())
        }
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Uninstall),
            parseManagementCommand(listOf("uninstall")),
        )
    }

    @Test
    fun `ordinary stop uses current workspace and help lists only supported management verbs`() {
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Stop(WorkspaceScope.Current, true)),
            parseManagementCommand(listOf("stop", "--json")),
        )
        val help = parseManagementCommand(listOf("--help")) as ManagementParsing.Print
        assertFalse(help.error)
        for (name in listOf("container", "reinstall", "disconnect", "hook", "workspace")) assertFalse(
            Regex("(?m)^  $name\\s").containsMatchIn(help.text),
            help.text,
        )
        for (name in listOf("install", "status", "stop", "versions", "upgrade", "uninstall")) assertTrue(
            Regex("(?m)^  $name\\s").containsMatchIn(help.text),
            help.text,
        )
    }
}
