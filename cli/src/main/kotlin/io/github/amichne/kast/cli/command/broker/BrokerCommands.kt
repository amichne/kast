package io.github.amichne.kast.cli.command.broker

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliActionResolution
import io.github.amichne.kast.cli.command.CliProductCommand
import io.github.amichne.kast.cli.command.KastCommandGroup
import io.github.amichne.kast.cli.command.LocalCommandFamily
import io.github.amichne.kast.cli.command.LocalKastCommand

internal fun brokerCommandGroup(): LocalCommandFamily {
    val serve = BrokerServeCommand
    return LocalCommandFamily(
        BrokerCommand().subcommands(serve),
        listOf(serve),
    )
}

private class BrokerCommand : KastCommandGroup(
    "broker",
    "Host the persistent Codex tool broker from the installed Kotlin product.",
)

private data object BrokerServeCommand : LocalKastCommand(
    "serve",
    CliProductCommand.BROKER_SERVE,
) {
    override fun help(context: Context): String =
        "Serve the typed broker through Codex's local App Server control socket."

    override fun resolveAction(): CliActionResolution = CliActionResolution.Selected(
        CliAction.Local.BrokerServe,
    )
}
