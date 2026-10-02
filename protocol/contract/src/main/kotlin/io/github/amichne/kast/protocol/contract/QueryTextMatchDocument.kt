package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.InvalidPathException
import java.nio.file.Path

enum class QueryTextMatchDocumentFailure {
    INVALID_WORD,
    INVALID_FILE,
    CONTEXT_LIMIT_EXCEEDED,
    INVALID_CONTEXT,
    CONTEXT_RANGE_MISMATCH,
    MATCH_OUTSIDE_CONTEXT,
    MATCH_TEXT_MISMATCH,
}

/** Lexical relevance evidence; the result's exact reference establishes declaration identity. */
sealed interface QueryTextMatchDocument {
    @ConsistentCopyVisibility
    data class IndexedWord
    private constructor(
        val word: ProtocolText,
        val file: ProtocolText,
        val range: SourceRangeDocument,
        val context: ProtocolSourceText,
        val contextRange: SourceRangeDocument,
        val line: SourceLineNumberDocument,
    ) : QueryTextMatchDocument {
        companion object {
            fun create(
                word: ProtocolText,
                file: ProtocolText,
                range: SourceRangeDocument,
                context: ProtocolSourceText,
                contextRange: SourceRangeDocument,
                line: SourceLineNumberDocument,
            ): Refinement<IndexedWord, QueryTextMatchDocumentFailure> {
                if (!word.isIndexedQueryWord()) return Refinement.Rejected(QueryTextMatchDocumentFailure.INVALID_WORD)
                if (!file.value.isCanonicalTextMatchFile())
                    return Refinement.Rejected(QueryTextMatchDocumentFailure.INVALID_FILE)
                if (context.value.length > MAX_CONTEXT_LENGTH) {
                    return Refinement.Rejected(QueryTextMatchDocumentFailure.CONTEXT_LIMIT_EXCEEDED)
                }
                if (context.value.any { it.isISOControl() && it != '\t' }) {
                    return Refinement.Rejected(QueryTextMatchDocumentFailure.INVALID_CONTEXT)
                }
                if (contextRange.endExclusive.value - contextRange.startInclusive.value != context.value.length) {
                    return Refinement.Rejected(QueryTextMatchDocumentFailure.CONTEXT_RANGE_MISMATCH)
                }
                if (
                    range.startInclusive.value < contextRange.startInclusive.value ||
                        range.endExclusive.value > contextRange.endExclusive.value
                ) {
                    return Refinement.Rejected(QueryTextMatchDocumentFailure.MATCH_OUTSIDE_CONTEXT)
                }
                val start = range.startInclusive.value - contextRange.startInclusive.value
                val end = range.endExclusive.value - contextRange.startInclusive.value
                if (context.value.substring(start, end) != word.value) {
                    return Refinement.Rejected(QueryTextMatchDocumentFailure.MATCH_TEXT_MISMATCH)
                }
                return Refinement.Refined(IndexedWord(word, file, range, context, contextRange, line))
            }

            const val MAX_CONTEXT_LENGTH = 512
            const val FILE_PATTERN =
                "^(?!.*(?:^|[/\\\\])\\.{1,2}(?:[/\\\\]|$))(?:/|[A-Za-z]:[/\\\\])(?:[^/\\\\\\x00-\\x1F\\x7F-\\x9F]+[/\\\\])*[^/\\\\\\x00-\\x1F\\x7F-\\x9F]+$"
        }
    }
}

fun ProtocolText.isIndexedQueryWord(): Boolean =
    value.length in 1..MAX_INDEXED_QUERY_WORD_LENGTH && INDEXED_QUERY_WORD.matches(value)

private const val MAX_INDEXED_QUERY_WORD_LENGTH = 256
private val INDEXED_QUERY_WORD = Regex("[A-Za-z_][A-Za-z0-9_]*")

private fun String.isCanonicalTextMatchFile(): Boolean {
    if (!Regex(QueryTextMatchDocument.IndexedWord.FILE_PATTERN).matches(this)) return false
    val path =
        try {
            Path.of(this)
        } catch (_: InvalidPathException) {
            return false
        }
    return path.isAbsolute && path.normalize() == path && path.toString() == this
}
