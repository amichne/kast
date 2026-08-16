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

sealed interface CliCommandParsing {
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
    private val operationByCommand = mapOf(
        listOf("workspace", "inspect") to CanonicalOperation.WORKSPACE_INSPECT,
        listOf("symbol", "discover") to CanonicalOperation.SYMBOL_DISCOVER,
        listOf("symbol", "resolve") to CanonicalOperation.SYMBOL_RESOLVE,
        listOf("symbol", "describe") to CanonicalOperation.SYMBOL_DESCRIBE,
        listOf("relation", "read") to CanonicalOperation.RELATION_READ,
        listOf("traversal", "run") to CanonicalOperation.TRAVERSAL_RUN,
        listOf("diagnostic", "check") to CanonicalOperation.DIAGNOSTIC_CHECK,
        listOf("change", "plan") to CanonicalOperation.CHANGE_PLAN,
        listOf("change", "apply") to CanonicalOperation.CHANGE_APPLY,
        listOf("change", "verify") to CanonicalOperation.CHANGE_VERIFY,
        listOf("change", "recover") to CanonicalOperation.CHANGE_RECOVER,
    )

    /**
     * Proof transition: `List<String> -> CliCommandParsing`.
     *
     * Establishes exactly one of the eleven canonical operation identities and a bounded refined
     * argument sequence. [CliCommandFailure] is the closed expected failure. Raw argv extraction
     * is permitted only here.
     */
    fun parse(argv: List<String>): CliCommandParsing {
        if (argv.isEmpty()) return CliCommandParsing.Rejected(CliCommandFailure.MissingCommand)
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
