package io.github.amichne.kast.cli.command.appserver

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.cli.command.*

internal fun appServerCommandGroup(): LocalCommandFamily {
    val actions = listOf(AppServerLeaf("status", CliProductCommand.APP_SERVER_STATUS, AppServerAction.Status))
    val root =
        KastCommandGroup(
                "app-server",
                """
                Inspect the persistent Kast App Server.

                           Diagnostics: the active installation writes service output to
                           state/broker/<installation-id>/service.log and the complete resolved launch
                           settings to state/broker/<installation-id>/launch-environment. Set
                           KAST_DEBUG=1 to also stream bounded
                           launch diagnostics to the calling process on stderr.
                """
                    .trimIndent(),
            )
            .subcommands(actions)
    return LocalCommandFamily(root, actions)
}

private class AppServerLeaf(name: String, command: CliProductCommand, private val action: AppServerAction) :
    LocalKastCommand(name, command) {
    override fun help(context: Context) = command.usage

    override fun resolveAction() = CliActionResolution.Selected(CliAction.Local.AppServer(action))
}
