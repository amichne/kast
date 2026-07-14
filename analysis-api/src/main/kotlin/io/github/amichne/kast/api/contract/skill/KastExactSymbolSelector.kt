package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.SymbolKind
import kotlinx.serialization.Serializable

@Serializable
data class KastExactSymbolSelector(
    val fqName: String,
    val declarationFile: String,
    val declarationStartOffset: Int,
    val kind: SymbolKind? = null,
    val containingType: String? = null,
)
