package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation

private const val MAX_CLI_ARGUMENT_LENGTH = 4_096
private const val MAX_CLI_ARGUMENT_COUNT = 64

enum class CliArgumentFailure {
    BLANK,
    TOO_LONG,
}

/** One bounded, non-blank argument admitted at the command-line boundary. */
@JvmInline
value class CliArgument private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<CliArgument, CliArgumentFailure>`.
         *
         * Establishes that the argument is non-blank and bounded. [CliArgumentFailure] is the
         * closed expected failure. Raw text may leave [CliArgument] only at an operation-specific
         * request parser.
         */
        fun parse(raw: String): Refinement<CliArgument, CliArgumentFailure> = when {
            raw.isBlank() -> Refinement.Rejected(CliArgumentFailure.BLANK)
            raw.length > MAX_CLI_ARGUMENT_LENGTH ->
                Refinement.Rejected(CliArgumentFailure.TOO_LONG)
            else -> Refinement.Refined(CliArgument(raw))
        }
    }
}

/** A bounded argument sequence whose members have all been refined. */
class CliArguments internal constructor(
    val values: List<CliArgument>,
)

/** One canonical public operation selected by command syntax. */
class CliInvocation internal constructor(
    val operation: CanonicalOperation,
    val arguments: CliArguments,
)

enum class CliLocalCommand { HELP, VERSION, SCHEMA }

/** Process-local operator actions that do not extend the semantic wire protocol. */
enum class CliLifecycleCommand(
    val command: String,
) {
    START("start"),
    STOP("stop"),
    STATUS("status"),
    CLEAN("clean"),
    REINDEX("reindex"),
}

class CliCommandSyntax internal constructor(
    val operation: CanonicalOperation,
    val command: List<String>,
    val usage: String,
)

internal val canonicalCliSyntaxes = listOf(
    CliCommandSyntax(CanonicalOperation.WORKSPACE_INSPECT, listOf("workspace", "inspect"), "workspace inspect"),
    CliCommandSyntax(
        CanonicalOperation.SYMBOL_DISCOVER,
        listOf("symbol", "discover"),
        "symbol discover --mode <name|location|structure|text> ... --limit <1..1000>"
    ),
    CliCommandSyntax(
        CanonicalOperation.SYMBOL_RESOLVE,
        listOf("symbol", "resolve"),
        "symbol resolve --candidate <candidate-selector>"
    ),
    CliCommandSyntax(
        CanonicalOperation.SYMBOL_DESCRIBE,
        listOf("symbol", "describe"),
        "symbol describe --selector <exact-selector>"
    ),
    CliCommandSyntax(
        CanonicalOperation.RELATION_READ,
        listOf("relation", "read"),
        "relation read --selector <exact-selector> --relation <kind> --limit <1..1000>"
    ),
    CliCommandSyntax(
        CanonicalOperation.TRAVERSAL_RUN,
        listOf("traversal", "run"),
        "traversal run --selector <exact-selector> --relation <kind> --maximum-depth <1..1000> --maximum-results <1..1000>"
    ),
    CliCommandSyntax(
        CanonicalOperation.DIAGNOSTIC_CHECK,
        listOf("diagnostic", "check"),
        "diagnostic check --scope <scope> --limit <1..1000>"
    ),
    CliCommandSyntax(
        CanonicalOperation.CHANGE_PLAN,
        listOf("change", "plan"),
        "change plan --intent <add-file|add-declaration|replace-declaration|rename-symbol> <intent-options>"
    ),
    CliCommandSyntax(CanonicalOperation.CHANGE_APPLY, listOf("change", "apply"), "change apply --plan <plan-identity>"),
    CliCommandSyntax(
        CanonicalOperation.CHANGE_VERIFY,
        listOf("change", "verify"),
        "change verify --application <application-identity>"
    ),
    CliCommandSyntax(
        CanonicalOperation.CHANGE_RECOVER,
        listOf("change", "recover"),
        "change recover --plan <plan-identity>"
    ),
)

sealed interface CliCommandParsing {
    data class Local(val command: CliLocalCommand) : CliCommandParsing

    data class Lifecycle(val command: CliLifecycleCommand) : CliCommandParsing

    data class Parsed(
        val invocation: CliInvocation,
    ) : CliCommandParsing

    data class Rejected(
        val failure: CliCommandFailure,
    ) : CliCommandParsing
}

sealed interface CliCommandFailure {
    data object MissingCommand : CliCommandFailure
    data object UnknownCommand : CliCommandFailure
    data object TooManyArguments : CliCommandFailure

    data class InvalidArgument(
        val failure: CliArgumentFailure,
    ) : CliCommandFailure
}

/** Sole admission boundary for the public command surface. */
object CliCommandParser {
    private val operationByCommand = canonicalCliSyntaxes.associate { it.command to it.operation }
    private val localCommands = mapOf(
        "--help" to CliLocalCommand.HELP,
        "--version" to CliLocalCommand.VERSION,
        "--schema" to CliLocalCommand.SCHEMA,
    )
    private val lifecycleCommands = CliLifecycleCommand.entries.associateBy { it.command }

    /**
     * Proof transition: `List<String> -> CliCommandParsing`.
     *
     * Establishes exactly one local metadata command, one exact-root lifecycle command, or one of
     * the eleven canonical operation identities with a bounded refined argument sequence.
     * [CliCommandFailure] is the closed expected failure. Raw argv extraction is permitted only
     * here.
     */
    fun parse(argv: List<String>): CliCommandParsing {
        if (argv.isEmpty()) return CliCommandParsing.Rejected(CliCommandFailure.MissingCommand)
        if (argv.size == 1) {
            localCommands[argv.single()]?.let { return CliCommandParsing.Local(it) }
            lifecycleCommands[argv.single()]?.let { return CliCommandParsing.Lifecycle(it) }
        }
        val operation = operationByCommand[argv.take(2)]
                        ?: return CliCommandParsing.Rejected(CliCommandFailure.UnknownCommand)
        val rawArguments = argv.drop(2)
        if (rawArguments.size > MAX_CLI_ARGUMENT_COUNT) {
            return CliCommandParsing.Rejected(CliCommandFailure.TooManyArguments)
        }
        val arguments = ArrayList<CliArgument>(rawArguments.size)
        rawArguments.forEach { raw ->
            when (val refinement = CliArgument.parse(raw)) {
                is Refinement.Refined -> arguments.add(refinement.value)
                is Refinement.Rejected -> return CliCommandParsing.Rejected(
                    CliCommandFailure.InvalidArgument(refinement.failure),
                )
            }
        }
        return CliCommandParsing.Parsed(
            CliInvocation(operation, CliArguments(arguments.toList())),
        )
    }
}
