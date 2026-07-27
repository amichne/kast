package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class ReferenceOccurrence(
    @DocField(description = "Exact source range of this reference occurrence.")
    val location: Location,
    @DocField(description = "Semantic evidence for the declaration containing this occurrence.")
    val containingSymbol: ContainingSymbolEvidence,
)
