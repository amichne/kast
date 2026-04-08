package io.github.amichne.kast.standalone.cache

/**
 * Captures the three categories of file changes detected between two snapshots.
 * Used by [FileManifestSnapshot] and [IncrementalIndexResult] to avoid
 * repeating three parallel list fields.
 */
internal data class FileChangeSet(
    val added: List<String>,
    val modified: List<String>,
    val removed: List<String>,
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && modified.isEmpty() && removed.isEmpty()
}
