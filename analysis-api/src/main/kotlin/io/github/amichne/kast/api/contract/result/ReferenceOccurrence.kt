package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.Location
import kotlinx.serialization.Serializable

@Serializable
data class ReferenceOccurrence(
    val location: Location,
    val containingSymbol: ContainingSymbolEvidence,
)
