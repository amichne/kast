package io.github.amichne.kast.indexstore.api.index

/**
 * Backend-neutral snapshot of the persisted source identifier index.
 */
data class SourceIndexSnapshot(
    val candidatePathsByIdentifier: Map<String, List<WorkspaceSourcePath>>,
    val moduleByPath: Map<WorkspaceSourcePath, SourceIndexModuleIdentity>,
    val packageByPath: Map<WorkspaceSourcePath, String>,
    val importsByPath: Map<WorkspaceSourcePath, List<String>>,
    val wildcardImportPackagesByPath: Map<WorkspaceSourcePath, List<String>>,
)
