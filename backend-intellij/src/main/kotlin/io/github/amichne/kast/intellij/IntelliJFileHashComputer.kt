package io.github.amichne.kast.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.charset.StandardCharsets

/**
 * Computes file hashes using IntelliJ's VFS and Document APIs.
 *
 * Prioritizes unsaved Document text over disk content, ensuring hash computation
 * reflects the current in-memory state as seen by the IDE.
 */
internal object IntelliJFileHashComputer {
    /**
     * Computes hashes for the given file paths.
     *
     * For each file:
     * 1. If there's an unsaved Document, use its text
     * 2. Otherwise, read from VirtualFile
     * 3. Fallback to error if file doesn't exist
     *
     * @param filePaths Collection of absolute file paths
     * @return List of FileHash objects with computed hashes
     */
    suspend fun currentHashes(filePaths: Collection<String>): List<FileHash> = readAction {
        val fileDocumentManager = FileDocumentManager.getInstance()
        val vfsManager = VirtualFileManager.getInstance()

        filePaths
            .map { NormalizedPath.parse(it).value }
            .distinct()
            .sorted()
            .map { filePath ->
                val virtualFile = vfsManager.findFileByUrl("file://$filePath")
                    ?: throw IllegalStateException("File not found: $filePath")

                val content = getFileContent(virtualFile, fileDocumentManager)
                FileHash(filePath = filePath, hash = FileHashing.sha256(content))
            }
    }

    /**
     * Gets file content, preferring unsaved Document text over VFS content.
     */
    private fun getFileContent(
        virtualFile: VirtualFile,
        fileDocumentManager: FileDocumentManager,
    ): String {
        // Check for unsaved Document first
        val document = fileDocumentManager.getCachedDocument(virtualFile)
        if (document != null) {
            // Document exists - use its current text (may be unsaved)
            return document.text
        }

        // No Document - read from VirtualFile
        return String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
    }
}
