package io.github.amichne.kast.cli.command.ide

import com.github.ajalt.clikt.completion.CompletionGenerator
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintCompletionMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.github.amichne.kast.cli.command.*
import io.github.amichne.kast.cli.ide.*
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

sealed interface ExistingIdeRootSelection {
    data object CurrentDirectory : ExistingIdeRootSelection

    data class Explicit(val path: Path) : ExistingIdeRootSelection
}

internal fun ideCommandGroup(): LocalCommandFamily = hostedCommandGroup(HostedCommandFamily.IDE)

internal fun hostedIndexCommandGroup(): LocalCommandFamily = hostedCommandGroup(HostedCommandFamily.INDEX)

private enum class HostedCommandFamily(
    val group: String,
    val status: CliProductCommand,
    val classes: CliProductCommand,
    val supertype: CliProductCommand,
    val completion: CliProductCommand,
) {
    INDEX(
        "index",
        CliProductCommand.INDEX_STATUS,
        CliProductCommand.INDEX_CLASSES,
        CliProductCommand.INDEX_SUPERTYPE,
        CliProductCommand.INDEX_COMPLETION,
    ),
    IDE(
        "ide",
        CliProductCommand.IDE_STATUS,
        CliProductCommand.IDE_CLASSES,
        CliProductCommand.IDE_SUPERTYPE,
        CliProductCommand.IDE_COMPLETION,
    ),
}

private fun hostedCommandGroup(family: HostedCommandFamily): LocalCommandFamily {
    val commands =
        listOf(
            IdeStatusCommand(family.status),
            IdeClassesCommand(family.classes),
            IdeSupertypeCommand(family.supertype),
            IdeCompletionCommand(family.completion),
        )
    return LocalCommandFamily(
        KastCommandGroup(
                family.group,
                "Read the existing IDEA index. IDEA owns updates; missing IDE state remains unavailable.",
            )
            .subcommands(commands),
        commands,
    )
}

private class IdeCompletionCommand(command: CliProductCommand) : LocalKastCommand("generate-completion", command) {
    private val shell by argument("SHELL").choice("bash", "zsh", "fish")

    override fun help(context: Context) = "Generate shell completion for the IDEA command path without connecting."

    override fun resolveAction(): CliActionResolution {
        val root = generateSequence(currentContext) { it.parent }.last().command
        throw PrintCompletionMessage(CompletionGenerator.generateCompletionForCommand(root, shell))
    }
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

private class IdeClassesCommand(command: CliProductCommand) : IdeCommand("classes", command) {
    private val name by
        argument("NAME", help = "Exact Kotlin class short name.").convert {
            when (val parsed = ExistingIdeClassName.parse(it)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> fail("Expected a Kotlin class short name of at most 512 UTF-8 bytes")
            }
        }

    override fun help(context: Context) = "Find up to 32 authored Kotlin classes through IDEA's existing index and K2."

    override fun resolveAction() = action(ExistingIdeOperation.Classes(name))
}

private class IdeSupertypeCommand(command: CliProductCommand) : IdeCommand("supertype", command) {
    private val name by
        argument("QUALIFIED_NAME", help = "Exact Kotlin class identity, including enclosing classes.").convert {
            when (val parsed = ExistingIdeQualifiedClassName.parse(it)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> fail("Expected a qualified Kotlin class name of at most 4096 UTF-8 bytes")
            }
        }

    override fun help(context: Context) = "Resolve one class's explicit supertype through IDEA's existing index and K2."

    override fun resolveAction() = action(ExistingIdeOperation.Supertype(name))
}
