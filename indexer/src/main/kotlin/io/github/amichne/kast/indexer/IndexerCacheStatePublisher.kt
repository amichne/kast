package io.github.amichne.kast.indexer

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal enum class IndexerCacheState(val wireName: String) {
    REFRESHING("refreshing"),
    SMART("smart"),
    REBUILD_REQUIRED("rebuild-required"),
}

internal sealed interface IndexerCacheStatePublication {
    data object Published : IndexerCacheStatePublication
    data object Rejected : IndexerCacheStatePublication
}

/** Publishes sidecar readiness only to the exact cache marker admitted by the launcher. */
internal object IndexerCacheStatePublisher {
    private const val PROPERTY = "kast.cache.state.path"

    fun publish(state: IndexerCacheState): IndexerCacheStatePublication {
        val raw = System.getProperty(PROPERTY)
            ?: return IndexerCacheStatePublication.Rejected
        val path = try {
            Path.of(raw)
        } catch (_: RuntimeException) {
            return IndexerCacheStatePublication.Rejected
        }
        if (
            !path.isAbsolute ||
            path.normalize() != path ||
            path.fileName.toString() != "cache-state" ||
            Files.isSymbolicLink(path)
        ) {
            return IndexerCacheStatePublication.Rejected
        }
        val parent = try {
            path.parent?.toRealPath()
        } catch (_: IOException) {
            return IndexerCacheStatePublication.Rejected
        } catch (_: SecurityException) {
            return IndexerCacheStatePublication.Rejected
        }
        if (
            parent == null ||
            parent != path.parent ||
            !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
        ) {
            return IndexerCacheStatePublication.Rejected
        }
        val staging = try {
            Files.createTempFile(parent, ".cache-state-", ".partial")
        } catch (_: IOException) {
            return IndexerCacheStatePublication.Rejected
        } catch (_: SecurityException) {
            return IndexerCacheStatePublication.Rejected
        }
        return try {
            Files.writeString(staging, state.wireName + "\n")
            Files.move(
                staging,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            IndexerCacheStatePublication.Published
        } catch (_: AtomicMoveNotSupportedException) {
            IndexerCacheStatePublication.Rejected
        } catch (_: IOException) {
            IndexerCacheStatePublication.Rejected
        } catch (_: SecurityException) {
            IndexerCacheStatePublication.Rejected
        } finally {
            try {
                Files.deleteIfExists(staging)
            } catch (_: IOException) {
                // An unpublished state marker carries no authority.
            }
        }
    }
}
