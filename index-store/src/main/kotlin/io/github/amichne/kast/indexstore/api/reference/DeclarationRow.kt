package io.github.amichne.kast.indexstore.api.reference

data class DeclarationRow(
    val fqName: String,
    val kind: DeclarationKind,
    val visibility: DeclarationVisibility,
    val filePath: String,
    val declarationOffset: Int?,
    val modulePath: String?,
    val sourceSet: String?,
    /**
     * Fully-qualified names of the **direct** supertypes declared by this type.
     * Populated during the Phase-2 declaration scan via PSI supertype resolution.
     * Defaults to an empty list for non-type declarations (functions, properties, etc.)
     * and for declarations scanned before this field was introduced.
     */
    val supertypes: List<String> = emptyList(),
)
