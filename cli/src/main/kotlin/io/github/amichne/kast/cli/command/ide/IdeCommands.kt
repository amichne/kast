package io.github.amichne.kast.cli.command.ide

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import io.github.amichne.kast.appserver.ide.ExistingIdeDocuments
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.cli.ide.*
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

sealed interface ExistingIdeRootSelection {
    data object CurrentDirectory : ExistingIdeRootSelection

    data class Explicit(val path: Path) : ExistingIdeRootSelection
}

internal fun ideCommandGroup(): LocalCommandFamily {
    val commands =
        listOf(
            IdeStatusCommand(CliProductCommand.IDE_STATUS),
            IdeRefreshCommand(CliProductCommand.IDE_REFRESH),
        )
    return LocalCommandFamily(
        KastCommandGroup(
                "ide",
                "Inspect or refresh the existing IDEA endpoint; missing IDE state remains unavailable.",
            )
            .subcommands(commands),
        commands,
    )
}

private abstract class IdeCommand(name: String, command: CliProductCommand) : LocalKastCommand(name, command) {
    private val root by
        option("--root", help = "Existing workspace root; defaults to the current directory's Gradle root.").convert {
            Path.of(it)
        }

    protected fun action(operation: ExistingIdeOperation) =
        CliActionResolution.Selected(
            CliAction.Local.ExistingIde(
                operation,
                root?.let(ExistingIdeRootSelection::Explicit) ?: ExistingIdeRootSelection.CurrentDirectory,
            )
        )
}

private class IdeStatusCommand(command: CliProductCommand) : IdeCommand("status", command) {
    override fun help(context: Context) = "Describe the existing IDEA endpoint without starting a runtime."

    override fun resolveAction() = action(ExistingIdeOperation.Status)
}

private class IdeRefreshCommand(command: CliProductCommand) : IdeCommand("refresh", command) {
    private val request by
        argument("DOCUMENT", help = "Typed refresh request, status, or configure JSON document.").convert {
            when (val parsed = ExistingIdeDocuments.admitRefreshCommand(it)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    fail("Expected an unambiguous workspace refresh request, status, or configure document")
            }
        }

    override fun help(context: Context) =
        "Explicitly refresh files or reload the linked Gradle model, inspect status, or configure an exact task-success rule."

    override fun resolveAction() = action(ExistingIdeOperation.Refresh(request))
}
