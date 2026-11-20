package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
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
            PublicCliFlag.MAX_DIAGNOSTICS,
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
    if (command == PublicCliCommandId.DIAGNOSTIC_CHECK) return lowerDiagnosticFlags(arguments, budget)
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

private fun lowerDiagnosticFlags(
    arguments: LexicalArguments,
    budget: CliBudget?,
): Refinement<JsonObject, PublicCliArgumentFailure> =
    when (val path = arguments[PublicCliFlag.PATH]) {
        null -> Refinement.Rejected(PublicCliArgumentFailure.MISSING_VALUE)
        else ->
            Refinement.Refined(
                flagJson
                    .encodeToJsonElement(
                        CliDiagnosticInput(
                            path,
                            arguments[PublicCliFlag.MAX_DIAGNOSTICS]?.toLong(),
                            arguments[PublicCliFlag.CONTINUATION],
                            budget,
                            arguments.has(PublicCliFlag.VERBOSE),
                        )
                    )
                    .jsonObject
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
        PublicCliCommandId.DESCRIBE,
        PublicCliCommandId.DIAGNOSTIC_CHECK -> Refinement.Rejected(PublicCliArgumentFailure.INVALID_COMMAND)
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
    val domain = relationDomain(arguments)
    val steps =
        when (val parsed = parseSteps(arguments, domain)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    val terminal =
        when (val parsed = parseTerminal(arguments, output, domain)) {
            is Refinement.Rejected -> return parsed
            is Refinement.Refined -> parsed.value
        }
    if (domain != null && steps.none { it is CliExpandRelation } && terminal !is CliTerminal.Trace)
        return Refinement.Rejected(PublicCliArgumentFailure.IRRELEVANT_OPTION)
    return Refinement.Refined(
        CliAction.Run(
            source,
            steps.takeIf { it.isNotEmpty() },
            terminal,
            if (arguments.has(PublicCliFlag.RETAIN)) CliRetention.RETAIN else null,
            budget,
        )
    )
}

/** Pipeline flags lower in argv order, so `--relation callers --minus R` differs from `--minus R --relation callers`. */
/**
 * Any destination flag selects SOURCE_DOMAIN for every relation hop and TRACE, matching the MCP destination
 * defaults: source sets main and test, production and test sources, generated sources included.
 */
private fun relationDomain(arguments: LexicalArguments): CliSourceDomain? {
    val sets = arguments.values(PublicCliFlag.RELATION_SOURCE_SET)
    val directory = arguments[PublicCliFlag.RELATION_IN]
    val productionOnly = arguments.has(PublicCliFlag.RELATION_PRODUCTION_ONLY)
    val excludeGenerated = arguments.has(PublicCliFlag.RELATION_EXCLUDE_GENERATED)
    if (directory == null && sets.isEmpty() && !productionOnly && !excludeGenerated) return null
    return CliSourceDomain(
        sourceSets = sets.ifEmpty { listOf("main", "test") },
        directory = directory,
        sourcePolicy = if (productionOnly) CliSourcePolicy.PRODUCTION_ONLY else CliSourcePolicy.PRODUCTION_AND_TEST,
        generatedSources = if (excludeGenerated) CliGeneratedSources.EXCLUDE else CliGeneratedSources.INCLUDE,
    )
}

private fun parseSteps(
    arguments: LexicalArguments,
    domain: CliSourceDomain?,
): Refinement<List<CliStep>, PublicCliArgumentFailure> {
    val steps = mutableListOf<CliStep>()
    for ((flag, raw) in arguments.sequence) {
        val step =
            when (flag) {
                PublicCliFlag.RELATION ->
                    CliRelation.entries.singleOrNull { it.spelling == raw }?.let { CliExpandRelation(it, domain) }
                PublicCliFlag.VISIBILITY -> parseVisibility(raw)?.let { CliWhere(CliVisibilityPredicate(it)) }
                PublicCliFlag.INTERSECT -> CliIntersect(CliRetainedInput(raw))
                PublicCliFlag.UNION -> CliUnion(CliRetainedInput(raw))
                PublicCliFlag.MINUS -> CliDifference(CliRetainedInput(raw))
                PublicCliFlag.WITH -> CliJoin(CliJoinMode.Semi, CliRetainedInput(raw))
                PublicCliFlag.WITHOUT -> CliJoin(CliJoinMode.Anti, CliRetainedInput(raw))
                else -> continue
            } ?: return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
        steps.add(step)
    }
    return Refinement.Refined(steps)
}

/** One comma-separated list lowers to one WHERE stage; duplicate and unknown spellings reject here. */
private fun parseVisibility(raw: String): List<CliVisibility>? {
    val names = raw.split(',')
    if (names.distinct().size != names.size) return null
    return names.map { name -> CliVisibility.entries.singleOrNull { it.spelling == name } ?: return null }
}

private fun parseTerminal(
    arguments: LexicalArguments,
    output: CliOutput?,
    domain: CliSourceDomain?,
): Refinement<CliTerminal, PublicCliArgumentFailure> =
    when (arguments[PublicCliFlag.TERMINAL]) {
        null,
        "emit" -> Refinement.Refined(CliTerminal.Emit(output))
        "trace" ->
            when (output) {
                is CliOutput.Symbols,
                null -> Refinement.Refined(CliTerminal.Trace(output, domain))
                is CliOutput.Witness,
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
    val detail =
        when (val raw = arguments[PublicCliFlag.DETAIL]) {
            null -> null
            else -> parseDetailSelector(raw) ?: return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
        }
    return Refinement.Refined(
        CliAction.ReadResult(
            reference,
            arguments[PublicCliFlag.CURSOR]?.toLong(),
            arguments[PublicCliFlag.EVIDENCE_CURSOR]?.toLong(),
            output,
            budget,
            detail,
        )
    )
}

/** Only JSON syntax is checked here; a malformed document is the one finite ingress failure. */
private fun parseDetailSelector(raw: String): JsonObject? =
    try {
        flagJson.parseToJsonElement(raw) as? JsonObject
    } catch (_: SerializationException) {
        null
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
    val all = arguments.has(PublicCliFlag.ALL)
    val retained = arguments[PublicCliFlag.FROM]
    if (
        listOf(name != null, refs.isNotEmpty(), file != null || offset != null, all, retained != null).count { it } !=
            1
    )
        return Refinement.Rejected(PublicCliArgumentFailure.INVALID_SOURCE)
    if (name != null) return parseSearchSource(arguments, name)
    if (all) return parseAllSource(arguments)
    if (
        setOf(PublicCliFlag.DIRECTORY, PublicCliFlag.SOURCE_SET, PublicCliFlag.KIND, PublicCliFlag.NAME_MATCH)
            .any(arguments::has)
    )
        return Refinement.Rejected(PublicCliArgumentFailure.IRRELEVANT_OPTION)
    return if (retained != null) Refinement.Refined(CliSource.Result(retained))
    else if (refs.isNotEmpty()) Refinement.Refined(CliSource.References(refs))
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
    val kinds = parseKinds(arguments) ?: return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    return Refinement.Refined(CliSource.Search(name, match, kinds.takeIf { it.isNotEmpty() }, directoryScope(arguments)))
}

private fun parseAllSource(arguments: LexicalArguments): Refinement<CliSource, PublicCliArgumentFailure> {
    if (arguments.has(PublicCliFlag.NAME_MATCH)) return Refinement.Rejected(PublicCliArgumentFailure.IRRELEVANT_OPTION)
    val kinds = parseKinds(arguments) ?: return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    return Refinement.Refined(CliSource.All(kinds.takeIf { it.isNotEmpty() }, directoryScope(arguments)))
}

private fun parseKinds(arguments: LexicalArguments): List<CliKind>? =
    arguments.values(PublicCliFlag.KIND).map { raw ->
        CliKind.entries.singleOrNull { it.spelling == raw } ?: return null
    }

private fun directoryScope(arguments: LexicalArguments): CliDirectory? {
    val sets = arguments.values(PublicCliFlag.SOURCE_SET)
    val directory = arguments[PublicCliFlag.DIRECTORY]
    return if (directory != null || sets.isNotEmpty()) CliDirectory(directory ?: ".", sets.takeIf { it.isNotEmpty() })
    else null
}

private fun parseOutput(arguments: LexicalArguments): Refinement<CliOutput?, PublicCliArgumentFailure> {
    val raw = arguments[PublicCliFlag.FIELDS]
    val fields = if (raw == null) null else if (raw.isEmpty()) emptyList() else raw.split(',')
    if (
        fields != null &&
            (fields.distinct().size != fields.size ||
                fields.any { name -> CliField.entries.none { it.spelling == name } })
    )
        return Refinement.Rejected(PublicCliArgumentFailure.INVALID_FIELDS)
    return when (arguments[PublicCliFlag.OUTPUT]) {
        "occurrences" ->
            if (fields == null) Refinement.Refined(CliOutput.Occurrences)
            else Refinement.Rejected(PublicCliArgumentFailure.FIELDS_WITH_OCCURRENCES)
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
