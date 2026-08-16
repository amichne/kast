package io.github.amichne.kast.indexer

import io.github.amichne.kast.runtime.composition.KastRuntimeDispatch
import io.github.amichne.kast.runtime.composition.KastRuntimeDispatchOperations

/** Isolated outer host for one target-only runtime composition. */
class KastIndexerHost(
    private val runtime: KastRuntimeDispatchOperations,
) {
    /**
     * Proof transition: `String -> KastRuntimeDispatch`.
     *
     * Establishes canonical request admission and exact target operation routing through the sole
     * runtime composition. Expected transport and semantic failures remain closed data. Raw
     * documents may cross only this outer host frame.
     */
    suspend fun dispatch(document: String): KastRuntimeDispatch = runtime.dispatch(document)
}
