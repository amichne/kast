package io.github.amichne.kast.cli.command

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.PlaintextHelpFormatter
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parsers.CommandLineParser
import io.github.amichne.kast.cli.CliProjectionFailure
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.CliTextDocumentAdmission
import io.github.amichne.kast.cli.command.broker.brokerCommandGroup
import io.github.amichne.kast.cli.command.change.changeCommandGroup
import io.github.amichne.kast.cli.command.codex.codexCommandGroup
import io.github.amichne.kast.cli.command.diagnostic.diagnosticCommandGroup
import io.github.amichne.kast.cli.command.lifecycle.lifecycleCommands
import io.github.amichne.kast.cli.command.product.productCommandGroup
import io.github.amichne.kast.cli.command.query.queryCommandGroup
import io.github.amichne.kast.cli.command.relation.relationCommandGroup
import io.github.amichne.kast.cli.command.source.sourceCommandGroup
import io.github.amichne.kast.cli.command.symbol.symbolCommandGroup
import io.github.amichne.kast.cli.command.traversal.traversalCommandGroup
import io.github.amichne.kast.cli.command.workspace.indexCommandGroup
import io.github.amichne.kast.cli.command.workspace.topologyCommandGroup
import io.github.amichne.kast.cli.projection.CanonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.CanonicalOperationDefinitions
import io.github.amichne.kast.protocol.registry.HostedExposure
import io.github.amichne.kast.protocol.registry.OperationDefinition

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
    data class Parsed(val action: CliAction) : CliCommandParsing

    data class Help(val document: CliTextDocument) : CliCommandParsing

    data class Rejected(
        val failure: CliCommandFailure,
        val diagnostic: CliTextDocument,
    ) : CliCommandParsing

    data class ProjectionRejected(val failure: CliProjectionFailure) : CliCommandParsing
}

internal class CliSemanticCommandSurface
internal constructor(
    val operation: CanonicalOperation,
    val usage: String,
)

internal class CliCommandSurface
internal constructor(
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
    data class Created(val factory: CliCommandGraphFactory) : CliCommandGraphConstruction

    data class Rejected(val failures: Set<CliCommandGraphFailure>) : CliCommandGraphConstruction
}

/** A proven canonical graph factory that returns fresh Clikt state for each invocation. */
class CliCommandGraphFactory
private constructor(
    private val preparers: CanonicalCliRequestPreparers,
    internal val surface: CliCommandSurface,
) {
    /**
     * Proof transition: `List<String> -> CliCommandParsing`.
     *
     * Establishes one bounded Clikt invocation refined to exactly one typed CLI action, local help, or closed
     * rejection. [CliCommandFailure] and [CliProjectionFailure] are the finite expected failures. Raw argv is extracted
     * only into Clikt at this outer command boundary.
     */
    internal fun parse(
        argv: List<String>,
        requestInput: CliRequestDocumentInput = CliRequestDocumentInput.Absent,
    ): CliCommandParsing {
        val graph = canonicalGraph(preparers, requestInput)
        val admitted =
            when (val admission = CliArgv.admit(argv)) {
                is CliArgvAdmission.Admitted -> admission.argv
                is CliArgvAdmission.Rejected ->
                    return CliCommandParsing.Rejected(
                        admission.failure.commandFailure(),
                        graph.root.argvDiagnostic(admission.failure),
                    )
            }
        if (admitted.isExact(BROKER_SERVE_ARGUMENTS)) {
            return CliCommandParsing.Parsed(CliAction.Local.BrokerServe)
        }
        return graph.parse(admitted)
    }

    companion object {
        /** The same local family and parser, projected before isolated-product bootstrap. */
        internal fun parseExistingIde(argv: List<String>): CliCommandParsing {
            val families =
                listOf(
                    io.github.amichne.kast.cli.command.ide.hostedIndexCommandGroup(),
                    io.github.amichne.kast.cli.command.ide.ideCommandGroup(),
                )
            val graph =
                CliCommandGraph(
                    KastRootCommand().subcommands(families.map { it.root }),
                    emptyList(),
                    families.flatMap { it.commands },
                    emptyList(),
                )
            return when (val admission = CliArgv.admit(argv)) {
                is CliArgvAdmission.Admitted -> graph.parse(admission.argv)
                is CliArgvAdmission.Rejected ->
                    CliCommandParsing.Rejected(
                        admission.failure.commandFailure(),
                        graph.root.argvDiagnostic(admission.failure),
                    )
            }
        }

        /**
         * Proof transition: `CanonicalCliRequestPreparers -> CliCommandGraphConstruction`.
         *
         * Establishes exactly one semantic leaf for every publicly exposed canonical operation and exactly one leaf for
         * every public product-local and lifecycle command. [CliCommandGraphFailure] closes missing and duplicate graph
         * identities. Clikt nodes remain private to this composition boundary.
         */
        internal fun create(preparers: CanonicalCliRequestPreparers): CliCommandGraphConstruction {
            val graph = canonicalGraph(preparers, CliRequestDocumentInput.Absent)
            val failures = graph.failures()
            return if (failures.isEmpty()) {
                CliCommandGraphConstruction.Created(CliCommandGraphFactory(preparers, graph.surface()))
            } else {
                CliCommandGraphConstruction.Rejected(failures)
            }
        }
    }
}

private class CliArgv private constructor(private val tokens: List<String>) {
    fun cliktTokens(): List<String> = tokens

    fun isExact(expected: List<String>): Boolean = tokens == expected

    companion object {
        /** Refines raw argv to a bounded immutable command-selection token sequence. */
        fun admit(raw: List<String>): CliArgvAdmission =
            when {
                raw.size > MAX_CLI_TOKEN_COUNT -> CliArgvAdmission.Rejected(CliArgvFailure.TOO_MANY_TOKENS)
                raw.any(String::isBlank) -> CliArgvAdmission.Rejected(CliArgvFailure.MISSING_OR_BLANK_TOKEN)
                raw.any { it.length > MAX_CLI_TOKEN_LENGTH } -> CliArgvAdmission.Rejected(CliArgvFailure.TOKEN_TOO_LONG)
                else -> CliArgvAdmission.Admitted(CliArgv(raw.toList()))
            }
    }
}

private val BROKER_SERVE_ARGUMENTS = listOf("broker", "serve")

private enum class CliArgvFailure {
    MISSING_OR_BLANK_TOKEN,
    TOKEN_TOO_LONG,
    TOO_MANY_TOKENS,
}

private sealed interface CliArgvAdmission {
    data class Admitted(val argv: CliArgv) : CliArgvAdmission

    data class Rejected(val failure: CliArgvFailure) : CliArgvAdmission
}

private fun CliArgvFailure.commandFailure(): CliCommandFailure =
    when (this) {
        CliArgvFailure.MISSING_OR_BLANK_TOKEN -> CliCommandFailure.MISSING_OR_BLANK_ARGUMENT
        CliArgvFailure.TOKEN_TOO_LONG -> CliCommandFailure.ARGUMENT_TOO_LONG
        CliArgvFailure.TOO_MANY_TOKENS -> CliCommandFailure.TOO_MANY_ARGUMENTS
    }

private fun KastCommand.argvDiagnostic(failure: CliArgvFailure): CliTextDocument =
    when (failure) {
        CliArgvFailure.MISSING_OR_BLANK_TOKEN,
        CliArgvFailure.TOKEN_TOO_LONG,
        CliArgvFailure.TOO_MANY_TOKENS -> helpDiagnostic()
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
     * Establishes exactly one typed action, help document, or closed command/projection failure from already-bounded
     * argv. Raw tokens leave [CliArgv] only at this private Clikt boundary.
     */
    fun parse(argv: CliArgv): CliCommandParsing {
        var selection: CliCommandSelection = CliCommandSelection.Empty
        try {
            val parsed = CommandLineParser.parse(root, argv.cliktTokens())
            CommandLineParser.run(parsed.invocation) { command ->
                when (val resolution = command.resolveAction()) {
                    CliNodeResolution.NoAction -> Unit
                    is CliActionResolution.Selected -> {
                        selection =
                            when (selection) {
                                CliCommandSelection.Empty ->
                                    CliCommandSelection.Chosen(CliCommandParsing.Parsed(resolution.action))
                                is CliCommandSelection.Chosen,
                                CliCommandSelection.Ambiguous -> CliCommandSelection.Ambiguous
                            }
                    }
                    is CliActionResolution.UsageRejected ->
                        throw UsageError(resolution.failure.message()).also { it.context = command.currentContext }
                    is CliActionResolution.ProjectionRejected ->
                        selection =
                            when (selection) {
                                CliCommandSelection.Empty ->
                                    CliCommandSelection.Chosen(CliCommandParsing.ProjectionRejected(resolution.failure))
                                is CliCommandSelection.Chosen,
                                CliCommandSelection.Ambiguous -> CliCommandSelection.Ambiguous
                            }
                }
            }
        } catch (local: CliLocalCommandMessage) {
            return CliCommandParsing.Parsed(CliAction.Local.Metadata(local.command))
        } catch (completion: com.github.ajalt.clikt.core.PrintCompletionMessage) {
            return CliCommandParsing.Help(completion.message.orEmpty().renderedHelpDocument())
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
            CliCommandSelection.Empty ->
                CliCommandParsing.Rejected(
                    CliCommandFailure.COMMAND_INCOMPLETE,
                    root.helpDiagnostic(),
                )
            is CliCommandSelection.Chosen -> completed.parsing
            CliCommandSelection.Ambiguous ->
                CliCommandParsing.Rejected(
                    CliCommandFailure.COMMAND_GRAPH_AMBIGUOUS,
                    root.helpDiagnostic(),
                )
        }
    }

    fun failures(): Set<CliCommandGraphFailure> = buildSet {
        val semanticCounts = semantic.groupingBy(SemanticKastCommand<*>::operation).eachCount()
        io.github.amichne.kast.protocol.registry.HostedOperationProjection.publicDefinitions
            .map { it.operation }
            .forEach { operation ->
                when (semanticCounts[operation] ?: 0) {
                    0 -> add(CliCommandGraphFailure.MissingOperation(operation))
                    1 -> Unit
                    else -> add(CliCommandGraphFailure.DuplicateOperation(operation))
                }
            }
        val localCounts = local.groupingBy(LocalKastCommand::command).eachCount()
        CliProductCommand.entries
            .filter { it.exposure == CliLocalExposure.PUBLIC }
            .forEach { command ->
                when (localCounts[command] ?: 0) {
                    0 -> add(CliCommandGraphFailure.MissingLocal(command))
                    1 -> Unit
                    else -> add(CliCommandGraphFailure.DuplicateLocal(command))
                }
            }
        val lifecycleCounts = lifecycle.groupingBy(LifecycleKastCommand::command).eachCount()
        CliLifecycleCommand.entries
            .filter { it.exposure == CliLocalExposure.PUBLIC }
            .forEach { command ->
                when (lifecycleCounts[command] ?: 0) {
                    0 -> add(CliCommandGraphFailure.MissingLifecycle(command))
                    1 -> Unit
                    else -> add(CliCommandGraphFailure.DuplicateLifecycle(command))
                }
            }
    }

    fun surface(): CliCommandSurface =
        CliCommandSurface(
            localFlags = listOf("--help", "--version", "--schema"),
            localCommands = local.map(LocalKastCommand::command),
            lifecycleCommands = lifecycle.map(LifecycleKastCommand::command),
            semanticCommands =
                semantic.map { command ->
                    CliSemanticCommandSurface(command.operation, command.schemaUsage)
                },
        )
}

internal abstract class LifecycleKastCommand(
    name: String,
    val command: CliLifecycleCommand,
) : KastCommand(name)

private class KastRootCommand : KastCommand("kast") {
    override val invokeWithoutSubcommand: Boolean = true
    override val printHelpOnEmptyArgs: Boolean = false

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
        "Query the existing IDEA index with index commands; inspect and change a workspace through the isolated sidecar."

    override fun helpEpilog(context: Context): String =
        "Semantic results are one JSON document on stdout. Diagnostics are one JSON document on stderr."

    override fun resolveAction(): CliNodeResolution =
        if (currentContext.invokedSubcommand == null) {
            CliActionResolution.Selected(CliAction.Local.Inspect)
        } else CliNodeResolution.NoAction
}

private class CliLocalCommandMessage(val command: CliLocalMetadataCommand) : PrintMessage(command.name.lowercase())

private sealed interface CliCommandSelection {
    data object Empty : CliCommandSelection

    data class Chosen(val parsing: CliCommandParsing) : CliCommandSelection

    data object Ambiguous : CliCommandSelection
}

private fun canonicalGraph(
    preparers: CanonicalCliRequestPreparers,
    requestInput: CliRequestDocumentInput,
): CliCommandGraph {
    val product = productCommandGroup()
    val appServer = io.github.amichne.kast.cli.command.appserver.appServerCommandGroup()
    val broker = brokerCommandGroup()
    val codex = codexCommandGroup()
    val ide = io.github.amichne.kast.cli.command.ide.ideCommandGroup()
    val hostedIndex = io.github.amichne.kast.cli.command.ide.hostedIndexCommandGroup()
    val index = indexCommandGroup(preparers, requestInput)
    val topology = topologyCommandGroup(preparers, requestInput)
    val symbol = symbolCommandGroup(preparers, requestInput)
    val source = sourceCommandGroup(preparers, requestInput)
    val relation = relationCommandGroup(preparers, requestInput)
    val traversal = traversalCommandGroup(preparers, requestInput)
    val query = queryCommandGroup(preparers, requestInput)
    val diagnostic = diagnosticCommandGroup(preparers, requestInput)
    val change = changeCommandGroup(preparers, requestInput)
    val lifecycle = lifecycleCommands().filter { it.command.exposure == CliLocalExposure.PUBLIC }
    val families =
        listOf(index, topology, query, symbol, source, relation, traversal, diagnostic, change).map {
            it.projectPublicDefinitions(CanonicalOperationDefinitions.all)
        }
    val semantic = families.flatMap(CommandFamily::semanticCommands)
    val localFamilies =
        listOf(product, broker, codex, hostedIndex, ide)
            .map { family ->
                val commands = family.commands.filter { it.command.exposure == CliLocalExposure.PUBLIC }
                val root =
                    when (val candidate = family.root) {
                        is LocalKastCommand ->
                            if (candidate in commands) {
                                candidate
                            } else {
                                ProjectedCommandGroup(candidate).subcommands(commands)
                            }
                        else -> ProjectedCommandGroup(candidate).subcommands(commands)
                    }
                LocalCommandFamily(root, commands)
            }
            .filter { it.commands.isNotEmpty() }
    val root =
        KastRootCommand()
            .subcommands(
                families.filter { it.semanticCommands.isNotEmpty() }.map { it.root } +
                    localFamilies.map { it.root } +
                    appServer.root +
                    lifecycle
            )
    return CliCommandGraph(root, semantic, localFamilies.flatMap { it.commands } + appServer.commands, lifecycle)
}

internal class CommandFamily(
    val root: KastCommandGroup,
    val semanticCommands: List<SemanticKastCommand<*>>,
)

/** Registers only publicly exposed semantic leaves; internal leaves are absent from the command tree. */
internal fun CommandFamily.projectPublicDefinitions(
    definitions: List<OperationDefinition<*, *, *, *, *>>
): CommandFamily {
    val publicOperations =
        definitions.filter { it.hostedExposure == HostedExposure.PUBLIC }.mapTo(linkedSetOf()) { it.operation }
    val commands = semanticCommands.filter { it.operation in publicOperations }
    return CommandFamily(ProjectedCommandGroup(root).subcommands(commands), commands)
}

/** Rebuilds a plain family group before registration, retaining its name and help description. */
private class ProjectedCommandGroup(private val source: KastCommand) : KastCommandGroup(source.commandName, "") {
    override fun help(context: Context): String = source.help(context)
}

private fun KastCommand.formatted(failure: CliktError): CliTextDocument =
    (getFormattedHelp(failure) ?: "").renderedHelpDocument()

private fun KastCommand.helpDiagnostic(): CliTextDocument = (getFormattedHelp() ?: "").renderedHelpDocument()

/**
 * Proof transition: `String -> CliTextDocument` at the Clikt rendering boundary.
 *
 * Establishes non-blank diagnostic text. Blank renderer output deterministically selects the trusted command-rejection
 * document, so this outer adapter has no remaining expected failure.
 */
private fun String.renderedHelpDocument(): CliTextDocument =
    when (val admission = CliTextDocument.admit(this)) {
        is CliTextDocumentAdmission.Admitted -> admission.document
        is CliTextDocumentAdmission.Rejected -> CliTextDocument.commandRejected
    }
