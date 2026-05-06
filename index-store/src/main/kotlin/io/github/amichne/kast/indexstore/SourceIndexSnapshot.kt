package io.github.amichne.kast.indexstore

/**
 * Backend-neutral snapshot of the persisted source identifier index.
 */
data class SourceIndexSnapshot(
    val candidatePathsByIdentifier: Map<String, List<String>>,
    val moduleNameByPath: Map<String, String>,
    val packageByPath: Map<String, String>,
    val importsByPath: Map<String, List<String>>,
    val wildcardImportPackagesByPath: Map<String, List<String>>,
)
