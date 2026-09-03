package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parsers.CommandLineParser
import com.github.ajalt.clikt.output.PlaintextHelpFormatter
import io.github.amichne.kast.cli.CliProjectionFailure
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.CliTextDocumentAdmission
import io.github.amichne.kast.cli.command.change.changeCommandGroup
import io.github.amichne.kast.cli.command.broker.brokerCommandGroup
import io.github.amichne.kast.cli.command.diagnostic.diagnosticCommandGroup
import io.github.amichne.kast.cli.command.lifecycle.lifecycleCommands
import io.github.amichne.kast.cli.command.product.productCommandGroup
import io.github.amichne.kast.cli.command.relation.relationCommandGroup
import io.github.amichne.kast.cli.command.source.sourceCommandGroup
import io.github.amichne.kast.cli.command.symbol.symbolCommandGroup
import io.github.amichne.kast.cli.command.traversal.traversalCommandGroup
import io.github.amichne.kast.cli.command.workspace.workspaceCommandGroup
import io.github.amichne.kast.cli.command.workspace.indexCommandGroup
import io.github.amichne.kast.cli.command.workspace.topologyCommandGroup
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation

private const val MAX_CLI_TOKEN_LENGTH = 4_096
private const val MAX_CLI_TOKEN_COUNT = 66

internal enum class CliCommandFailure {
    MISSING_OR_BLANK_ARGUMENT,
    ARGUMENT_TOO_LONG,
    TOO_MANY_ARGUMENTS,
    ARGUMENTS_REJECTED,
    COMMAND_INCOMPLETE,
    COMMAND_GRAPH_AMBIGUOUS,
    PARSER_REJECTED,
}

internal fun CliCommandFailure.outputReason(): String = name.lowercase().replace('_', '-')

internal sealed interface CliCommandParsing {
    data class Parsed(
        val action: CliAction,
    ) : CliCommandParsing

    data class Help(
        val document: CliTextDocument,
    ) : CliCommandParsing

    data class Rejected(
        val failure: CliCommandFailure,
        val diagnostic: CliTextDocument,
    ) : CliCommandParsing

    data class ProjectionRejected(
        val failure: CliProjectionFailure,
    ) : CliCommandParsing
}

internal class CliSemanticCommandSurface internal constructor(
    val operation: CanonicalOperation,
    val usage: String,
)

internal class CliCommandSurface internal constructor(
    val localFlags: List<String>,
    val localCommands: List<CliProductCommand>,
    val lifecycleCommands: List<CliLifecycleCommand>,
    val semanticCommands: List<CliSemanticCommandSurface>,
)

internal sealed interface CliCommandGraphFailure {
    data class MissingOperation(val operation: CanonicalOperation) : CliCommandGraphFailure
    data class DuplicateOperation(val operation: CanonicalOperation) : CliCommandGraphFailure
    data class MissingLocal(val command: CliProductCommand) : CliCommandGraphFailure
    data class DuplicateLocal(val command: CliProductCommand) : CliCommandGraphFailure
    data class MissingLifecycle(val command: CliLifecycleCommand) : CliCommandGraphFailure
    data class DuplicateLifecycle(val command: CliLifecycleCommand) : CliCommandGraphFailure
}

internal sealed interface CliCommandGraphConstruction {
    data class Created(
        val factory: CliCommandGraphFactory,
    ) : CliCommandGraphConstruction

    data class Rejected(
        val failures: Set<CliCommandGraphFailure>,
    ) : CliCommandGraphConstruction
}

/** A proven canonical graph factory that returns fresh Clikt state for each invocation. */
class CliCommandGraphFactory private constructor(
    private val preparers: CanonicalCliRequestPreparers,
    internal val surface: CliCommandSurface,
) {
    /**
     * Proof transition: `List<String> -> CliCommandParsing`.
     *
     * Establishes one bounded Clikt invocation refined to exactly one typed CLI action, local help,
     * or closed rejection. [CliCommandFailure] and [CliProjectionFailure] are the finite expected
     * failures. Raw argv is extracted only into Clikt at this outer command boundary.
     */
    internal fun parse(argv: List<String>): CliCommandParsing {
        val graph = canonicalGraph(preparers)
        val admitted = when (val admission = CliArgv.admit(argv)) {
            is CliArgvAdmission.Admitted -> admission.argv
            is CliArgvAdmission.Rejected -> return CliCommandParsing.Rejected(
                admission.failure.commandFailure(),
                graph.root.helpDiagnostic(),
            )
        }
        return graph.parse(admitted)
    }

    companion object {
        /**
         * Proof transition: `CanonicalCliRequestPreparers -> CliCommandGraphConstruction`.
         *
         * Establishes exactly one semantic leaf for every canonical operation and exactly one leaf
         * for every product-local and lifecycle command. [CliCommandGraphFailure] closes missing
         * and duplicate graph identities. Clikt nodes remain private to this composition boundary.
         */
        internal fun create(preparers: CanonicalCliRequestPreparers): CliCommandGraphConstruction {
            val graph = canonicalGraph(preparers)
            val failures = graph.failures()
            return if (failures.isEmpty()) {
                CliCommandGraphConstruction.Created(
                    CliCommandGraphFactory(preparers, graph.surface()),
                )
            } else {
                CliCommandGraphConstruction.Rejected(failures)
            }
        }
    }
}

private class CliArgv private constructor(
    private val tokens: List<String>,
) {
    fun cliktTokens(): List<String> = tokens

    companion object {
        /**
         * Proof transition: `List<String> -> CliArgvAdmission`.
         *
         * Establishes a bounded sequence of non-blank argv tokens. [CliArgvFailure] is the closed
         * expected failure. Raw strings may leave only at the Clikt parser boundary.
         */
        fun admit(raw: List<String>): CliArgvAdmission = when {
            raw.size > MAX_CLI_TOKEN_COUNT ->
                CliArgvAdmission.Rejected(CliArgvFailure.TOO_MANY_TOKENS)
            raw.any(String::isBlank) ->
                CliArgvAdmission.Rejected(CliArgvFailure.MISSING_OR_BLANK_TOKEN)
            raw.any { it.length > MAX_CLI_TOKEN_LENGTH } ->
                CliArgvAdmission.Rejected(CliArgvFailure.TOKEN_TOO_LONG)
            else -> CliArgvAdmission.Admitted(CliArgv(raw.toList()))
        }
    }
}

private enum class CliArgvFailure {
    MISSING_OR_BLANK_TOKEN,
    TOKEN_TOO_LONG,
    TOO_MANY_TOKENS,
}

private sealed interface CliArgvAdmission {
    data class Admitted(val argv: CliArgv) : CliArgvAdmission
    data class Rejected(val failure: CliArgvFailure) : CliArgvAdmission
}

private fun CliArgvFailure.commandFailure(): CliCommandFailure = when (this) {
    CliArgvFailure.MISSING_OR_BLANK_TOKEN -> CliCommandFailure.MISSING_OR_BLANK_ARGUMENT
    CliArgvFailure.TOKEN_TOO_LONG -> CliCommandFailure.ARGUMENT_TOO_LONG
    CliArgvFailure.TOO_MANY_TOKENS -> CliCommandFailure.TOO_MANY_ARGUMENTS
}

private class CliCommandGraph(
    val root: KastCommand,
    private val semantic: List<SemanticKastCommand<*>>,
    private val local: List<LocalKastCommand>,
    private val lifecycle: List<LifecycleKastCommand>,
) {
    /**
     * Proof transition: `CliArgv -> CliCommandParsing`.
     *
     * Establishes exactly one typed action, help document, or closed command/projection failure
     * from already-bounded argv. Raw tokens leave [CliArgv] only at this private Clikt boundary.
     */
    fun parse(argv: CliArgv): CliCommandParsing {
        var selection: CliCommandSelection = CliCommandSelection.Empty
        try {
            val parsed = CommandLineParser.parse(root, argv.cliktTokens())
            CommandLineParser.run(parsed.invocation) { command ->
                when (val resolution = command.resolveAction()) {
                    CliNodeResolution.NoAction -> Unit
                    is CliActionResolution.Selected -> {
                        selection = when (selection) {
                            CliCommandSelection.Empty -> CliCommandSelection.Chosen(
                                CliCommandParsing.Parsed(resolution.action),
                            )
                            is CliCommandSelection.Chosen, CliCommandSelection.Ambiguous ->
                                CliCommandSelection.Ambiguous
                        }
                    }
                    is CliActionResolution.UsageRejected -> throw UsageError(
                        resolution.failure.message(),
                    ).also { it.context = command.currentContext }
                    is CliActionResolution.ProjectionRejected ->
                        selection = when (selection) {
                            CliCommandSelection.Empty -> CliCommandSelection.Chosen(
                                CliCommandParsing.ProjectionRejected(resolution.failure),
                            )
                            is CliCommandSelection.Chosen, CliCommandSelection.Ambiguous ->
                                CliCommandSelection.Ambiguous
                        }
                }
            }
        } catch (local: CliLocalCommandMessage) {
            return CliCommandParsing.Parsed(CliAction.Local.Metadata(local.command))
        } catch (help: PrintHelpMessage) {
            val document = root.formatted(help)
            return if (help.error) {
                CliCommandParsing.Rejected(CliCommandFailure.COMMAND_INCOMPLETE, document)
            } else {
                CliCommandParsing.Help(document)
            }
        } catch (usage: UsageError) {
            return CliCommandParsing.Rejected(
                CliCommandFailure.ARGUMENTS_REJECTED,
                root.formatted(usage),
            )
        } catch (failure: CliktError) {
            return CliCommandParsing.Rejected(
                CliCommandFailure.PARSER_REJECTED,
                root.formatted(failure),
            )
        }
        return when (val completed = selection) {
            CliCommandSelection.Empty -> CliCommandParsing.Rejected(
                CliCommandFailure.COMMAND_INCOMPLETE,
                root.helpDiagnostic(),
            )
            is CliCommandSelection.Chosen -> completed.parsing
            CliCommandSelection.Ambiguous -> CliCommandParsing.Rejected(
                CliCommandFailure.COMMAND_GRAPH_AMBIGUOUS,
                root.helpDiagnostic(),
            )
        }
    }

    fun failures(): Set<CliCommandGraphFailure> = buildSet {
        val semanticCounts = semantic.groupingBy(SemanticKastCommand<*>::operation).eachCount()
        CanonicalOperation.entries.forEach { operation ->
            when (semanticCounts[operation] ?: 0) {
                0 -> add(CliCommandGraphFailure.MissingOperation(operation))
                1 -> Unit
                else -> add(CliCommandGraphFailure.DuplicateOperation(operation))
            }
        }
        val localCounts = local.groupingBy(LocalKastCommand::command).eachCount()
        CliProductCommand.entries.forEach { command ->
            when (localCounts[command] ?: 0) {
                0 -> add(CliCommandGraphFailure.MissingLocal(command))
                1 -> Unit
                else -> add(CliCommandGraphFailure.DuplicateLocal(command))
            }
        }
        val lifecycleCounts = lifecycle.groupingBy(LifecycleKastCommand::command).eachCount()
        CliLifecycleCommand.entries.forEach { command ->
            when (lifecycleCounts[command] ?: 0) {
                0 -> add(CliCommandGraphFailure.MissingLifecycle(command))
                1 -> Unit
                else -> add(CliCommandGraphFailure.DuplicateLifecycle(command))
            }
        }
    }

    fun surface(): CliCommandSurface = CliCommandSurface(
        localFlags = listOf("--help", "--version", "--schema"),
        localCommands = local.map(LocalKastCommand::command),
        lifecycleCommands = lifecycle.map(LifecycleKastCommand::command),
        semanticCommands = semantic.map { command ->
            CliSemanticCommandSurface(command.operation, command.schemaUsage)
        },
    )
}

internal abstract class LifecycleKastCommand(
    name: String,
    val command: CliLifecycleCommand,
) : KastCommand(name)

private class KastRootCommand : KastCommand("kast") {
    override val printHelpOnEmptyArgs: Boolean = true

    init {
        configureContext {
            helpFormatter = { context ->
                PlaintextHelpFormatter(
                    context,
                    showDefaultValues = true,
                    showRequiredTag = true,
                )
            }
        }
        eagerOption("--version", help = "Show the installed IntelliJ sidecar product version") {
            throw CliLocalCommandMessage(CliLocalMetadataCommand.VERSION)
        }
        eagerOption("--schema", help = "Print the installed machine-readable schema") {
            throw CliLocalCommandMessage(CliLocalMetadataCommand.SCHEMA)
        }
    }

    override fun help(context: Context): String =
        "Inspect and change one exact Kotlin workspace through an isolated IntelliJ sidecar."

    override fun helpEpilog(context: Context): String =
        "Semantic results are one JSON document on stdout. Diagnostics are one JSON document on stderr."

    override fun resolveAction(): CliNodeResolution = CliNodeResolution.NoAction
}

private class CliLocalCommandMessage(
    val command: CliLocalMetadataCommand,
) : PrintMessage(command.name.lowercase())

private sealed interface CliCommandSelection {
    data object Empty : CliCommandSelection

    data class Chosen(
        val parsing: CliCommandParsing,
    ) : CliCommandSelection

    data object Ambiguous : CliCommandSelection
}

private fun canonicalGraph(preparers: CanonicalCliRequestPreparers): CliCommandGraph {
    val product = productCommandGroup()
    val broker = brokerCommandGroup()
    val workspace = workspaceCommandGroup(preparers)
    val index = indexCommandGroup(preparers)
    val topology = topologyCommandGroup(preparers)
    val symbol = symbolCommandGroup(preparers)
    val source = sourceCommandGroup(preparers)
    val relation = relationCommandGroup(preparers)
    val traversal = traversalCommandGroup(preparers)
    val diagnostic = diagnosticCommandGroup(preparers)
    val change = changeCommandGroup(preparers)
    val lifecycle = lifecycleCommands(preparers)
    val semantic = listOf(
        workspace,
        index,
        topology,
        symbol,
        source,
        relation,
        traversal,
        diagnostic,
        change,
    )
        .flatMap(CommandFamily::semanticCommands)
    val root = KastRootCommand().subcommands(
        listOf(
            product.root,
            broker.root,
            workspace.root,
            index.root,
            topology.root,
            symbol.root,
            source.root,
            relation.root,
            traversal.root,
            diagnostic.root,
            change.root,
        ) + lifecycle
    )
    return CliCommandGraph(root, semantic, product.commands + broker.commands, lifecycle)
}

internal class CommandFamily(
    val root: KastCommand,
    val semanticCommands: List<SemanticKastCommand<*>>,
)

private fun KastCommand.formatted(failure: CliktError): CliTextDocument =
    (getFormattedHelp(failure) ?: "").renderedHelpDocument()

private fun KastCommand.helpDiagnostic(): CliTextDocument =
    (getFormattedHelp() ?: "").renderedHelpDocument()

/**
 * Proof transition: `String -> CliTextDocument` at the Clikt rendering boundary.
 *
 * Establishes non-blank diagnostic text. Blank renderer output deterministically selects the
 * trusted command-rejection document, so this outer adapter has no remaining expected failure.
 */
private fun String.renderedHelpDocument(): CliTextDocument = when (
    val admission = CliTextDocument.admit(this)
) {
    is CliTextDocumentAdmission.Admitted -> admission.document
    is CliTextDocumentAdmission.Rejected -> CliTextDocument.commandRejected
}
