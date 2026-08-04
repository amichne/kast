package io.github.amichne.kast.idea

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object SemanticPathContentIdentity {
    fun resolve(path: Path, isCancelled: () -> Boolean = { false }): String = when {
        Files.isRegularFile(path) -> "file:${file(path, isCancelled)}"
        Files.isDirectory(path) -> "directory:${directory(path, isCancelled)}"
        else -> "missing"
    }

    fun file(path: Path, isCancelled: () -> Boolean = { false }): String =
        MessageDigest.getInstance("SHA-256").run {
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    requireActive(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    update(buffer, 0, count)
                }
            }
            digest().toHexString()
        }

    private fun directory(root: Path, isCancelled: () -> Boolean): String {
        val files = Files.walk(root).use { paths ->
            paths.peek { requireActive(isCancelled) }
                .filter(Files::isRegularFile)
                .map { path -> path.toAbsolutePath().normalize() }
                .sorted(compareBy { path -> stableRelativePath(root, path) })
                .toList()
        }
        return MessageDigest.getInstance("SHA-256").run {
            files.forEach { path ->
                requireActive(isCancelled)
                updateRecord(stableRelativePath(root, path))
                updateRecord(file(path, isCancelled))
            }
            digest().toHexString()
        }
    }

    fun requireActive(isCancelled: () -> Boolean) {
        ProgressManager.checkCanceled()
        if (isCancelled() || Thread.currentThread().isInterrupted) throw ProcessCanceledException()
    }

    private fun stableRelativePath(root: Path, path: Path): String =
        root.toAbsolutePath().normalize().relativize(path).toString().replace('\\', '/')

    private fun MessageDigest.updateRecord(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ))
        update(bytes)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }
}
