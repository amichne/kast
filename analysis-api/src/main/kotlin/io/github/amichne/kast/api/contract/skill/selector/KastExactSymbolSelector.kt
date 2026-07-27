package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class KastExactSymbolSelector(
    @DocField(description = "Compiler-resolved fully-qualified declaration name.")
    val fqName: String,
    @DocField(description = "Canonical absolute path to the declaration source file.")
    val declarationFile: String,
    @DocField(description = "Zero-based declaration start offset in the canonical source file.")
    val declarationStartOffset: Int,
    @DocField(description = "Expected declaration kind when the caller has kind evidence.")
    val kind: SymbolKind? = null,
    @DocField(description = "Expected fully-qualified containing type for a member declaration.")
    val containingType: String? = null,
)
