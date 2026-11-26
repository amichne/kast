package io.github.amichne.kast.symbol.contract

/**
 * Whether Kotlin also calls a function named [name] through operator or delegate syntax (`plus`, `get`, `invoke`,
 * `componentN`, ...). Kotlin accepts a Java method with such a name as an operator without a modifier.
 */
fun isKotlinConventionName(name: String): Boolean = name in KOTLIN_CONVENTION_NAMES || COMPONENT_NAME.matches(name)

/**
 * Every `getS` or `setS` that Kotlin reads as the property [name]. Kotlin decapitalizes `S` smartly, so `getURL` reads
 * as `url` and `getURLConnection` as `urlConnection`. An `isS` accessor keeps its own name and is accessor-shaped
 * already. A name that starts with an upper-case letter is never a synthetic property name.
 */
fun accessorsNamedAsProperty(name: String): List<String> {
    if (name.isEmpty() || !name[0].isLowerCase()) return emptyList()
    val suffixes =
        (1..name.length)
            .map { length -> name.take(length).uppercase() + name.drop(length) }
            .filter { it.decapitalizeSmart() == name }
            .distinct()
    return suffixes.flatMap { listOf("get$it", "set$it") }
}

/** Kotlin's synthetic property name rule: lower the leading upper-case run, except a last capital before lower case. */
private fun String.decapitalizeSmart(): String {
    if (isEmpty() || !this[0].isUpperCase()) return this
    if (length == 1 || !this[1].isUpperCase()) return this[0].lowercaseChar() + substring(1)
    val run = indexOfFirst { !it.isUpperCase() }.let { if (it < 0) length else it }
    if (run == length) return lowercase()
    return substring(0, run - 1).lowercase() + substring(run - 1)
}

private val COMPONENT_NAME = Regex("component[1-9][0-9]*")

private val KOTLIN_CONVENTION_NAMES =
    setOf(
        "plus",
        "minus",
        "times",
        "div",
        "rem",
        "mod",
        "rangeTo",
        "rangeUntil",
        "unaryPlus",
        "unaryMinus",
        "not",
        "inc",
        "dec",
        "contains",
        "get",
        "set",
        "invoke",
        "compareTo",
        "equals",
        "iterator",
        "next",
        "hasNext",
        "plusAssign",
        "minusAssign",
        "timesAssign",
        "divAssign",
        "remAssign",
        "modAssign",
        "getValue",
        "setValue",
        "provideDelegate",
    )
