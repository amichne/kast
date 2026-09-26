package io.github.amichne.kast.query.service

import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryBindingName
import io.github.amichne.kast.query.contract.QueryJoinInput
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import java.util.Collections

private const val JOIN_STATE_OVERHEAD_BYTES = 512L
private const val JOIN_ROW_OVERHEAD_BYTES = 512L
private const val JOIN_INDEX_ENTRY_BYTES = 128L
private const val JOIN_EVIDENCE_MULTIPLIER = 4L

/** A named input is visible to a later join only after the upstream stream has crossed its barrier. */
internal sealed interface QueryBoundRows {
    val values: List<QuerySymbol>

    data class Building(override val values: List<QuerySymbol>) : QueryBoundRows

    data class Complete(override val values: List<QuerySymbol>) : QueryBoundRows

    data class Incomplete(
        override val values: List<QuerySymbol>,
        val limitations: Set<QueryLimitation>,
    ) : QueryBoundRows
}

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

internal data class QueryJoinSnapshot(
    val bindings: Map<QueryBindingName, QueryBoundRows>,
    val indexes: Map<ExactQueryStage.Join, QueryJoinIndex>,
)

internal enum class QueryBindAdmission {
    ACCEPTED,
    ROW_LIMIT,
    BYTE_LIMIT,
    CONTRACT_VIOLATION,
}

internal enum class QueryJoinBuild {
    BUILT,
    COMPLETE,
    BYTE_LIMIT,
    INPUT_NOT_SEALED,
}

/** Request-local, bounded build state; snapshots contain no compiler or host objects. */
internal class QueryJoins(restored: QueryJoinSnapshot = QueryJoinSnapshot(emptyMap(), emptyMap())) {
    private val bindings = restored.bindings.mapValues { (_, rows) -> rows.detached() }.toMutableMap()
    private val indexes = restored.indexes.mapValues { (_, index) -> MutableJoinIndex(index) }.toMutableMap()

    fun bind(
        name: QueryBindingName,
        row: QuerySymbol,
        maximumRows: Int,
        maximumBytes: Long,
    ): QueryBindAdmission {
        val current = bindings[name]
        if (current is QueryBoundRows.Complete || current is QueryBoundRows.Incomplete) {
            return QueryBindAdmission.CONTRACT_VIOLATION
        }
        val rows = current?.values.orEmpty()
        if (rows.size >= maximumRows) return QueryBindAdmission.ROW_LIMIT
        val added =
            saturatedAdd(JOIN_ROW_OVERHEAD_BYTES, saturatedMultiply(row.projectedUtf8Size(), JOIN_EVIDENCE_MULTIPLIER))
        val overhead = if (current == null) JOIN_STATE_OVERHEAD_BYTES else 0L
        if (saturatedAdd(retainedBytes(), saturatedAdd(added, overhead)) > maximumBytes) {
            return QueryBindAdmission.BYTE_LIMIT
        }
        bindings[name] = QueryBoundRows.Building(rows + row)
        return QueryBindAdmission.ACCEPTED
    }

    fun seal(name: QueryBindingName, limitations: Set<QueryLimitation>): List<QuerySymbol>? {
        val current = bindings[name]
        if (current is QueryBoundRows.Complete || current is QueryBoundRows.Incomplete) return null
        val rows = current?.values.orEmpty().frozenSymbols()
        bindings[name] =
            if (limitations.isEmpty()) {
                QueryBoundRows.Complete(rows)
            } else {
                QueryBoundRows.Incomplete(rows, Collections.unmodifiableSet(limitations.toSet()))
            }
        return rows
    }

    fun rightRows(stage: ExactQueryStage.Join): List<QuerySymbol>? =
        when (val input = stage.right) {
            is QueryJoinInput.Named ->
                when (val rows = bindings[input.name]) {
                    is QueryBoundRows.Complete -> rows.values
                    is QueryBoundRows.Incomplete -> rows.values
                    is QueryBoundRows.Building,
                    null -> null
                }
            is QueryJoinInput.Retained -> input.result.symbols
        }

    fun namedRightProvesAbsence(stage: ExactQueryStage.Join): Boolean =
        when (val input = stage.right) {
            is QueryJoinInput.Named -> bindings[input.name] is QueryBoundRows.Complete
            is QueryJoinInput.Retained -> false
        }

    fun buildNext(stage: ExactQueryStage.Join, maximumBytes: Long): QueryJoinBuild {
        val right = rightRows(stage) ?: return QueryJoinBuild.INPUT_NOT_SEALED
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
        val right = rightRows(stage) ?: return null
        val index = indexes[stage] ?: MutableJoinIndex(0, linkedMapOf())
        if (index.consumed != right.size) return null
        return index.positions[CanonicalSymbolId.from(left.selector)].orEmpty()
    }

    fun retainedBytes(): Long {
        val boundBytes =
            bindings.values.fold(0L) { total, rows ->
                saturatedAdd(
                    total,
                    rows.values.fold(JOIN_STATE_OVERHEAD_BYTES) { size, row ->
                        saturatedAdd(
                            size,
                            saturatedAdd(
                                JOIN_ROW_OVERHEAD_BYTES,
                                saturatedMultiply(row.projectedUtf8Size(), JOIN_EVIDENCE_MULTIPLIER),
                            ),
                        )
                    },
                )
            }
        val indexEntries =
            indexes.values.fold(0L) { total, index ->
                index.positions.values.fold(total) { count, positions -> saturatedAdd(count, positions.size.toLong()) }
            }
        return saturatedAdd(
            boundBytes,
            saturatedAdd(
                saturatedMultiply(indexes.size.toLong(), JOIN_STATE_OVERHEAD_BYTES),
                saturatedMultiply(indexEntries, JOIN_INDEX_ENTRY_BYTES),
            ),
        )
    }

    fun snapshot(): QueryJoinSnapshot =
        QueryJoinSnapshot(
            Collections.unmodifiableMap(bindings.mapValues { (_, rows) -> rows.detached() }),
            Collections.unmodifiableMap(indexes.mapValues { (_, index) -> index.snapshot() }),
        )
}

private fun QueryBoundRows.detached(): QueryBoundRows =
    when (this) {
        is QueryBoundRows.Building -> copy(values = values.frozenSymbols())
        is QueryBoundRows.Complete -> copy(values = values.frozenSymbols())
        is QueryBoundRows.Incomplete ->
            copy(values = values.frozenSymbols(), limitations = Collections.unmodifiableSet(limitations.toSet()))
    }

private fun List<QuerySymbol>.frozenSymbols(): List<QuerySymbol> =
    Collections.unmodifiableList(
        map { symbol -> symbol.copy(connections = Collections.unmodifiableList(symbol.connections.toList())) }
    )

private fun saturatedMultiply(left: Long, right: Long): Long =
    if (left < 0L || right < 0L || left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
