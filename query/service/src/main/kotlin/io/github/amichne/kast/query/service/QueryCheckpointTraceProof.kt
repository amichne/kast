package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryGroupingEvidence
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolSource

/** Immutable proof selection; grouping and ordinary continuation remain owned by the interpreter. */
internal fun PipelineCheckpoint.traceProof(rowLimit: ResultLimit, byteLimit: QueryByteLimit): QueryRows.Symbols {
    val selected = mutableListOf<QuerySymbol>()
    var remainingBytes = byteLimit.value
    for ((stage, grouped) in identityRows) {
        val output = stage.terminalTraceOutput() ?: continue
        for (row in grouped.values) {
            if (!row.projectable(output)) continue
            if (selected.size >= rowLimit.value) return QueryRows.Symbols.of(selected)
            val bytes = row.projectedUtf8Size()
            if (bytes > remainingBytes) return QueryRows.Symbols.of(selected)
            selected += row
            remainingBytes -= bytes
        }
    }
    return QueryRows.Symbols.of(selected)
}

private fun ExactQueryStage.terminalTraceOutput(): QueryOutputSyntax.Symbols? {
    if (this !is ExactQueryStage.Distinct || evidence != QueryGroupingEvidence.ALL_ARRIVALS) return null
    val emit = next as? ExactQueryStage.Emit ?: return null
    return emit.output as? QueryOutputSyntax.Symbols
}

private fun QuerySymbol.projectable(output: QueryOutputSyntax.Symbols): Boolean =
    QuerySymbolField.SOURCE !in output.fields.values || source !is QuerySymbolSource.Pending
