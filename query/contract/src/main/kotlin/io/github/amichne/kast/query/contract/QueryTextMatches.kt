package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolTextMatch
import java.util.Collections

enum class QueryTextMatchFailure {
    ITEM_LIMIT_EXCEEDED
}

private const val MATCH_PROJECTION_OVERHEAD_BYTES = 512L
private const val JSON_ESCAPE_BYTES_PER_CHARACTER = 6L

/** Detached lexical relevance evidence, independent of compiler-proven relation arrival. */
class QueryTextMatches private constructor(val values: List<SymbolTextMatch>) {
    companion object {
        /** Equal to the canonical public protocol's bounded-list capacity. */
        const val MAX_ITEMS: Int = 1000
        val Empty: QueryTextMatches = QueryTextMatches(emptyList())

        fun singleton(match: SymbolTextMatch): QueryTextMatches = QueryTextMatches(listOf(match))

        fun from(matches: List<SymbolTextMatch>): Refinement<QueryTextMatches, QueryTextMatchFailure> =
            collect(matches.asSequence())

        private fun collect(matches: Sequence<SymbolTextMatch>): Refinement<QueryTextMatches, QueryTextMatchFailure> {
            val distinct = linkedSetOf<SymbolTextMatch>()
            for (match in matches) {
                if (match !in distinct && distinct.size == MAX_ITEMS) {
                    return Refinement.Rejected(QueryTextMatchFailure.ITEM_LIMIT_EXCEEDED)
                }
                distinct += match
            }
            val result =
                if (distinct.isEmpty()) Empty else QueryTextMatches(Collections.unmodifiableList(distinct.toList()))
            return Refinement.Refined(result)
        }
    }

    /** Every exemplar belongs to this exact owner under the same read authority. */
    fun belongsTo(selector: SymbolSelector): Boolean = values.all { match ->
        match.lease == selector.lease &&
            SymbolDiscoveryFileIdentity.Workspace(match.file) == selector.file &&
            match.declarationRange.startInclusive.value == selector.range.startInclusive &&
            match.declarationRange.endExclusive.value == selector.range.endExclusive
    }

    fun merge(other: QueryTextMatches): Refinement<QueryTextMatches, QueryTextMatchFailure> =
        when {
            other.values.isEmpty() -> Refinement.Refined(this)
            values.isEmpty() -> Refinement.Refined(other)
            else -> collect(values.asSequence() + other.values.asSequence())
        }

    /** Includes fixed wire field names and worst-case JSON escaping of the bounded context. */
    fun projectedUtf8Size(): Long =
        values.fold(0L) { bytes, match ->
            bytes.saturatedAdd(
                MATCH_PROJECTION_OVERHEAD_BYTES +
                    JSON_ESCAPE_BYTES_PER_CHARACTER *
                        (match.word.value.length.toLong() + match.file.value.length + match.context.length)
            )
        }

    override fun equals(other: Any?): Boolean = other is QueryTextMatches && values == other.values

    override fun hashCode(): Int = values.hashCode()
}
