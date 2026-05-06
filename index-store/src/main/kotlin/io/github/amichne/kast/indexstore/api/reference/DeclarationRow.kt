package io.github.amichne.kast.indexstore.api.reference

data class DeclarationRow(
    val fqName: String,
    val kind: DeclarationKind,
    val visibility: DeclarationVisibility,
    val filePath: String,
    val declarationOffset: Int?,
    val modulePath: String?,
    val sourceSet: String?,
)
