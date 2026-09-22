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

internal fun ideCommandGroup(): LocalCommandFamily {
    val commands =
        listOf(
            IdeStatusCommand(CliProductCommand.IDE_STATUS),
            IdeRefreshCommand(CliProductCommand.IDE_REFRESH),
            IdeClassesCommand(CliProductCommand.IDE_CLASSES),
            IdeSupertypeCommand(CliProductCommand.IDE_SUPERTYPE),
            IdeCompletionCommand(CliProductCommand.IDE_COMPLETION),
            IdeTrustBrokerCommand(),
        )
    return LocalCommandFamily(
        KastCommandGroup(
                "ide",
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

private class IdeTrustBrokerCommand : LocalKastCommand("trust-broker", CliProductCommand.IDE_TRUST_BROKER) {
    override fun help(context: Context) = "Explicitly enroll this user's broker approval key for hosted changes."

    override fun resolveAction() = CliActionResolution.Selected(CliAction.Local.TrustBroker)
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
