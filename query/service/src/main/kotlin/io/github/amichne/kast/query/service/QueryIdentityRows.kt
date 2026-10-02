package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryExecutionRejection
import io.github.amichne.kast.query.contract.QuerySetOperator
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.query.contract.QueryTextMatchFailure
import io.github.amichne.kast.query.contract.merge
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import io.github.amichne.kast.symbol.contract.SymbolDescription

internal enum class QueryIdentityRowFailure {
    CONFLICTING_DESCRIPTION,
    CONFLICTING_SOURCE,
    TEXT_MATCH_LIMIT_EXCEEDED,
}

internal fun QueryIdentityRowFailure.executionRejection(): QueryExecutionRejection =
    when (this) {
        QueryIdentityRowFailure.CONFLICTING_DESCRIPTION,
        QueryIdentityRowFailure.CONFLICTING_SOURCE -> QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION
        QueryIdentityRowFailure.TEXT_MATCH_LIMIT_EXCEEDED -> QueryExecutionRejection.TEXT_MATCH_LIMIT_EXCEEDED
    }

private fun QueryTextMatchFailure.identityFailure(): QueryIdentityRowFailure =
    when (this) {
        QueryTextMatchFailure.ITEM_LIMIT_EXCEEDED -> QueryIdentityRowFailure.TEXT_MATCH_LIMIT_EXCEEDED
    }

/** Request-local grouping of proven rows; the query evaluator still owns task order and budgets. */
internal class QueryIdentityRows(restored: Map<ExactQueryStage, Map<CanonicalSymbolId, QuerySymbol>>) {
    private val rows = restored.mapValues { (_, values) -> LinkedHashMap(values) }.toMutableMap()
    private val rightRows = mutableMapOf<ExactQueryStage.Set, Map<CanonicalSymbolId, QuerySymbol>>()

    fun acceptDistinct(
        stage: ExactQueryStage.Distinct,
        incoming: QuerySymbol,
    ): Refinement<Unit, QueryIdentityRowFailure> {
        val distinctRows = rows.getOrPut(stage) { linkedMapOf() }
        val id = CanonicalSymbolId.from(incoming.selector)
        val first = distinctRows.putIfAbsent(id, incoming) ?: return Refinement.Refined(Unit)
        val matches =
            when (val merged = first.textMatches.merge(incoming.textMatches)) {
                is Refinement.Refined -> merged.value
                is Refinement.Rejected -> return Refinement.Rejected(merged.failure.identityFailure())
            }
        if (!matches.belongsTo(first.selector)) {
            return Refinement.Rejected(QueryIdentityRowFailure.CONFLICTING_DESCRIPTION)
        }
        // Relation arrival remains first-row evidence; independent lexical proof is cumulative.
        distinctRows[id] = first.copy(textMatches = matches)
        return Refinement.Refined(Unit)
    }

    fun flushDistinct(stage: ExactQueryStage.Distinct): List<QuerySymbol> =
        rows.remove(stage)?.values?.toList().orEmpty()

    fun acceptSet(stage: ExactQueryStage.Set, incoming: QuerySymbol): Refinement<Unit, QueryIdentityRowFailure> {
        val right =
            when (val grouped = groupedRight(stage)) {
                is Refinement.Refined -> grouped.value
                is Refinement.Rejected -> return grouped
            }
        val id = CanonicalSymbolId.from(incoming.selector)
        val selected =
            when (stage.operator) {
                QuerySetOperator.INTERSECTION ->
                    right[id]?.let { mergeRows(incoming, it) } ?: return Refinement.Refined(Unit)
                QuerySetOperator.DIFFERENCE ->
                    incoming.takeIf { id !in right }?.let { Refinement.Refined(it) } ?: return Refinement.Refined(Unit)
            }
        return when (selected) {
            is Refinement.Refined -> mergeInto(rows.getOrPut(stage) { linkedMapOf() }, selected.value)
            is Refinement.Rejected -> selected
        }
    }

    fun flushSet(stage: ExactQueryStage.Set): Refinement<List<QuerySymbol>, QueryIdentityRowFailure> {
        val aggregated = LinkedHashMap(rows[stage].orEmpty())
        rows.remove(stage)
        return Refinement.Refined(aggregated.values.toList())
    }

    fun snapshot(): Map<ExactQueryStage, Map<CanonicalSymbolId, QuerySymbol>> = rows.mapValues { (_, values) ->
        values.toMap()
    }

    private fun groupedRight(
        stage: ExactQueryStage.Set
    ): Refinement<Map<CanonicalSymbolId, QuerySymbol>, QueryIdentityRowFailure> {
        rightRows[stage]?.let {
            return Refinement.Refined(it)
        }
        val grouped = linkedMapOf<CanonicalSymbolId, QuerySymbol>()
        for (row in stage.right.symbols) {
            when (val merged = mergeInto(grouped, row)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return merged
            }
        }
        rightRows[stage] = grouped
        return Refinement.Refined(grouped)
    }

    private fun mergeInto(
        destination: MutableMap<CanonicalSymbolId, QuerySymbol>,
        incoming: QuerySymbol,
    ): Refinement<Unit, QueryIdentityRowFailure> {
        val id = CanonicalSymbolId.from(incoming.selector)
        val previous = destination[id]
        val merged =
            if (previous == null) {
                incoming
            } else {
                when (val result = mergeRows(previous, incoming)) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            }
        destination[id] = merged
        return Refinement.Refined(Unit)
    }
}

/** Preserve both proven evidence sets when a semi-join retains one left row. */
internal fun mergeRows(
    first: QuerySymbol,
    second: QuerySymbol,
): Refinement<QuerySymbol, QueryIdentityRowFailure> {
    if (!sameDescription(first.description, second.description)) {
        return Refinement.Rejected(QueryIdentityRowFailure.CONFLICTING_DESCRIPTION)
    }
    val source =
        when {
            first.source == second.source -> first.source
            first.source is QuerySymbolSource.Pending -> second.source
            second.source is QuerySymbolSource.Pending -> first.source
            first.source is QuerySymbolSource.Returned && second.source is QuerySymbolSource.Withheld -> first.source
            first.source is QuerySymbolSource.Withheld && second.source is QuerySymbolSource.Returned -> second.source
            first.source is QuerySymbolSource.Returned && second.source is QuerySymbolSource.Rejected -> first.source
            first.source is QuerySymbolSource.Rejected && second.source is QuerySymbolSource.Returned -> second.source
            else -> return Refinement.Rejected(QueryIdentityRowFailure.CONFLICTING_SOURCE)
        }
    val matches =
        when (val merged = first.textMatches.merge(second.textMatches)) {
            is Refinement.Refined -> merged.value
            is Refinement.Rejected -> return Refinement.Rejected(merged.failure.identityFailure())
        }
    return Refinement.Refined(
        first.copy(
            connections = (first.connections + second.connections).distinct().sorted(),
            source = source,
            arrival = first.arrival.merge(second.arrival),
            walkArrival = first.walkArrival.merge(second.walkArrival),
            textMatches = matches,
        )
    )
}

private fun sameDescription(first: SymbolDescription, second: SymbolDescription): Boolean {
    if (first.file != second.file) return false
    if (first.range != second.range) return false
    if (first.name != second.name) return false
    if (first.qualifiedIdentity != second.qualifiedIdentity) return false
    if (first.kind != second.kind) return false
    if (first.signature != second.signature) return false
    return first.compilerIdentity == second.compilerIdentity
}
