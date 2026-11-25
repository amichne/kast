package io.github.amichne.kast.distribution.cli

import java.io.File
import java.io.IOException
import java.nio.file.Path

/** Whether an upgrade leaves connected agents with an older plugin than the installation. */
internal sealed interface AgentRefresh {
    data object NotNeeded : AgentRefresh

    data class Needed(val command: List<String>) : AgentRefresh
}

/** Only a completed installation has a new plugin. The new executable owns the refresh, not this older process. */
internal fun agentRefresh(
    outcome: ManagedUpgradeOutcome,
    command: ManagementCommand.Upgrade,
    home: Path,
): AgentRefresh =
    if (outcome == ManagedUpgradeOutcome.ChildExited(0) && !command.dryRun)
        AgentRefresh.Needed(listOf(home.resolve(".local/bin/kast").toString(), "agent", "refresh"))
    else AgentRefresh.NotNeeded

/** The refresh reports on stderr, so it never joins the upgrade's result. Its failure never changes that result. */
internal fun AgentRefresh.run(environment: Map<String, String>) {
    val command =
        when (this) {
            AgentRefresh.NotNeeded -> return
            is AgentRefresh.Needed -> command
        }
    try {
        val builder = ProcessBuilder(command).redirectInput(File("/dev/null")).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(environment)
        val child = builder.start()
        child.inputStream.use { it.copyTo(System.err) }
        if (child.waitFor() != 0) agentRefreshNotice()
    } catch (_: IOException) {
        agentRefreshNotice()
    }
}

private fun agentRefreshNotice() =
    System.err.println("kast: upgrade: an agent keeps an older plugin. Run `kast agent refresh`.")
