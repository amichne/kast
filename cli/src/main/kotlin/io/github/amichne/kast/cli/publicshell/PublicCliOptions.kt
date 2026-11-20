package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement

/** Lexical options share their inventory with local discovery. Semantic limits stay in public admission. */
internal enum class PublicCliFlag(val spelling: String, val takesValue: Boolean = true, val repeated: Boolean = false) {
    WORKSPACE("--workspace"),
    CATALOG("--catalog"),
    JSON("--json", false),
    JQ("--jq"),
    JQ_TIMEOUT("--jq-timeout-ms"),
    JQ_BYTES("--jq-max-bytes"),
    HELP("--help", false),
    REQUEST("--request"),
    NAME("--name"),
    REF("--ref", repeated = true),
    FILE("--file"),
    PATH("--path"),
    MAX_DIAGNOSTICS("--max-diagnostics"),
    OFFSET("--offset"),
    DIRECTORY("--in"),
    SOURCE_SET("--source-set", repeated = true),
    KIND("--kind", repeated = true),
    NAME_MATCH("--name-match"),
    RELATION("--relation", repeated = true),
    TERMINAL("--terminal"),
    OUTPUT("--output"),
    FIELDS("--fields"),
    RETAIN("--retain", false),
    ALL("--all", false),
    FROM("--from"),
    VISIBILITY("--visibility", repeated = true),
    INTERSECT("--intersect", repeated = true),
    UNION("--union", repeated = true),
    MINUS("--minus", repeated = true),
    WITH("--with", repeated = true),
    WITHOUT("--without", repeated = true),
    RELATION_IN("--relation-in"),
    RELATION_SOURCE_SET("--relation-source-set", repeated = true),
    RELATION_PRODUCTION_ONLY("--relation-production-only", false),
    RELATION_EXCLUDE_GENERATED("--relation-exclude-generated", false),
    MAX_RESULTS("--max-results"),
    MAX_BYTES("--max-returned-bytes"),
    MAX_ELAPSED("--max-elapsed-ms"),
    VERBOSE("--verbose", false),
    CURSOR("--cursor"),
    EVIDENCE_CURSOR("--evidence-cursor"),
    DETAIL("--detail"),
    CONTINUATION("--continuation"),
    REGION("--region"),
    TEXT("--text"),
    SECTION("--section"),
    POINTER("--pointer");

    fun accepts(command: PublicCliCommandId): Boolean = this in flagsFor(command)
}

internal val processFlags =
    setOf(PublicCliFlag.WORKSPACE, PublicCliFlag.CATALOG, PublicCliFlag.JSON, PublicCliFlag.HELP) + jqFlags
private val queryFlags =
    setOf(
        PublicCliFlag.REQUEST,
        PublicCliFlag.NAME,
        PublicCliFlag.REF,
        PublicCliFlag.FILE,
        PublicCliFlag.OFFSET,
        PublicCliFlag.DIRECTORY,
        PublicCliFlag.SOURCE_SET,
        PublicCliFlag.KIND,
        PublicCliFlag.NAME_MATCH,
        PublicCliFlag.RELATION,
        PublicCliFlag.TERMINAL,
        PublicCliFlag.ALL,
        PublicCliFlag.FROM,
        PublicCliFlag.VISIBILITY,
        PublicCliFlag.INTERSECT,
        PublicCliFlag.UNION,
        PublicCliFlag.MINUS,
        PublicCliFlag.WITH,
        PublicCliFlag.WITHOUT,
        PublicCliFlag.RELATION_IN,
        PublicCliFlag.RELATION_SOURCE_SET,
        PublicCliFlag.RELATION_PRODUCTION_ONLY,
        PublicCliFlag.RELATION_EXCLUDE_GENERATED,
        PublicCliFlag.OUTPUT,
        PublicCliFlag.FIELDS,
        PublicCliFlag.RETAIN,
        PublicCliFlag.MAX_RESULTS,
        PublicCliFlag.MAX_BYTES,
        PublicCliFlag.MAX_ELAPSED,
        PublicCliFlag.VERBOSE,
    )

private val diagnosticFlags =
    setOf(
        PublicCliFlag.REQUEST,
        PublicCliFlag.PATH,
        PublicCliFlag.MAX_DIAGNOSTICS,
        PublicCliFlag.CONTINUATION,
        PublicCliFlag.MAX_ELAPSED,
        PublicCliFlag.MAX_BYTES,
        PublicCliFlag.VERBOSE,
    )

internal fun flagsFor(command: PublicCliCommandId): Set<PublicCliFlag> =
    when (command) {
        PublicCliCommandId.QUERY,
        PublicCliCommandId.QUERY_PLAN -> processFlags + queryFlags
        PublicCliCommandId.RESULT_READ ->
            processFlags +
                setOf(
                    PublicCliFlag.REF,
                    PublicCliFlag.CURSOR,
                    PublicCliFlag.EVIDENCE_CURSOR,
                    PublicCliFlag.DETAIL,
                    PublicCliFlag.OUTPUT,
                    PublicCliFlag.FIELDS,
                    PublicCliFlag.MAX_RESULTS,
                    PublicCliFlag.MAX_BYTES,
                    PublicCliFlag.VERBOSE,
                )
        PublicCliCommandId.RESULT_RESUME ->
            processFlags +
                setOf(
                    PublicCliFlag.CONTINUATION,
                    PublicCliFlag.MAX_RESULTS,
                    PublicCliFlag.MAX_BYTES,
                    PublicCliFlag.VERBOSE,
                )
        PublicCliCommandId.SOURCE_READ ->
            processFlags +
                setOf(
                    PublicCliFlag.REF,
                    PublicCliFlag.REGION,
                    PublicCliFlag.TEXT,
                    PublicCliFlag.MAX_BYTES,
                    PublicCliFlag.VERBOSE,
                )
        PublicCliCommandId.DIAGNOSTIC_CHECK -> processFlags + diagnosticFlags
        PublicCliCommandId.DESCRIBE ->
            jqFlags +
                setOf(
                    PublicCliFlag.CATALOG,
                    PublicCliFlag.JSON,
                    PublicCliFlag.HELP,
                    PublicCliFlag.SECTION,
                    PublicCliFlag.POINTER,
                    PublicCliFlag.CURSOR,
                    PublicCliFlag.MAX_RESULTS,
                    PublicCliFlag.MAX_BYTES,
                )
    }

/** [sequence] keeps argv order across distinct flags; pipeline stages lower in exactly that order. */
internal data class LexicalArguments(
    val words: List<String>,
    val options: Map<PublicCliFlag, List<String>>,
    val sequence: List<Pair<PublicCliFlag, String>> = emptyList(),
) {
    operator fun get(flag: PublicCliFlag): String? = options[flag]?.singleOrNull()

    fun has(flag: PublicCliFlag): Boolean = flag in options

    fun values(flag: PublicCliFlag): List<String> = options[flag].orEmpty()
}

/** This lexer only recognizes argv. PublicToolContract remains the sole semantic admission owner. */
internal fun lexPublicCliArguments(argv: List<String>): Refinement<LexicalArguments, PublicCliArgumentFailure> {
    val words = mutableListOf<String>()
    val options = mutableMapOf<PublicCliFlag, MutableList<String>>()
    val sequence = mutableListOf<Pair<PublicCliFlag, String>>()
    var index = 0
    while (index < argv.size) {
        val token = argv[index]
        if (!token.startsWith("-")) {
            words.add(token)
            index++
            continue
        }
        val option =
            when (val parsed = lexOption(argv, index, options.keys)) {
                is Refinement.Rejected -> return parsed
                is Refinement.Refined -> parsed.value
            }
        options.getOrPut(option.flag) { mutableListOf() }.add(option.value)
        sequence.add(option.flag to option.value)
        index += option.width
    }
    return Refinement.Refined(LexicalArguments(words, options, sequence))
}

private data class LexicalOption(val flag: PublicCliFlag, val value: String, val width: Int)

private fun lexOption(
    argv: List<String>,
    index: Int,
    supplied: Set<PublicCliFlag>,
): Refinement<LexicalOption, PublicCliArgumentFailure> {
    val token = argv[index]
    val name = token.substringBefore('=')
    val flag =
        PublicCliFlag.entries.singleOrNull { it.spelling == name || name == "-h" && it == PublicCliFlag.HELP }
            ?: return Refinement.Rejected(PublicCliArgumentFailure.UNKNOWN_OPTION)
    if (!flag.repeated && flag in supplied) return Refinement.Rejected(PublicCliArgumentFailure.DUPLICATE_OPTION)
    if (flag.takesValue) return lexValue(argv, index, flag)
    if ('=' in token) return Refinement.Rejected(PublicCliArgumentFailure.INVALID_VALUE)
    return Refinement.Refined(LexicalOption(flag, "", 1))
}

private fun lexValue(
    argv: List<String>,
    index: Int,
    flag: PublicCliFlag,
): Refinement<LexicalOption, PublicCliArgumentFailure> {
    val token = argv[index]
    if ('=' in token) return Refinement.Refined(LexicalOption(flag, token.substringAfter('='), 1))
    val value = argv.getOrNull(index + 1)
    if (value == null || value.startsWith("--")) return Refinement.Rejected(PublicCliArgumentFailure.MISSING_VALUE)
    return Refinement.Refined(LexicalOption(flag, value, 2))
}
