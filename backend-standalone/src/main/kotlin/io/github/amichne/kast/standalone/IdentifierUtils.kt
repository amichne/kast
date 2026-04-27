package io.github.amichne.kast.standalone

internal val identifierRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")

internal fun String.isIndexableIdentifier(): Boolean = identifierRegex.matches(this)

internal fun String.identifierOccurrenceOffsets(identifier: String): Sequence<Int> = sequence {
    var searchFrom = 0
    while (true) {
        val occurrenceOffset = indexOf(identifier, startIndex = searchFrom)
        if (occurrenceOffset == -1) {
            break
        }

        val before = getOrNull(occurrenceOffset - 1)
        val after = getOrNull(occurrenceOffset + identifier.length)
        val startsIdentifier = before?.isKastIdentifierPart() != true
        val endsIdentifier = after?.isKastIdentifierPart() != true
        if (startsIdentifier && endsIdentifier) {
            yield(occurrenceOffset)
        }

        searchFrom = occurrenceOffset + identifier.length
    }
}

internal fun Char.isKastIdentifierPart(): Boolean = this == '_' || isLetterOrDigit()
