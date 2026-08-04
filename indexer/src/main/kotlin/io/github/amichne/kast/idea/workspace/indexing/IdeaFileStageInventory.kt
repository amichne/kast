package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.nio.file.Files
import java.nio.file.Path

@JvmInline
internal value class AdmittedWorkspaceContentIdentity private constructor(val value: String) {
    companion object {
        fun hash(canonicalRecords: Iterable<String>): AdmittedWorkspaceContentIdentity =
            AdmittedWorkspaceContentIdentity(FileHashing.sha256(canonicalRecords.joinToString("\n")))
    }
}

internal data class WorkspaceIndexingCandidate(
    val gradleModel: IdeaGradleProjectLoadBridge.GradleWorkspaceModel,
    val scope: WorkspaceIndexingScope,
    val ownerModuleNamesByPath: Map<WorkspaceSourcePath, Set<IdeaWorkspaceModuleIdentity>>,
    val inventoryEntries: List<FileInventoryEntry>,
) {
    val admittedContentIdentity: AdmittedWorkspaceContentIdentity = AdmittedWorkspaceContentIdentity.hash(
        inventoryEntries
            .sortedBy { entry -> entry.path.relative.value }
            .map { entry ->
                buildString {
                    append(entry.path.relative.value).append('|')
                    append(entry.contentHash.value).append('|')
                    append(
                        ownerModuleNamesByPath[entry.path]
                            .orEmpty()
                            .map(IdeaWorkspaceModuleIdentity::value)
                            .sorted()
                            .joinToString(","),
                    )
                }
            },
    )
}

internal fun buildFileInventoryEntries(
    ownerModuleNamesByPath: Map<WorkspaceSourcePath, Set<IdeaWorkspaceModuleIdentity>>,
    isCancelled: () -> Boolean,
    sourceSetForPath: (WorkspaceSourcePath) -> GradleSourceSetName?,
): List<FileInventoryEntry> = buildList {
    for ((filePath, ownerModuleNames) in ownerModuleNamesByPath) {
        if (isCancelled()) throw ProcessCanceledException()
        val path = filePath.absolute.value.toJavaPath()
        if (!Files.isRegularFile(path)) continue
        val sourceSet = sourceSetForPath(filePath)
        add(
            FileInventoryEntry(
                path = filePath,
                lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
                contentHash = hashFile(path, isCancelled),
                module = ownerModuleNames
                    .minOrNull()
                    ?.let { owner ->
                        indexedModuleIdentityForFilePath(
                            ideaModule = owner,
                            filePath = filePath,
                            sourceSet = sourceSet,
                        )
                    },
            ),
        )
    }
}

private fun hashFile(path: Path, isCancelled: () -> Boolean): FileContentHash =
    FileContentHash.parse(SemanticPathContentIdentity.file(path, isCancelled))
