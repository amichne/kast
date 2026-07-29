package io.github.amichne.kast.idea

import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal fun buildFileInventoryEntries(
    ownerModuleNamesByPath: Map<String, Set<IdeaWorkspaceModuleIdentity>>,
    workspaceRoot: Path,
    isCancelled: () -> Boolean,
    sourceSetForPath: (String) -> String?,
): List<FileInventoryEntry> = buildList {
    for ((filePath, ownerModuleNames) in ownerModuleNamesByPath) {
        if (isCancelled()) return emptyList()
        val path = Path.of(filePath)
        if (!Files.isRegularFile(path)) continue
        val sourceSet = sourceSetForPath(filePath)
        add(
            FileInventoryEntry(
                path = filePath,
                lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
                contentHash = hashFile(path),
                moduleName = ownerModuleNames
                    .minOrNull()
                    ?.let { owner ->
                        indexedModuleNameForFilePath(
                            ideaModuleName = owner.value,
                            filePath = filePath,
                            workspaceRoot = workspaceRoot,
                            sourceSet = sourceSet,
                        )
                    },
                sourceSet = sourceSet,
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
