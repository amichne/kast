package io.github.amichne.kast.api

import kotlinx.serialization.Serializable

@Serializable
data class Symbol(
    val fqName: String,
    val kind: SymbolKind,
    val location: Location,
    val type: String? = null,
    val containingDeclaration: String? = null,
    val supertypes: List<String>? = null,
    val visibility: SymbolVisibility? = null,
    val declarationScope: DeclarationScope? = null,
)
