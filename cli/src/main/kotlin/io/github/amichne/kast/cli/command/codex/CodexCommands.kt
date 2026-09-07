package io.github.amichne.kast.cli.command.codex

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliProductCommand
import io.github.amichne.kast.cli.command.LocalCommandFamily
import io.github.amichne.kast.cli.command.LocalKastCommand

internal fun codexCommandGroup(): LocalCommandFamily {
    val desktop = CodexDesktopCommand
    val cli = CodexCommand().subcommands(desktop)
    return LocalCommandFamily(cli, listOf(cli, desktop))
}

private class CodexCommand : LocalKastCommand(
    "codex",
    CliProductCommand.CODEX_CLI,
) {
    override val invokeWithoutSubcommand: Boolean = true
    override val printHelpOnEmptyArgs: Boolean = false

    override fun help(context: Context): String =
        "Use the qualified Kast tool catalog from a Codex host."

    override fun resolveAction() = if (currentContext.invokedSubcommand == null) {
        CliActionResolution.Selected(CliAction.Local.CodexCli)
    } else {
        io.github.amichne.kast.cli.command.CliNodeResolution.NoAction
    }
}

private data object CodexDesktopCommand : LocalKastCommand(
    "desktop",
    CliProductCommand.CODEX_DESKTOP,
) {
    override fun help(context: Context): String =
        "Launch Codex Desktop with Kast's process-local App Server façade."

    override fun resolveAction(): CliActionResolution = CliActionResolution.Selected(
        CliAction.Local.CodexDesktop,
    )
}
