package io.github.amichne.kast.indexstore.api.reference

/**
 * A row from the `symbol_references` table.
 */
data class SymbolReferenceRow(
    val sourcePath: String,
    val sourceOffset: Int,
    val sourceFqName: String? = null,
    val targetFqName: String,
    val targetPath: String?,
    val targetOffset: Int?,
    val edgeKind: EdgeKind = EdgeKind.UNKNOWN,
)
