package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement

/** Lexical options share their inventory with local discovery. Semantic limits stay in public admission. */
internal enum class PublicCliFlag(val spelling: String, val takesValue: Boolean = true, val repeated: Boolean = false) {
    WORKSPACE("--workspace"),
    CATALOG("--catalog"),
    JSON("--json", false),
    HELP("--help", false),
    REQUEST("--request"),
    NAME("--name"),
    REF("--ref", repeated = true),
    FILE("--file"),
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
    MAX_RESULTS("--max-results"),
    MAX_BYTES("--max-returned-bytes"),
    MAX_ELAPSED("--max-elapsed-ms"),
    VERBOSE("--verbose", false),
    CURSOR("--cursor"),
    EVIDENCE_CURSOR("--evidence-cursor"),
    CONTINUATION("--continuation"),
    REGION("--region"),
    TEXT("--text"),
    SECTION("--section");

    fun accepts(command: PublicCliCommandId): Boolean = this in flagsFor(command)
}

internal val processFlags =
    setOf(PublicCliFlag.WORKSPACE, PublicCliFlag.CATALOG, PublicCliFlag.JSON, PublicCliFlag.HELP)
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
        PublicCliFlag.OUTPUT,
        PublicCliFlag.FIELDS,
        PublicCliFlag.RETAIN,
        PublicCliFlag.MAX_RESULTS,
        PublicCliFlag.MAX_BYTES,
        PublicCliFlag.MAX_ELAPSED,
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
        PublicCliCommandId.DESCRIBE ->
            setOf(PublicCliFlag.CATALOG, PublicCliFlag.JSON, PublicCliFlag.HELP, PublicCliFlag.SECTION)
    }

internal data class LexicalArguments(val words: List<String>, val options: Map<PublicCliFlag, List<String>>) {
    operator fun get(flag: PublicCliFlag): String? = options[flag]?.singleOrNull()

    fun has(flag: PublicCliFlag): Boolean = flag in options

    fun values(flag: PublicCliFlag): List<String> = options[flag].orEmpty()
}

/** This lexer only recognizes argv. PublicToolContract remains the sole semantic admission owner. */
internal fun lexPublicCliArguments(argv: List<String>): Refinement<LexicalArguments, PublicCliArgumentFailure> {
    val words = mutableListOf<String>()
    val options = mutableMapOf<PublicCliFlag, MutableList<String>>()
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
        index += option.width
    }
    return Refinement.Refined(LexicalArguments(words, options))
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
