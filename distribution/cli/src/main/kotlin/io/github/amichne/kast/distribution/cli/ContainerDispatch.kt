package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parsers.CommandLineParser
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class ContainerNamespace : ManagementNode("container") {
    override fun help(context: Context) = "Open a mounted worktree with a self-contained IDEA runtime."

    override fun selection(): ManagementCommand? = null
}

internal sealed interface ContainerCommand {
    data object Status : ContainerCommand

    data class Open(
        val directory: Path,
        val name: ContainerWorkspaceName,
        val image: ContainerImageSelection,
        val network: ContainerNetwork,
        val task: ContainerTask,
    ) : ContainerCommand
}

internal enum class ContainerNetwork(val argument: String) {
    ONLINE("bridge"),
    OFFLINE("none"),
}

internal sealed interface ContainerTask {
    data object Shell : ContainerTask

    class Run private constructor(val executable: String, val arguments: List<String>) : ContainerTask {
        companion object {
            fun parse(arguments: List<String>): Refinement<Run, ContainerFailure> =
                if (arguments.isEmpty() || arguments.first().isBlank() || arguments.any { '\u0000' in it })
                    Refinement.Rejected(ContainerFailure.INVALID_COMMAND)
                else Refinement.Refined(Run(arguments.first(), arguments.drop(1)))
        }
    }
}

internal class ContainerWorkspaceName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ContainerWorkspaceName, ContainerFailure> =
            if (Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}").matches(raw)) Refinement.Refined(ContainerWorkspaceName(raw))
            else Refinement.Rejected(ContainerFailure.INVALID_NAME)
    }
}

internal sealed interface ContainerImageSelection {
    data object Local : ContainerImageSelection

    class Digest private constructor(val reference: String) : ContainerImageSelection {
        companion object {
            fun parse(raw: String): Refinement<Digest, ContainerFailure> =
                if (Regex("(?:[a-z0-9][a-z0-9._:/-]*@)?sha256:[a-f0-9]{64}").matches(raw))
                    Refinement.Refined(Digest(raw))
                else Refinement.Rejected(ContainerFailure.INVALID_IMAGE_REFERENCE)
        }
    }
}

internal fun parseContainerCommand(arguments: List<String>): ManagementParsing {
    val action = arguments.firstOrNull()?.takeUnless { it.startsWith("-") } ?: "shell"
    if (action !in setOf("shell", "run", "status"))
        return ManagementParsing.Print("kast container: select shell, run, or status", true)
    val command = ContainerEntry(action)
    val tail = if (arguments.firstOrNull() == action) arguments.drop(1) else arguments
    return try {
        val parsed = CommandLineParser.parse(command, tail)
        CommandLineParser.run(parsed.invocation) {}
        ManagementParsing.Selected(command.selection())
    } catch (help: PrintHelpMessage) {
        ManagementParsing.Print(command.getFormattedHelp(help).orEmpty(), help.error)
    } catch (failure: CliktError) {
        ManagementParsing.Print(command.getFormattedHelp(failure).orEmpty(), true)
    } catch (_: InvalidPathException) {
        ManagementParsing.Print("kast container: invalid workspace path", true)
    }
}

private class ContainerEntry(private val action: String) : ManagementNode("kast container $action") {
    private val workspace by option("--workspace", help = "Select a Git worktree. Default: current directory.")
    private val name by option("--name", help = "Select private runtime state for this worktree.").default("default")
    private val image by option("--image", help = "Select a local sha256 ID or registry reference with @sha256 digest.")
    private val offline by option("--offline", help = "Disable the workspace network.").flag()
    private val arguments by argument("COMMAND").multiple()

    override fun help(context: Context) =
        "Run in the mounted Git worktree with persistent Gradle and IDEA state. " +
            "Needs Colima on macOS and a prepared Kast v3 image. " +
            "Commands: shell (default), run -- COMMAND [ARGS], status. Host edits are immediate."

    private fun workspaceOptions(): Boolean = workspace != null || name != "default" || image != null

    override fun selection(): ManagementCommand.Container {
        if (action == "status") {
            if (workspaceOptions() || offline || arguments.isNotEmpty())
                throw CliktError("status accepts no workspace options or command")
            return ManagementCommand.Container(ContainerCommand.Status)
        }
        val task =
            if (action == "run") ContainerTask.Run.parse(arguments).orUsageError()
            else {
                if (arguments.isNotEmpty()) throw CliktError("Use run -- COMMAND [ARGS] for a task")
                ContainerTask.Shell
            }
        return ManagementCommand.Container(
            ContainerCommand.Open(
                Path.of(workspace ?: "."),
                ContainerWorkspaceName.parse(name).orUsageError(),
                image?.let { ContainerImageSelection.Digest.parse(it).orUsageError() } ?: ContainerImageSelection.Local,
                if (offline) ContainerNetwork.OFFLINE else ContainerNetwork.ONLINE,
                task,
            )
        )
    }
}

private fun <T> Refinement<T, ContainerFailure>.orUsageError(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw CliktError(failure.guidance)
    }
