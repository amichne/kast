package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.client.RemoteIndexConfig
import io.github.amichne.kast.indexstore.sourceIndexDatabasePath
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val REMOTE_INDEX_CONNECT_TIMEOUT_MILLIS = 5_000
private const val REMOTE_INDEX_READ_TIMEOUT_MILLIS = 15_000
private const val REMOTE_INDEX_MAX_BYTES = 50L * 1024L * 1024L

private fun openRemoteSourceIndex(uri: URI): InputStream =
    when (uri.scheme?.lowercase()) {
        null, "" -> Files.newInputStream(Path.of(uri.path))
        "file" -> Files.newInputStream(Path.of(uri))
        "http", "https" -> openHttpRemoteSourceIndex(uri)
        else -> error("Unsupported remote source index URI scheme: ${uri.scheme}")
    }

private fun openHttpRemoteSourceIndex(uri: URI): InputStream {
    val connection = uri.toURL().openConnection().applyRemoteIndexTimeouts()
    val declaredLength = connection.contentLengthLong
    if (declaredLength > REMOTE_INDEX_MAX_BYTES) {
        closeConnection(connection)
        error(
            "Remote source index is too large: $declaredLength bytes exceeds limit of " +
                "$REMOTE_INDEX_MAX_BYTES bytes"
        )
    }

    return try {
        val input = connection.getInputStream()
        BoundedInputStream(input, REMOTE_INDEX_MAX_BYTES) {
            closeConnection(connection)
        }
    } catch (e: Exception) {
        closeConnection(connection)
        throw e
    }
}

private fun URLConnection.applyRemoteIndexTimeouts(): URLConnection =
    apply {
        connectTimeout = REMOTE_INDEX_CONNECT_TIMEOUT_MILLIS
        readTimeout = REMOTE_INDEX_READ_TIMEOUT_MILLIS
    }

private fun closeConnection(connection: URLConnection) {
    if (connection is HttpURLConnection) {
        connection.disconnect()
    }
}

private class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val onClose: () -> Unit,
) : FilterInputStream(input) {
    private var bytesRead: Long = 0

    override fun read(): Int {
        val value = super.read()
        if (value != -1) {
            bytesRead += 1
            ensureWithinLimit()
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) {
            bytesRead += count.toLong()
            ensureWithinLimit()
        }
        return count
    }

    override fun close() {
        try {
            super.close()
        } finally {
            onClose()
        }
    }

    private fun ensureWithinLimit() {
        if (bytesRead > maxBytes) {
            error("Remote source index exceeds limit of $maxBytes bytes")
        }
    }
}

internal class SourceIndexHydrator(
    private val openRemote: (URI) -> InputStream = ::openRemoteSourceIndex,
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
