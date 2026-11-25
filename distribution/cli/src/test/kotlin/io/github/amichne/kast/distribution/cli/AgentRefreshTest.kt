package io.github.amichne.kast.distribution.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AgentRefreshTest {
    @TempDir lateinit var home: Path

    @Test
    fun `only a completed installation refreshes connected agents`() {
        val upgrade = ManagementCommand.Upgrade()
        val expected = AgentRefresh.Needed(listOf(home.resolve(".local/bin/kast").toString(), "agent", "refresh"))
        assertEquals(expected, agentRefresh(ManagedUpgradeOutcome.ChildExited(0), upgrade, home))
        val unchanged =
            listOf(
                ManagedUpgradeOutcome.ChildExited(0) to upgrade.copy(dryRun = true),
                ManagedUpgradeOutcome.ChildExited(1) to upgrade,
                ManagedUpgradeOutcome.Rejected(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED) to upgrade,
                ManagedUpgradeOutcome.RecoveryRequired(BuildSelectionFailure.INSTALLER_CONTRACT_UNSUPPORTED) to upgrade,
            )
        for ((outcome, command) in unchanged) {
            assertEquals(AgentRefresh.NotNeeded, agentRefresh(outcome, command, home), "$outcome $command")
        }
    }

    @Test
    fun `a refresh runs the installed command and neither its failure nor its absence escapes`() {
        val executable = home.resolve("kast")
        val marker = home.resolve("ran")
        val needed = AgentRefresh.Needed(listOf(executable.toString(), "agent", "refresh"))
        needed.run(emptyMap())
        Files.writeString(executable, "#!/bin/sh\nprintf '%s' \"\$*\" > \"\$MARKER\"\nexit 1\n")
        executable.toFile().setExecutable(true)
        needed.run(mapOf("MARKER" to marker.toString()))
        assertEquals("agent refresh", Files.readString(marker))
        AgentRefresh.NotNeeded.run(emptyMap())
    }
}
