package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal fun lowerFlags(
    command: PublicCliCommandId,
    arguments: LexicalArguments,
): Refinement<JsonObject, PublicCliArgumentFailure> {
    for (flag in
        listOf(
            PublicCliFlag.MAX_RESULTS,
            PublicCliFlag.MAX_BYTES,
            PublicCliFlag.MAX_ELAPSED,
            PublicCliFlag.CURSOR,
            PublicCliFlag.EVIDENCE_CURSOR,
            PublicCliFlag.OFFSET,
        )) {
        val raw = arguments[flag]
        if (raw != null && raw.toLongOrNull() == null)
            return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    }
    val budget =
        CliBudget(
                arguments[PublicCliFlag.MAX_ELAPSED]?.toLong(),
                arguments[PublicCliFlag.MAX_RESULTS]?.toLong(),
                arguments[PublicCliFlag.MAX_BYTES]?.toLong(),
            )
            .takeUnless { it == CliBudget() }
    val output =
        when (val parsed = parseOutput(arguments)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    val action =
        when (val parsed = lowerAction(command, arguments, output, budget)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    return Refinement.Refined(
        flagJson.encodeToJsonElement(CliEnvelope(action, arguments.has(PublicCliFlag.VERBOSE))).jsonObject
    )
}

private fun lowerAction(
    command: PublicCliCommandId,
    arguments: LexicalArguments,
    output: CliOutput?,
    budget: CliBudget?,
): Refinement<CliAction, PublicCliArgumentFailure> =
    when (command) {
        PublicCliCommandId.QUERY,
        PublicCliCommandId.QUERY_PLAN -> lowerRun(arguments, output, budget)
        PublicCliCommandId.RESULT_READ -> lowerReadResult(arguments, output, budget)
        PublicCliCommandId.RESULT_RESUME -> {
            val continuation = arguments[PublicCliFlag.CONTINUATION]
            if (continuation == null) Refinement.Rejected(PublicCliArgumentFailure.MISSING_VALUE)
            else Refinement.Refined(CliAction.Resume(continuation, budget))
        }
        PublicCliCommandId.SOURCE_READ -> lowerReadSource(arguments, budget)
        PublicCliCommandId.DESCRIBE -> Refinement.Rejected(PublicCliArgumentFailure.INVALID_COMMAND)
    }

private fun lowerRun(
    arguments: LexicalArguments,
    output: CliOutput?,
    budget: CliBudget?,
): Refinement<CliAction, PublicCliArgumentFailure> {
    val source =
        when (val parsed = parseSource(arguments)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    val relations = mutableListOf<CliRelation>()
    for (raw in arguments.values(PublicCliFlag.RELATION)) {
        val relation =
            CliRelation.entries.singleOrNull { it.spelling == raw }
                ?: return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
        relations.add(relation)
    }
    val terminal =
        when (val parsed = parseTerminal(arguments, output)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    return Refinement.Refined(
        CliAction.Run(
            source,
            relations.map(::CliExpandRelation).takeIf { it.isNotEmpty() },
            terminal,
            if (arguments.has(PublicCliFlag.RETAIN)) CliRetention.RETAIN else null,
            budget,
        )
    )
}

private fun parseTerminal(
    arguments: LexicalArguments,
    output: CliOutput?,
): Refinement<CliTerminal, PublicCliArgumentFailure> =
    when (arguments[PublicCliFlag.TERMINAL]) {
        null,
        "emit" -> Refinement.Refined(CliTerminal.Emit(output))
        "trace" ->
            when (output) {
                is CliOutput.Symbols,
                null -> Refinement.Refined(CliTerminal.Trace(output))
                CliOutput.Occurrences -> Refinement.Rejected(PublicCliArgumentFailure.IRRELEVANT_OPTION)
            }
        else -> Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    }

private fun lowerReadResult(
    arguments: LexicalArguments,
    output: CliOutput?,
    budget: CliBudget?,
): Refinement<CliAction, PublicCliArgumentFailure> {
    val reference = arguments[PublicCliFlag.REF] ?: return Refinement.Rejected(PublicCliArgumentFailure.MISSING_VALUE)
    return Refinement.Refined(
        CliAction.ReadResult(
            reference,
            arguments[PublicCliFlag.CURSOR]?.toLong(),
            arguments[PublicCliFlag.EVIDENCE_CURSOR]?.toLong(),
            output,
            budget,
        )
    )
}

private fun lowerReadSource(
    arguments: LexicalArguments,
    budget: CliBudget?,
): Refinement<CliAction, PublicCliArgumentFailure> {
    val reference = arguments[PublicCliFlag.REF] ?: return Refinement.Rejected(PublicCliArgumentFailure.MISSING_VALUE)
    val rawRegion = arguments[PublicCliFlag.REGION]
    val region = CliRegion.entries.singleOrNull { it.spelling == rawRegion }
    if (rawRegion != null && region == null) return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    val text =
        when (arguments[PublicCliFlag.TEXT]) {
            null -> null
            "complete" -> CliText.Complete
            "none" -> CliText.None
            else -> return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
        }
    return Refinement.Refined(CliAction.ReadSource(reference, region, text, budget))
}

private fun parseSource(arguments: LexicalArguments): Refinement<CliSource, PublicCliArgumentFailure> {
    val name = arguments[PublicCliFlag.NAME]
    val refs = arguments.values(PublicCliFlag.REF)
    val file = arguments[PublicCliFlag.FILE]
    val offset = arguments[PublicCliFlag.OFFSET]
    if (listOf(name != null, refs.isNotEmpty(), file != null || offset != null).count { it } != 1)
        return Refinement.Rejected(PublicCliArgumentFailure.INVALID_SOURCE)
    if (name != null) return parseSearchSource(arguments, name)
    if (
        setOf(PublicCliFlag.DIRECTORY, PublicCliFlag.SOURCE_SET, PublicCliFlag.KIND, PublicCliFlag.NAME_MATCH)
            .any(arguments::has)
    )
        return Refinement.Rejected(PublicCliArgumentFailure.IRRELEVANT_OPTION)
    return if (refs.isNotEmpty()) Refinement.Refined(CliSource.References(refs))
    else if (file != null && offset != null) Refinement.Refined(CliSource.Location(file, offset.toLong()))
    else Refinement.Rejected(PublicCliArgumentFailure.INVALID_SOURCE)
}

private fun parseSearchSource(
    arguments: LexicalArguments,
    name: String,
): Refinement<CliSource, PublicCliArgumentFailure> {
    val match =
        when (arguments[PublicCliFlag.NAME_MATCH]) {
            null -> null
            "exact" -> CliNameMatch.EXACT
            "fuzzy" -> CliNameMatch.FUZZY
            else -> return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
        }
    val kinds = mutableListOf<CliKind>()
    for (raw in arguments.values(PublicCliFlag.KIND)) {
        val kind = CliKind.entries.singleOrNull { it.spelling == raw }
        if (kind == null) return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
        kinds.add(kind)
    }
    val sets = arguments.values(PublicCliFlag.SOURCE_SET)
    val directory = arguments[PublicCliFlag.DIRECTORY]
    val scope =
        if (directory != null || sets.isNotEmpty()) CliDirectory(directory ?: ".", sets.takeIf { it.isNotEmpty() })
        else null
    return Refinement.Refined(CliSource.Search(name, match, kinds.takeIf { it.isNotEmpty() }, scope))
}

private fun parseOutput(arguments: LexicalArguments): Refinement<CliOutput?, PublicCliArgumentFailure> {
    val raw = arguments[PublicCliFlag.FIELDS]
    val fields = if (raw == null) null else if (raw.isEmpty()) emptyList() else raw.split(',')
    if (
        fields != null &&
            (fields.distinct().size != fields.size ||
                fields.any { name -> CliField.entries.none { it.spelling == name } })
    )
        return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    return when (arguments[PublicCliFlag.OUTPUT]) {
        "occurrences" ->
            if (fields == null) Refinement.Refined(CliOutput.Occurrences)
            else Refinement.Rejected(PublicCliArgumentFailure.IRRELEVANT_OPTION)
        null ->
            Refinement.Refined(
                fields?.let { CliOutput.Symbols(it.map { name -> CliField.entries.single { it.spelling == name } }) }
            )
        "symbols" ->
            Refinement.Refined(
                CliOutput.Symbols(
                    fields?.map { name -> CliField.entries.single { it.spelling == name } }
                        ?: PublicToolContract.defaultQuerySymbolFields().map { field -> CliField.valueOf(field.name) }
                )
            )
        else -> Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    }
}

private val flagJson = Json {
    encodeDefaults = false
    explicitNulls = false
}
