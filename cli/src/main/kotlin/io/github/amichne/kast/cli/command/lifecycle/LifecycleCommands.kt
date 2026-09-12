package io.github.amichne.kast.cli.command.lifecycle

import com.github.ajalt.clikt.core.Context
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.command.LifecycleKastCommand

/** Retired names fail explicitly; they carry no process, import or cache authority. */
internal fun lifecycleCommands(): List<LifecycleKastCommand> = listOf(StartCommand, StopCommand, StatusCommand)

private data object StartCommand : LifecycleKastCommand("start", CliLifecycleCommand.START) {
    override fun help(context: Context) = "Retired. Open the workspace in IntelliJ with the Kast plugin."

    override fun resolveAction(): CliActionResolution = CliActionResolution.Selected(CliAction.Lifecycle.Start)
}

private data object StopCommand : LifecycleKastCommand("stop", CliLifecycleCommand.STOP) {
    override fun help(context: Context) = "Retired. The existing IDE owns the project's lifetime."

    override fun resolveAction(): CliActionResolution = CliActionResolution.Selected(CliAction.Lifecycle.Stop)
}

private data object StatusCommand : LifecycleKastCommand("status", CliLifecycleCommand.STATUS) {
    override fun help(context: Context) = "Report the existing IDE's admitted project endpoint."

    override fun resolveAction(): CliActionResolution = CliActionResolution.Selected(CliAction.Lifecycle.Status)
}
