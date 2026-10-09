package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.ResultLimit

/**
 * Read-only evidence from pending terminal TRACE groups. These rows are a bounded, qualified proof subset, not emitted
 * execution rows or a completed grouping. Reading them must preserve the checkpoint, original coverage, and counts.
 */
interface QueryCheckpointPartialSymbols : QueryCheckpoint {
    fun partialSymbols(rowLimit: ResultLimit, byteLimit: QueryByteLimit): QueryRows.Symbols
}
