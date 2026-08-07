package io.github.amichne.kast.indexstore.snapshot

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
@JvmInline
value class RepositoryRelativePath private constructor(val value: String) : Comparable<RepositoryRelativePath> {
    init {
        require(isCanonical(value)) { "Repository-relative path must be canonical: $value" }
    }

    override fun compareTo(other: RepositoryRelativePath): Int = value.compareTo(other.value)

    companion object {
        /**
         * Proof transition: `String -> RepositoryRelativePathResolution`.
         *
         * A resolved value establishes a non-blank, canonical,
         * repository-relative path with no parent traversal or
         * platform-specific separators. Rejection is finite
         * [RepositoryRelativePathFailure] data. The raw value may be accepted
         * or extracted only at Git, filesystem, SQLite, or serialization
         * boundaries.
         */
        fun resolve(raw: String): RepositoryRelativePathResolution = if (isCanonical(raw)) {
            RepositoryRelativePathResolution.Resolved(RepositoryRelativePath(raw))
        } else {
            RepositoryRelativePathResolution.Rejected(RepositoryRelativePathFailure.NonCanonical(raw))
        }

        /**
         * Derivation transition: `String -> RepositoryRelativePath` for
         * repository-owned constants and already-proven source paths. A
         * violation is a programming defect, not an expected parse outcome.
         */
        fun fromCanonical(raw: String): RepositoryRelativePath = RepositoryRelativePath(raw)

        private fun isCanonical(raw: String): Boolean {
            if (raw.isBlank() || '\\' in raw) return false
            val path = runCatching { Path.of(raw) }.getOrNull() ?: return false
            return !path.isAbsolute && path.normalize().toString() == raw && path.none { it.toString() == ".." }
        }
    }
}

sealed interface RepositoryRelativePathFailure {
    data class NonCanonical(val value: String) : RepositoryRelativePathFailure
}

sealed interface RepositoryRelativePathResolution {
    data class Resolved(val path: RepositoryRelativePath) : RepositoryRelativePathResolution

    data class Rejected(val failure: RepositoryRelativePathFailure) : RepositoryRelativePathResolution
}

@Serializable
@JvmInline
value class RepositorySnapshotDatabasePath private constructor(val value: String) {
    init {
        require(isCanonicalDatabasePath(value)) {
            "Repository snapshot database path must be an absolute normalized source-index.db"
        }
    }

    /** Raw extraction is confined to filesystem, SQLite, and serialization boundaries. */
    fun toJavaPath(): Path = Path.of(value)

    companion object {
        /**
         * Proof transition: `Path -> RepositorySnapshotDatabasePath`.
         *
         * Derives an absolute normalized `source-index.db` location. Existence,
         * repository ownership, and manifest agreement are established later by
         * `RepositorySnapshotLayout.resolveDatabase`. The raw path may be
         * extracted only at filesystem, SQLite, or serialization boundaries.
         */
        fun from(path: Path): RepositorySnapshotDatabasePath =
            RepositorySnapshotDatabasePath(path.toAbsolutePath().normalize().toString())

        private fun isCanonicalDatabasePath(raw: String): Boolean {
            val path = runCatching { Path.of(raw) }.getOrNull() ?: return false
            return path.isAbsolute && path.normalize().toString() == raw && path.fileName.toString() == "source-index.db"
        }
    }
}

@Serializable
@JvmInline
value class SnapshotCreationEpochMillis private constructor(val value: Long) {
    init {
        require(value >= 0) { "Snapshot creation time must be non-negative" }
    }

    companion object {
        /**
         * Proof transition: `Long -> SnapshotCreationEpochMillis`.
         *
         * Establishes a non-negative Unix epoch millisecond timestamp. Raw
         * extraction is permitted only at clock and serialization boundaries.
         */
        fun fromClock(value: Long): SnapshotCreationEpochMillis = SnapshotCreationEpochMillis(value)
    }
}

@Serializable
data class SnapshotManifest(
    val key: SnapshotKey,
    val files: Map<RepositoryRelativePath, GitObjectId>,
    val createdAt: SnapshotCreationEpochMillis,
)

/**
 * Construction transition:
 * `(SnapshotKey, SnapshotKey, Set<RepositoryRelativePath>,`
 * `Map<RepositoryRelativePath, ExtractionShardKey>,`
 * `RepositorySnapshotDatabasePath) -> OverlayManifest`.
 *
 * Establishes exact base/target compatibility, disjoint tombstone and changed
 * paths, and shard compatibility with the target. All inputs already carry
 * their primitive-level invariants; raw extraction is permitted only at Git,
 * filesystem, SQLite, and serialization boundaries.
 */
@Serializable
data class OverlayManifest(
    val base: SnapshotKey,
    val target: SnapshotKey,
    val tombstones: Set<RepositoryRelativePath>,
    val shards: Map<RepositoryRelativePath, ExtractionShardKey>,
    val baseDatabase: RepositorySnapshotDatabasePath,
) {
    init {
        require(base.compatibility == target.compatibility) {
            "Snapshot overlay requires exact compatibility"
        }
        require(tombstones.intersect(shards.keys).isEmpty()) {
            "Snapshot overlay paths cannot be both tombstones and shards"
        }
        require(shards.values.all { shard -> shard.compatibility == target.compatibility }) {
            "Snapshot overlay shards must match target compatibility"
        }
    }

    companion object {
        /**
         * Proof transition:
         * `(SnapshotManifest, SnapshotManifest, RepositorySnapshotDatabasePath) -> OverlayManifest`.
         *
         * Derives a compatible overlay whose tombstones and changed shards are
         * disjoint and whose base database is already constrained to the
         * canonical snapshot filename. The returned manifest, not either input
         * manifest, is the proof consumed by overlay publication.
         */
        fun between(
            base: SnapshotManifest,
            target: SnapshotManifest,
            baseDatabase: RepositorySnapshotDatabasePath,
        ): OverlayManifest {
            require(base.key.compatibility == target.key.compatibility) { "Snapshot overlay requires exact compatibility" }
            val tombstones = base.files.keys.minus(target.files.keys).toSortedSet()
            val shards = target.files
                .filter { (path, oid) -> base.files[path] != oid }
                .toSortedMap()
                .mapValues { (_, oid) -> ExtractionShardKey(target.key.compatibility, oid) }
            return OverlayManifest(base.key, target.key, tombstones, shards, baseDatabase)
        }
    }
}

sealed interface RepositorySnapshotSelection {
    data object NoCompatibleSnapshot : RepositorySnapshotSelection

    data class Selected(val manifest: SnapshotManifest) : RepositorySnapshotSelection
}

object RepositorySnapshotSelector {
    /**
     * Proof transition:
     * `(SnapshotManifest, Collection<SnapshotManifest>) -> RepositorySnapshotSelection`.
     *
     * Derives either the best exactly compatible base or the explicit absence
     * of one. The selected manifest carries compatibility; callers never infer
     * selection from null.
     */
    fun choose(
        target: SnapshotManifest,
        retained: Collection<SnapshotManifest>,
    ): RepositorySnapshotSelection = retained
        .asSequence()
        .filter { candidate -> candidate.key.compatibility == target.key.compatibility }
        .minWithOrNull(
            compareBy<SnapshotManifest> { candidate -> differenceCost(candidate, target) }
                .thenByDescending { candidate -> candidate.createdAt.value }
                .thenBy { candidate -> candidate.key.directoryName.value },
        )
        ?.let(RepositorySnapshotSelection::Selected)
        ?: RepositorySnapshotSelection.NoCompatibleSnapshot

    private fun differenceCost(base: SnapshotManifest, target: SnapshotManifest): Int =
        (base.files.keys + target.files.keys).count { path -> base.files[path] != target.files[path] }
}
