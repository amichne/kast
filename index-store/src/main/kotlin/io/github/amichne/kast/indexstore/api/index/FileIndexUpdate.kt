package io.github.amichne.kast.indexstore.api.index

/**
 * Represents all identifier-index data for a single file.
 */
data class FileIndexUpdate(
    val path: String,
    val identifiers: Set<String>,
    val packageName: String?,
    val modulePath: String?,
    val sourceSet: String?,
    val imports: Set<String>,
    val wildcardImports: Set<String>,
)
