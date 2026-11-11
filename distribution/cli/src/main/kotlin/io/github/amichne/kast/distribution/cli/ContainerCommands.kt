package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

internal fun containerCommands(): ManagementNode =
    ContainerRootCommand()
        .subcommands(
            ContainerCreateCommand(),
            ContainerResumeCommand(),
            ContainerStopCommand(),
            ContainerRemoveCommand(),
            ContainerRuntimeCommand(),
        )

private class ContainerRootCommand : ManagementNode("container") {
    override val invokeWithoutSubcommand = true
    override val printHelpOnEmptyArgs = false
    private val json by option("--json").flag()

    override fun help(context: Context) = "Manage private workspace Hosts without desktop IntelliJ."

    override fun selection(): ManagementCommand? =
        if (currentContext.invokedSubcommand == null) ManagementCommand.Container(ContainerCommand.List(json)) else null
}

private class ContainerCreateCommand : ManagementNode("create") {
    private val name by option("--name", help = "Unique name; defaults to a workspace-derived name.")
    private val root by option("--root", help = "Trusted workspace; defaults to the current directory.")
    private val gradleHome by option("--gradle-home", help = "Explicit shared Gradle home; the default is private.")
    private val json by option("--json").flag()

    override fun help(context: Context) =
        "Create a container and trust this workspace's build code. Does not start the Host."

    override fun selection() = ManagementCommand.Container(ContainerCommand.Create(name, root, gradleHome, json))
}

private class ContainerResumeCommand : ManagementNode("resume") {
    private val selector by argument("name|id")
    private val json by option("--json").flag()

    override fun help(context: Context) = "Enable and prepare the private workspace; wait for Host readiness."

    override fun selection() = ManagementCommand.Container(ContainerCommand.Resume(selector, json))
}

private class ContainerStopCommand : ManagementNode("stop") {
    private val selector by argument("name|id")
    private val json by option("--json").flag()

    override fun help(context: Context) = "Disable automatic restart and stop only this private Host."

    override fun selection() = ManagementCommand.Container(ContainerCommand.Stop(selector, json))
}

private class ContainerRemoveCommand : ManagementNode("rm") {
    private val id by argument("id")
    private val json by option("--json").flag()

    override fun help(context: Context) =
        "Stop and remove a container by exact id. Preserve workspace, external Gradle home, and shared runtime."

    override fun selection(): ManagementCommand =
        when (val selected = ContainerId.parse(id)) {
            is io.github.amichne.kast.kernel.Refinement.Rejected ->
                throw com.github.ajalt.clikt.core.CliktError(selected.failure.name)
            is io.github.amichne.kast.kernel.Refinement.Refined ->
                ManagementCommand.Container(ContainerCommand.Remove(selected.value, json))
        }
}

private class ContainerRuntimeCommand : ManagementNode("import-runtime") {
    private val archive by option("--archive", help = "Installer-free runtime ZIP.").required()
    private val digest by option("--sha256", help = "Expected archive SHA-256.").required()
    private val json by option("--json").flag()

    override fun help(context: Context) =
        "Verify and import an offline runtime for this Control version and native platform."

    override fun selection() = ManagementCommand.Container(ContainerCommand.ImportRuntime(archive, digest, json))
}
