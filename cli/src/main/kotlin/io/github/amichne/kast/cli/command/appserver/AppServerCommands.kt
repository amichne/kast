package io.github.amichne.kast.cli.command.appserver

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.amichne.kast.appserver.*
import io.github.amichne.kast.cli.command.*

internal fun appServerCommandGroup(): LocalCommandFamily {
    val actions = listOf(
        AppServerLeaf("register",CliProductCommand.APP_SERVER_REGISTER,AppServerAction.Register),
        AppServerLeaf("enable",CliProductCommand.APP_SERVER_ENABLE,AppServerAction.Enable),
        AppServerLeaf("status",CliProductCommand.APP_SERVER_STATUS,AppServerAction.Status),
        AppServerLeaf("stop",CliProductCommand.APP_SERVER_STOP,AppServerAction.Stop),
        AppServerLeaf("disable",CliProductCommand.APP_SERVER_DISABLE,AppServerAction.Disable),
    )
    val claim = ControlLeaf("claim",CliProductCommand.APP_SERVER_CLAIM,ControlOperation.CLAIM)
    val release = ControlLeaf("release",CliProductCommand.APP_SERVER_RELEASE,ControlOperation.RELEASE)
    val control = KastCommandGroup("control","Manage the controller of an attached task.").subcommands(claim,release)
    val root = KastCommandGroup("app-server","Manage the persistent Kast App Server.").subcommands(actions + control)
    return LocalCommandFamily(root,actions + claim + release)
}
private class AppServerLeaf(name: String,command: CliProductCommand,private val action: AppServerAction): LocalKastCommand(name,command) {
    override fun help(context: Context) = command.usage
    override fun resolveAction() = CliActionResolution.Selected(CliAction.Local.AppServer(action))
}
private class ControlLeaf(name: String,command: CliProductCommand,private val operation: ControlOperation): LocalKastCommand(name,command) {
    private val threadId by argument("thread-id")
    private val connectionId by argument("connection-id")
    override fun help(context: Context) = command.usage
    override fun resolveAction(): CliActionResolution = when (val admission = AppServerAction.Control.admit(operation,threadId,connectionId)) {
        is AppServerControlAdmission.Admitted -> CliActionResolution.Selected(CliAction.Local.AppServer(admission.action))
        is AppServerControlAdmission.Rejected -> throw com.github.ajalt.clikt.core.UsageError(admission.failure.name.lowercase().replace('_','-'))
    }
}
