package io.github.amichne.kast.cli.command.ide

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.completion.CompletionGenerator
import com.github.ajalt.clikt.core.PrintCompletionMessage
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.cli.ide.*
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

sealed interface ExistingIdeRootSelection {
    data object CurrentDirectory : ExistingIdeRootSelection
    data class Explicit(val path: Path) : ExistingIdeRootSelection
}

internal fun ideCommandGroup(): LocalCommandFamily {
    val commands = listOf(IdeStatusCommand(), IdeClassesCommand(), IdeCompletionCommand())
    return LocalCommandFamily(KastCommandGroup("ide", "Query indexes in the existing IDEA project. Missing IDE state remains unavailable.").subcommands(commands), commands)
}

private class IdeCompletionCommand : LocalKastCommand("generate-completion", CliProductCommand.IDE_COMPLETION) {
    private val shell by argument("SHELL").choice("bash", "zsh", "fish")
    override fun help(context: Context) = "Generate shell completion for the IDEA command path without connecting."
    override fun resolveAction(): CliActionResolution {
        val root = generateSequence(currentContext) { it.parent }.last().command
        throw PrintCompletionMessage(CompletionGenerator.generateCompletionForCommand(root, shell))
    }
}

private abstract class IdeCommand(name: String, command: CliProductCommand) : LocalKastCommand(name, command) {
    private val root by option("--root", help = "Existing workspace root; defaults to the current directory's Gradle root.").convert { Path.of(it) }
    protected fun action(operation: ExistingIdeOperation) = CliActionResolution.Selected(CliAction.Local.ExistingIde(
        operation, root?.let(ExistingIdeRootSelection::Explicit) ?: ExistingIdeRootSelection.CurrentDirectory,
    ))
}

private class IdeStatusCommand : IdeCommand("status", CliProductCommand.IDE_STATUS) {
    override fun help(context: Context) = "Describe the existing IDEA endpoint without starting a runtime."
    override fun resolveAction() = action(ExistingIdeOperation.Status)
}

private class IdeClassesCommand : IdeCommand("classes", CliProductCommand.IDE_CLASSES) {
    private val name by argument("NAME", help = "Exact Kotlin class short name.").convert {
        when (val parsed = ExistingIdeClassName.parse(it)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> fail("Expected a Kotlin class short name of at most 512 UTF-8 bytes")
        }
    }
    override fun help(context: Context) = "Find up to 32 authored Kotlin classes through IDEA's existing index and K2."
    override fun resolveAction() = action(ExistingIdeOperation.Classes(name))
}
