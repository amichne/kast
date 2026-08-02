package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal fun buildFileInventoryEntries(
    ownerModuleNamesByPath: Map<WorkspaceSourcePath, Set<IdeaWorkspaceModuleIdentity>>,
    isCancelled: () -> Boolean,
    sourceSetForPath: (WorkspaceSourcePath) -> GradleSourceSetName?,
): List<FileInventoryEntry> = buildList {
    for ((filePath, ownerModuleNames) in ownerModuleNamesByPath) {
        if (isCancelled()) return emptyList()
        val path = filePath.absolute.value.toJavaPath()
        if (!Files.isRegularFile(path)) continue
        val sourceSet = sourceSetForPath(filePath)
        add(
            FileInventoryEntry(
                path = filePath,
                lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
                contentHash = hashFile(path),
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

private fun hashFile(path: Path): FileContentHash {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return FileContentHash.parse(
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) },
    )
}
