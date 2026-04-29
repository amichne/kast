package io.github.amichne.kast.indexstore

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

/**
 * Write-through boundary used by hot in-memory indexes without depending on a concrete store.
 */
interface SourceIndexWriter {
    fun saveFileIndex(update: FileIndexUpdate)

    fun removeFile(path: String)
}

/**
 * A row from the `symbol_references` table.
 */
data class SymbolReferenceRow(
    val sourcePath: String,
    val sourceOffset: Int,
    val targetFqName: String,
    val targetPath: String?,
    val targetOffset: Int?,
)

fun splitModuleName(moduleName: String?): Pair<String?, String?> {
    if (moduleName == null) return null to null
    val bracketIndex = moduleName.indexOf('[')
    if (bracketIndex < 0) return moduleName to null
    val closingIndex = moduleName.indexOf(']', bracketIndex + 1)
    if (closingIndex < 0) return moduleName to null
    return moduleName.substring(0, bracketIndex) to moduleName.substring(bracketIndex + 1, closingIndex)
}
