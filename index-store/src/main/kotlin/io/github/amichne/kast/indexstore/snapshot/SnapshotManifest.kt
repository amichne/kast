package io.github.amichne.kast.indexstore.snapshot

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class SnapshotManifest(
    val key: SnapshotKey,
    val files: Map<String, GitObjectId>,
    val createdAtEpochMillis: Long,
) {
    init {
        require(createdAtEpochMillis >= 0) { "Snapshot creation time must be non-negative" }
        require(files.keys.all(::isCanonicalRelativePath)) { "Snapshot paths must be canonical repository-relative paths" }
    }
}

@Serializable
data class OverlayManifest(
    val base: SnapshotKey,
    val target: SnapshotKey,
    val tombstones: Set<String>,
    val shards: Map<String, ExtractionShardKey>,
) {
    companion object {
        fun between(base: SnapshotManifest, target: SnapshotManifest): OverlayManifest {
            require(base.key.compatibility == target.key.compatibility) { "Snapshot overlay requires exact compatibility" }
            val tombstones = base.files.keys.minus(target.files.keys).toSortedSet()
            val shards = target.files
                .filter { (path, oid) -> base.files[path] != oid }
                .toSortedMap()
                .mapValues { (_, oid) -> ExtractionShardKey(target.key.compatibility, oid) }
            return OverlayManifest(base.key, target.key, tombstones, shards)
        }
    }
}

object RepositorySnapshotSelector {
    fun choose(target: SnapshotManifest, retained: Collection<SnapshotManifest>): SnapshotManifest? = retained
        .asSequence()
        .filter { candidate -> candidate.key.compatibility == target.key.compatibility }
        .minWithOrNull(
            compareBy<SnapshotManifest> { candidate -> differenceCost(candidate, target) }
                .thenByDescending(SnapshotManifest::createdAtEpochMillis)
                .thenBy { candidate -> candidate.key.directoryName },
        )

    private fun differenceCost(base: SnapshotManifest, target: SnapshotManifest): Int =
        (base.files.keys + target.files.keys).count { path -> base.files[path] != target.files[path] }
}

private fun isCanonicalRelativePath(raw: String): Boolean {
    if (raw.isBlank() || '\\' in raw) return false
    val path = runCatching { Path.of(raw) }.getOrNull() ?: return false
    return !path.isAbsolute && path.normalize().toString() == raw && path.none { it.toString() == ".." }
}
