package io.github.amichne.kast.indexstore.api.reference

import io.github.amichne.kast.api.contract.NonNegativeInt

data class SymbolReferencePage(
    val references: List<SymbolReferenceRow>,
    val nextOffset: NonNegativeInt?,
)
