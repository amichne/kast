package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal fun interface ReferenceIndexLookup {
    fun referencesTo(targetFqName: String): IndexedReferenceLookupResult

    companion object {
        val Unavailable: ReferenceIndexLookup = ReferenceIndexLookup {
            IndexedReferenceLookupResult.NotReady
        }
    }
}

internal sealed interface IndexedReferenceLookupResult {
    data object NotReady : IndexedReferenceLookupResult

    data class Ready(
        val references: List<SymbolReferenceRow>,
    ) : IndexedReferenceLookupResult
}

internal class DiagnosticsReferenceIndexLookup(
    private val diagnostics: KastDiagnosticsService,
    private val store: SqliteSourceIndexStore,
) : ReferenceIndexLookup {
    override fun referencesTo(targetFqName: String): IndexedReferenceLookupResult =
        if (diagnostics.snapshot().indexSummary.state == KastIndexState.READY) {
            IndexedReferenceLookupResult.Ready(store.referencesToSymbol(targetFqName))
        } else {
            IndexedReferenceLookupResult.NotReady
        }
}
