package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import java.util.Collections

private const val JOIN_STATE_OVERHEAD_BYTES = 512L
private const val JOIN_INDEX_ENTRY_BYTES = 128L

/** The index stores positions, preserving repeated right rows rather than collapsing symbol identity. */
internal data class QueryJoinIndex(
    val consumed: Int,
    val positions: Map<CanonicalSymbolId, List<Int>>,
)

private class MutableJoinIndex(
    var consumed: Int,
    val positions: MutableMap<CanonicalSymbolId, MutableList<Int>>,
) {
    constructor(
        snapshot: QueryJoinIndex
    ) : this(
        snapshot.consumed,
        snapshot.positions.mapValuesTo(linkedMapOf()) { (_, values) -> values.toMutableList() },
    )

    fun snapshot(): QueryJoinIndex =
        QueryJoinIndex(
            consumed,
            Collections.unmodifiableMap(
                positions.mapValues { (_, values) -> Collections.unmodifiableList(values.toList()) }
            ),
        )
}

internal data class QueryJoinSnapshot(val indexes: Map<ExactQueryStage.Join, QueryJoinIndex>)

internal enum class QueryJoinBuild {
    BUILT,
    COMPLETE,
    BYTE_LIMIT,
}

/** Request-local, bounded build state; snapshots contain no compiler or host objects. */
internal class QueryJoins(restored: QueryJoinSnapshot = QueryJoinSnapshot(emptyMap())) {
    private val indexes = restored.indexes.mapValues { (_, index) -> MutableJoinIndex(index) }.toMutableMap()

    fun rightRows(stage: ExactQueryStage.Join): List<QuerySymbol> = stage.right.symbols

    fun buildNext(stage: ExactQueryStage.Join, maximumBytes: Long): QueryJoinBuild {
        val right = rightRows(stage)
        val prior = indexes[stage]
        val current = prior ?: MutableJoinIndex(0, linkedMapOf())
        if (current.consumed >= right.size) return QueryJoinBuild.COMPLETE
        val added = saturatedAdd(JOIN_INDEX_ENTRY_BYTES, if (prior == null) JOIN_STATE_OVERHEAD_BYTES else 0L)
        if (saturatedAdd(retainedBytes(), added) > maximumBytes) return QueryJoinBuild.BYTE_LIMIT
        val position = current.consumed
        val key = CanonicalSymbolId.from(right[position].selector)
        current.positions.getOrPut(key, ::mutableListOf).add(position)
        current.consumed = position + 1
        indexes[stage] = current
        return QueryJoinBuild.BUILT
    }

    fun matches(stage: ExactQueryStage.Join, left: QuerySymbol): List<Int>? {
        val index = indexes[stage] ?: MutableJoinIndex(0, linkedMapOf())
        if (index.consumed != rightRows(stage).size) return null
        return index.positions[CanonicalSymbolId.from(left.selector)].orEmpty()
    }

    fun retainedBytes(): Long {
        val indexEntries =
            indexes.values.fold(0L) { total, index ->
                index.positions.values.fold(total) { count, positions -> saturatedAdd(count, positions.size.toLong()) }
            }
        return saturatedAdd(
            saturatedMultiply(indexes.size.toLong(), JOIN_STATE_OVERHEAD_BYTES),
            saturatedMultiply(indexEntries, JOIN_INDEX_ENTRY_BYTES),
        )
    }

    fun snapshot(): QueryJoinSnapshot =
        QueryJoinSnapshot(Collections.unmodifiableMap(indexes.mapValues { (_, index) -> index.snapshot() }))
}

private fun saturatedMultiply(left: Long, right: Long): Long =
    if (left < 0L || right < 0L || left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
