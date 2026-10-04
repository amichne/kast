package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbolSource
import io.github.amichne.kast.query.contract.QueryWalkArrival

internal inline fun <Input, Output : Any> Iterable<Input>.mapProjected(
    transform: (Input) -> Output?
): QueryProjection.Legacy<Output> {
    val values = mutableListOf<Output>()
    for (input in this) values += transform(input) ?: return QueryProjection.Rejected
    return QueryProjection.Projected(values)
}

internal fun <Value> QueryProjection.Legacy<Value>.boundedProjectedOrNull(): BoundedProtocolList<Value>? =
    when (this) {
        is QueryProjection.Projected -> BoundedProtocolList.create(values).refinedForQueryOrNull()
        QueryProjection.Rejected -> null
    }

internal fun <Value, Failure> Refinement<Value, Failure>.refinedForQueryOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }

internal fun retainedRows(
    retained: QueryRetainedResult,
    output: QueryOutputDocument,
    start: Int,
    end: Int,
): QueryRows? =
    when (retained) {
        is QueryRetainedResult.Symbols -> retained.symbolRows(output, start, end)
        is QueryRetainedResult.ValuePaths ->
            if (output == QueryOutputDocument.ValuePaths)
                retained.rows.selectRows((start until end).toList()).refinedForQueryOrNull()
            else null
        is QueryRetainedResult.Occurrences ->
            if (output == QueryOutputDocument.Occurrences)
                QueryRows.Occurrences.of(retained.occurrences.subList(start, end))
            else null
        is QueryRetainedResult.Bindings ->
            if (output == QueryOutputDocument.BindingRows)
                QueryRows.Bindings.of(retained.bindingRows.subList(start, end), retained.mode)
            else null
    }

internal fun identifyRows(
    items: List<QueryResultItemDocument>,
    rowIds: List<QueryResultRowReference>?,
): QueryProjection.Legacy<QueryResultItemDocument> {
    if (rowIds == null) return QueryProjection.Projected(items)
    if (rowIds.isEmpty() && items.all { it is QueryResultItemDocument.ImpactWitness })
        return QueryProjection.Projected(items)
    if (rowIds.size != items.size) return QueryProjection.Rejected
    return QueryProjection.Projected(
        items.mapIndexed { index, item ->
            when (item) {
                is QueryResultItemDocument.ExactSymbol -> item.copy(rowId = rowIds[index])
                is QueryResultItemDocument.ImpactWitness -> item
                is QueryResultItemDocument.ValuePath -> item.copy(rowId = rowIds[index])
                is QueryResultItemDocument.Occurrence -> item.copy(rowId = rowIds[index])
                is QueryResultItemDocument.ReferenceOccurrence -> item.copy(rowId = rowIds[index])
                is QueryResultItemDocument.TraversalRecord -> item.copy(rowId = rowIds[index])
                is QueryResultItemDocument.BindingRow -> item.withRowId(rowIds[index])
            }
        }
    )
}

private fun QueryRetainedResult.Symbols.symbolRows(output: QueryOutputDocument, start: Int, end: Int): QueryRows? {
    when (output) {
        is QueryOutputDocument.Symbols ->
            if (
                QuerySymbolFieldDocument.SOURCE in output.fields.values &&
                    symbols.any { it.source == QuerySymbolSource.Pending }
            )
                return null
        QueryOutputDocument.TraversalRecords ->
            if (symbols.any { (it.walkArrival as? QueryWalkArrival.Proven)?.records?.singleOrNull() == null })
                return null
        QueryOutputDocument.Occurrences,
        QueryOutputDocument.BindingRows,
        QueryOutputDocument.ValuePaths,
        is QueryOutputDocument.ImpactWitness -> return null
    }
    return QueryRows.Symbols.of(symbols.subList(start, end))
}
