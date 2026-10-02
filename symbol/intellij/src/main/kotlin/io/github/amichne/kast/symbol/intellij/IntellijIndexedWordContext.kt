package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.editor.Document
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWord
import io.github.amichne.kast.symbol.contract.SymbolTextMatch

/** One current-document occurrence and bounded line window; exact owner binding follows at the domain factory. */
internal data class IntellijIndexedWordContext(
    val range: SymbolDiscoverySourceRange,
    val text: String,
    val contextRange: SymbolDiscoverySourceRange,
    val line: Int,
)

/** Document and UTF16 offsets are observed within one native read; no disk text or unbounded line scan is used. */
internal fun projectIndexedWordContext(
    document: Document,
    offset: SymbolDiscoverySourceOffset,
    word: SymbolDiscoveryWord,
): Refinement<IntellijIndexedWordContext, SymbolDiscoveryQualification> {
    val start = offset.value
    val text = document.immutableCharSequence
    if (start > text.length - word.value.length)
        return Refinement.Rejected(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
    val end = start + word.value.length
    if (!text.matchesWord(start, end, word)) return Refinement.Rejected(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
    val range =
        when (val parsed = SymbolDiscoverySourceRange.parse(start, end)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
        }
    val lineIndex = document.getLineNumber(start)
    val rawContext = boundedLineContext(document, text, lineIndex, range)
    val contextRange =
        when (val parsed = SymbolDiscoverySourceRange.parse(rawContext.startOffset, rawContext.endOffset)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(SymbolDiscoveryQualification.UNSUPPORTED_ITEM)
        }
    return Refinement.Refined(
        IntellijIndexedWordContext(
            range,
            text.subSequence(rawContext.startOffset, rawContext.endOffset).toString(),
            contextRange,
            lineIndex + 1,
        )
    )
}

private fun CharSequence.matchesWord(start: Int, end: Int, word: SymbolDiscoveryWord): Boolean {
    if (subSequence(start, end).toString() != word.value) return false
    if (start > 0 && this[start - 1].isWordPart()) return false
    return end >= length || !this[end].isWordPart()
}

private fun boundedLineContext(
    document: Document,
    text: CharSequence,
    line: Int,
    range: SymbolDiscoverySourceRange,
): com.intellij.openapi.util.TextRange {
    val start = range.startInclusive.value
    val end = range.endExclusive.value
    val lineStart = document.getLineStartOffset(line)
    val lineEnd = document.getLineEndOffset(line)
    val wordLength = end - start
    var contextStart = maxOf(lineStart, start - (SymbolTextMatch.MAX_CONTEXT_LENGTH - wordLength) / 2)
    var contextEnd = contextStart + minOf(SymbolTextMatch.MAX_CONTEXT_LENGTH, lineEnd - contextStart)
    if (contextEnd < end) {
        contextEnd = end
        contextStart = maxOf(lineStart, contextEnd - SymbolTextMatch.MAX_CONTEXT_LENGTH)
    }
    // A bounded window must preserve complete UTF16 code points at both edges.
    if (text.splitsSurrogateAt(contextStart)) contextStart += 1
    if (text.splitsSurrogateAt(contextEnd)) contextEnd -= 1
    return com.intellij.openapi.util.TextRange(contextStart, contextEnd)
}

private fun CharSequence.splitsSurrogateAt(offset: Int): Boolean =
    offset in 1 until length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()

private fun Char.isWordPart(): Boolean = isJavaIdentifierPart() && this != '$'
