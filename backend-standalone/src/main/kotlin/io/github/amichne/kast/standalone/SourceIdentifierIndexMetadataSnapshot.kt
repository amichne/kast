package io.github.amichne.kast.standalone

internal data class SourceIdentifierIndexMetadataSnapshot(
    val moduleNameByPath: Map<String, String>,
    val packageByPath: Map<String, String>,
    val importsByPath: Map<String, List<String>>,
    val wildcardImportPackagesByPath: Map<String, List<String>>,
)
