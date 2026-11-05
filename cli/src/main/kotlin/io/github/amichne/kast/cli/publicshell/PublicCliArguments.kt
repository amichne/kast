package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal sealed interface PublicCliWorkspace {
    data object Current : PublicCliWorkspace

    data class Selected(val path: String) : PublicCliWorkspace
}

internal sealed interface PublicCliInput {
    data class Lowered(val arguments: JsonObject) : PublicCliInput

    data class File(val path: String) : PublicCliInput

    data object Stdin : PublicCliInput
}

internal sealed interface PublicCliCommand {
    val projection: PublicOutputProjection
        get() = PublicOutputProjection.Unfiltered

    data class Query(
        val catalog: PublicToolCatalog,
        val workspace: PublicCliWorkspace,
        val input: PublicCliInput,
        val identity: PublicToolIdentity = PublicToolIdentity.QUERY_SYMBOLS,
        override val projection: PublicOutputProjection = PublicOutputProjection.Unfiltered,
    ) : PublicCliCommand

    data class Plan(
        val catalog: PublicToolCatalog,
        val workspace: PublicCliWorkspace,
        val input: PublicCliInput,
        override val projection: PublicOutputProjection = PublicOutputProjection.Unfiltered,
    ) : PublicCliCommand

    data class Describe(
        val catalog: PublicToolCatalog,
        val command: PublicCliCommandId?,
        val section: PublicCliDescribeSection,
        val selection: PublicSchemaSelection? = null,
        override val projection: PublicOutputProjection = PublicOutputProjection.Unfiltered,
    ) : PublicCliCommand

    data class Help(val command: PublicCliCommandId?) : PublicCliCommand

    data class NamespaceHelp(val namespace: PublicCliNamespace) : PublicCliCommand
}

internal enum class PublicCliNamespace(val word: String, val commands: List<PublicCliCommandId>) {
    SOURCE("source", listOf(PublicCliCommandId.SOURCE_READ)),
    RESULT("result", listOf(PublicCliCommandId.RESULT_READ, PublicCliCommandId.RESULT_RESUME)),
    DIAGNOSTIC("diagnostic", listOf(PublicCliCommandId.DIAGNOSTIC_CHECK)),
}

@Serializable
internal enum class PublicCliCommandId(val argv: List<String>, val id: String) {
    @SerialName("query") QUERY(listOf("query"), "query"),
    @SerialName("query.plan") QUERY_PLAN(listOf("query", "plan"), "query.plan"),
    @SerialName("result.read") RESULT_READ(listOf("result", "read"), "result.read"),
    @SerialName("result.resume") RESULT_RESUME(listOf("result", "resume"), "result.resume"),
    @SerialName("source.read") SOURCE_READ(listOf("source", "read"), "source.read"),
    @SerialName("diagnostic.check") DIAGNOSTIC_CHECK(listOf("diagnostic", "check"), "diagnostic.check"),
    @SerialName("describe") DESCRIBE(listOf("describe"), "describe"),
}

@Serializable
internal enum class PublicCliDescribeSection {
    @SerialName("inventory") INVENTORY,
    @SerialName("summary") SUMMARY,
    @SerialName("input") INPUT,
    @SerialName("output") OUTPUT,
    @SerialName("examples") EXAMPLES,
}

internal enum class PublicCliArgumentFailure {
    INVALID_COMMAND,
    UNKNOWN_OPTION,
    MISSING_VALUE,
    DUPLICATE_OPTION,
    INVALID_VALUE,
    MIXED_INPUT_MODES,
    INVALID_SOURCE,
    IRRELEVANT_OPTION,
    INVALID_FIELDS,
    FIELDS_WITH_OCCURRENCES,
    INVALID_SCHEMA_SELECTION,
    INVALID_JQ_OPTIONS,
}

internal fun parsePublicCliArguments(argv: List<String>): Refinement<PublicCliCommand, PublicCliArgumentFailure> =
    when (val lexed = lexPublicCliArguments(argv)) {
        is Refinement.Rejected -> lexed
        is Refinement.Refined -> projectCommand(lexed.value)
    }

private fun projectCommand(arguments: LexicalArguments): Refinement<PublicCliCommand, PublicCliArgumentFailure> {
    val command =
        when (val lowered = lowerCommand(arguments)) {
            is Refinement.Rejected -> return lowered
            is Refinement.Refined -> lowered.value
        }
    val projection =
        when (val selected = PublicJqOptions.parse(arguments)) {
            is Refinement.Rejected -> return selected
            is Refinement.Refined -> selected.value
        }
    return when (command) {
        is PublicCliCommand.Query -> Refinement.Refined(command.copy(projection = projection))
        is PublicCliCommand.Plan -> Refinement.Refined(command.copy(projection = projection))
        is PublicCliCommand.Describe -> Refinement.Refined(command.copy(projection = projection))
        is PublicCliCommand.Help,
        is PublicCliCommand.NamespaceHelp ->
            if (projection == PublicOutputProjection.Unfiltered) Refinement.Refined(command)
            else Refinement.Rejected(PublicCliArgumentFailure.INVALID_JQ_OPTIONS)
    }
}

private fun lowerCommand(arguments: LexicalArguments): Refinement<PublicCliCommand, PublicCliArgumentFailure> {
    val command = findCommand(arguments)
    if (command == null) return missingCommand(arguments)
    if (arguments.options.keys.any { !it.accepts(command) })
        return Refinement.Rejected(PublicCliArgumentFailure.IRRELEVANT_OPTION)
    if (arguments.has(PublicCliFlag.HELP)) return Refinement.Refined(PublicCliCommand.Help(command))
    val catalog =
        when (arguments[PublicCliFlag.CATALOG]) {
            null,
            "complete" -> PublicToolCatalog.COMPLETE
            "common" -> PublicToolCatalog.COMMON
            else -> return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
        }
    if (command == PublicCliCommandId.DESCRIBE) return describeCommand(arguments, catalog)
    if (arguments.words != command.argv) return Refinement.Rejected(PublicCliArgumentFailure.INVALID_COMMAND)
    if (
        command !in setOf(PublicCliCommandId.QUERY, PublicCliCommandId.QUERY_PLAN) &&
            arguments.values(PublicCliFlag.REF).size > 1
    )
        return Refinement.Rejected(PublicCliArgumentFailure.DUPLICATE_OPTION)
    return executionCommand(command, arguments, catalog)
}

private fun findCommand(arguments: LexicalArguments): PublicCliCommandId? =
    PublicCliCommandId.entries
        .sortedByDescending { it.argv.size }
        .firstOrNull { arguments.words.take(it.argv.size) == it.argv }

private fun missingCommand(arguments: LexicalArguments): Refinement<PublicCliCommand, PublicCliArgumentFailure> {
    val namespace = PublicCliNamespace.entries.singleOrNull { arguments.words == listOf(it.word) }
    return when {
        !arguments.has(PublicCliFlag.HELP) -> Refinement.Rejected(PublicCliArgumentFailure.INVALID_COMMAND)
        arguments.words.isEmpty() -> Refinement.Refined(PublicCliCommand.Help(null))
        namespace != null && arguments.options.keys.all { it in processFlags } ->
            Refinement.Refined(PublicCliCommand.NamespaceHelp(namespace))
        else -> Refinement.Rejected(PublicCliArgumentFailure.INVALID_COMMAND)
    }
}

private fun executionCommand(
    command: PublicCliCommandId,
    arguments: LexicalArguments,
    catalog: PublicToolCatalog,
): Refinement<PublicCliCommand, PublicCliArgumentFailure> {
    val workspace = arguments[PublicCliFlag.WORKSPACE]?.let(PublicCliWorkspace::Selected) ?: PublicCliWorkspace.Current
    val input =
        when (val parsed = commandInput(command, arguments)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    return Refinement.Refined(
        if (command == PublicCliCommandId.QUERY_PLAN) PublicCliCommand.Plan(catalog, workspace, input)
        else PublicCliCommand.Query(catalog, workspace, input, command.toolIdentity())
    )
}

internal fun PublicCliCommandId.toolIdentity(): PublicToolIdentity =
    if (this == PublicCliCommandId.DIAGNOSTIC_CHECK) PublicToolIdentity.CHECK_DIAGNOSTICS
    else PublicToolIdentity.QUERY_SYMBOLS

private fun commandInput(
    command: PublicCliCommandId,
    arguments: LexicalArguments,
): Refinement<PublicCliInput, PublicCliArgumentFailure> {
    val request = arguments[PublicCliFlag.REQUEST]
    if (request == null)
        return when (val lowered = lowerFlags(command, arguments)) {
            is Refinement.Rejected -> lowered
            is Refinement.Refined -> Refinement.Refined(PublicCliInput.Lowered(lowered.value))
        }
    if (arguments.options.keys.any { it !in processFlags && it != PublicCliFlag.REQUEST })
        return Refinement.Rejected(PublicCliArgumentFailure.MIXED_INPUT_MODES)
    if (request.isEmpty()) return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    return Refinement.Refined(if (request == "-") PublicCliInput.Stdin else PublicCliInput.File(request))
}

private fun describeCommand(
    arguments: LexicalArguments,
    catalog: PublicToolCatalog,
): Refinement<PublicCliCommand, PublicCliArgumentFailure> {
    val selected =
        when (arguments.words.size) {
            1 -> null
            2 ->
                PublicCliCommandId.entries.singleOrNull { it.id == arguments.words[1] }
                    ?: return Refinement.Rejected(PublicCliArgumentFailure.INVALID_COMMAND)
            else -> return Refinement.Rejected(PublicCliArgumentFailure.INVALID_COMMAND)
        }
    val section =
        when (arguments[PublicCliFlag.SECTION]) {
            null -> if (selected == null) PublicCliDescribeSection.INVENTORY else PublicCliDescribeSection.SUMMARY
            "input" -> PublicCliDescribeSection.INPUT
            "output" -> PublicCliDescribeSection.OUTPUT
            "examples" -> PublicCliDescribeSection.EXAMPLES
            else -> return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
        }
    if (selected == null && section != PublicCliDescribeSection.INVENTORY)
        return Refinement.Rejected(PublicCliArgumentFailure.INVALID_COMMAND)
    return describeSelection(arguments, catalog, selected, section)
}

private fun describeSelection(
    arguments: LexicalArguments,
    catalog: PublicToolCatalog,
    selected: PublicCliCommandId?,
    section: PublicCliDescribeSection,
): Refinement<PublicCliCommand, PublicCliArgumentFailure> {
    val browseFlags =
        setOf(PublicCliFlag.POINTER, PublicCliFlag.CURSOR, PublicCliFlag.MAX_RESULTS, PublicCliFlag.MAX_BYTES)
    if (arguments.options.keys.none { it in browseFlags })
        return Refinement.Refined(PublicCliCommand.Describe(catalog, selected, section))
    if (
        section !in setOf(PublicCliDescribeSection.INPUT, PublicCliDescribeSection.OUTPUT) ||
            !arguments.has(PublicCliFlag.POINTER)
    )
        return Refinement.Rejected(PublicCliArgumentFailure.INVALID_SCHEMA_SELECTION)
    return when (
        val selection =
            PublicSchemaSelection.parse(
                requireNotNull(arguments[PublicCliFlag.POINTER]),
                arguments[PublicCliFlag.CURSOR] ?: "0",
                arguments[PublicCliFlag.MAX_RESULTS] ?: "16",
                arguments[PublicCliFlag.MAX_BYTES] ?: "16384",
            )
    ) {
        is Refinement.Rejected -> selection
        is Refinement.Refined ->
            Refinement.Refined(PublicCliCommand.Describe(catalog, selected, section, selection.value))
    }
}
