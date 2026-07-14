package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.reference.SymbolReferencePage
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore

internal fun interface ReferenceIndexLookup {
    fun referencesTo(
        target: ExactReferenceTarget,
        offset: NonNegativeInt,
        maxResults: PositiveInt,
    ): IndexedReferenceLookupResult

    companion object {
        val Unavailable: ReferenceIndexLookup = ReferenceIndexLookup { _, _, _ ->
            IndexedReferenceLookupResult.NotReady
        }
    }
}

internal sealed interface IndexedReferenceLookupResult {
    data object NotReady : IndexedReferenceLookupResult

    data class IdentityUnavailable(
        val generation: SourceIndexGeneration,
    ) : IndexedReferenceLookupResult

    data class Ready(
        val page: SymbolReferencePage,
        val generation: SourceIndexGeneration,
    ) : IndexedReferenceLookupResult
}

internal class DiagnosticsReferenceIndexLookup(
    private val diagnostics: KastDiagnosticsService,
    private val store: SqliteSourceIndexStore,
) : ReferenceIndexLookup {
    override fun referencesTo(
        target: ExactReferenceTarget,
        offset: NonNegativeInt,
        maxResults: PositiveInt,
    ): IndexedReferenceLookupResult {
        if (diagnostics.snapshot().indexSummary.state != KastIndexState.READY) {
            return IndexedReferenceLookupResult.NotReady
        }
        val generatedPage = store.generatedReferencePageToExactSymbol(target, offset, maxResults)
        if (!generatedPage.exactIdentityAvailable) {
            return IndexedReferenceLookupResult.IdentityUnavailable(generatedPage.generation)
        }
        return IndexedReferenceLookupResult.Ready(
            page = generatedPage.page,
            generation = generatedPage.generation,
        )
    }
}
