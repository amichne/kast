package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.client.RemoteIndexConfig
import io.github.amichne.kast.indexstore.sourceIndexDatabasePath
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class SourceIndexHydrator(
    private val openRemote: (URI) -> java.io.InputStream = { uri ->
        when (uri.scheme?.lowercase()) {
            null, "", "file" -> Files.newInputStream(Path.of(uri))
            "http", "https" -> uri.toURL().openStream()
            else -> error("Unsupported remote source index URI scheme: ${uri.scheme}")
        }
    },
) {
    fun hydrate(
        workspaceRoot: Path,
        remote: RemoteIndexConfig,
    ): Boolean {
        val remoteUrl = remote.sourceIndexUrl?.takeIf(String::isNotBlank) ?: return false
        if (!remote.enabled) return false

        val target = sourceIndexDatabasePath(workspaceRoot)
        if (Files.isRegularFile(target)) return false

        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "${target.fileName}.hydrate-", ".tmp")
        try {
            openRemote(URI(remoteUrl)).use { input ->
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
