package fixture.calls

// Intentionally unresolved native K2 inputs, excluded from the compiled positive fixture.
fun unresolvedMapping(): String = missingInlineHelper { inlineTarget() }
inline fun ambiguousInline(marker: Int = 0, block: () -> String): String = block()
inline fun ambiguousInline(marker: String = "", block: () -> String): String = block()
fun ambiguousMapping(): String = ambiguousInline { inlineTarget() }
