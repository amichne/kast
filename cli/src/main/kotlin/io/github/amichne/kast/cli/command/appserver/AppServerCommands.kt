package io.github.amichne.kast.cli.command.appserver

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.cli.command.*

internal fun appServerCommandGroup(): LocalCommandFamily {
    val actions = listOf(AppServerLeaf("status", CliProductCommand.APP_SERVER_STATUS, AppServerAction.Status))
    val claim = ControlLeaf("claim", CliProductCommand.APP_SERVER_CLAIM, ControlOperation.CLAIM)
    val release = ControlLeaf("release", CliProductCommand.APP_SERVER_RELEASE, ControlOperation.RELEASE)
    val control = KastCommandGroup("control", "Manage the controller of an attached task.").subcommands(claim, release)
    val root =
        KastCommandGroup(
                "app-server",
                """
                Manage the persistent Kast App Server.

                           Diagnostics: the active installation writes service output to
                           state/broker/<installation-id>/service.log and the complete resolved launch
                           settings to state/broker/<installation-id>/launch-environment. Set
                           KAST_DEBUG=1 to also stream bounded
                           launch diagnostics to the calling process on stderr.
                """
                    .trimIndent(),
            )
            .subcommands(actions + control)
    return LocalCommandFamily(root, actions + claim + release)
}

private class AppServerLeaf(name: String, command: CliProductCommand, private val action: AppServerAction) :
    LocalKastCommand(name, command) {
    override fun help(context: Context) = command.usage

    override fun resolveAction() = CliActionResolution.Selected(CliAction.Local.AppServer(action))
}

private class ControlLeaf(name: String, command: CliProductCommand, private val operation: ControlOperation) :
    LocalKastCommand(name, command) {
    private val threadId by argument("thread-id")
    private val connectionId by argument("connection-id")

    override fun help(context: Context) = command.usage

    override fun resolveAction(): CliActionResolution =
        when (val admission = AppServerAction.Control.admit(operation, threadId, connectionId)) {
            is AppServerControlAdmission.Admitted ->
                CliActionResolution.Selected(CliAction.Local.AppServer(admission.action))
            is AppServerControlAdmission.Rejected ->
                throw com.github.ajalt.clikt.core.UsageError(admission.failure.name.lowercase().replace('_', '-'))
        }
}
