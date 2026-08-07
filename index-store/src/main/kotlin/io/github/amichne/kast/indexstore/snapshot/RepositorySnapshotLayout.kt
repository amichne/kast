package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.api.contract.NormalizedPath
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class RepositorySnapshotDatabaseCandidate(
    val key: SnapshotKey,
    val declaredPath: RepositorySnapshotDatabasePath,
)

class RepositorySnapshotDatabase internal constructor(
    val key: SnapshotKey,
    val path: RepositorySnapshotDatabasePath,
    val manifest: SnapshotManifest,
)

sealed interface RepositorySnapshotDatabaseFailure {
    data class PathMismatch(
        val expected: RepositorySnapshotDatabasePath,
        val declared: RepositorySnapshotDatabasePath,
    ) : RepositorySnapshotDatabaseFailure

    data class DatabaseUnavailable(val path: RepositorySnapshotDatabasePath) : RepositorySnapshotDatabaseFailure

    data class DatabaseSymlinked(val path: RepositorySnapshotDatabasePath) : RepositorySnapshotDatabaseFailure

    data class ManifestUnavailable(val path: NormalizedPath) : RepositorySnapshotDatabaseFailure

    data class ManifestSymlinked(val path: NormalizedPath) : RepositorySnapshotDatabaseFailure

    data class ManifestMalformed(val path: NormalizedPath) : RepositorySnapshotDatabaseFailure

    data class ManifestKeyMismatch(
        val expected: SnapshotKey,
        val actual: SnapshotKey,
    ) : RepositorySnapshotDatabaseFailure
}

sealed interface RepositorySnapshotDatabaseResolution {
    data class Resolved(val database: RepositorySnapshotDatabase) : RepositorySnapshotDatabaseResolution

    data class Rejected(val failure: RepositorySnapshotDatabaseFailure) : RepositorySnapshotDatabaseResolution
}

class RepositorySnapshotAuthorityException(
    val failure: RepositorySnapshotDatabaseFailure,
) : IllegalStateException(failure.toString())

internal sealed interface RepositoryRegistryLayout {
    data object Standalone : RepositoryRegistryLayout

    data class Keyed(
        val repositoriesDirectory: NormalizedPath,
        val workspacesDirectory: NormalizedPath,
    ) : RepositoryRegistryLayout
}

internal class RepositorySnapshotLayout private constructor(
    val repositoryDirectory: NormalizedPath,
) {
    val snapshotsDirectory: Path
        get() = repositoryDirectory.toJavaPath().resolve(SNAPSHOTS_DIRECTORY)

    val latestGoodPath: Path
        get() = repositoryDirectory.toJavaPath().resolve("main/latest-good.json")

    val mainHistoryPath: Path
        get() = repositoryDirectory.toJavaPath().resolve("main/history.json")

    val shardsDirectory: Path
        get() = repositoryDirectory.toJavaPath().resolve("shards")

    val registry: RepositoryRegistryLayout = deriveRegistry(repositoryDirectory)

    fun snapshotDirectory(key: SnapshotKey): Path = snapshotsDirectory.resolve(key.directoryName.value)

    fun databasePath(key: SnapshotKey): RepositorySnapshotDatabasePath =
        RepositorySnapshotDatabasePath.from(snapshotDirectory(key).resolve(DATABASE_FILE))

    /**
     * Proof transition:
     * `RepositorySnapshotDatabaseCandidate -> RepositorySnapshotDatabaseResolution`.
     *
     * A resolved value proves that the declared database is the exact location
     * derived from this repository and snapshot key, is a regular non-symlink
     * file, and has an adjacent regular non-symlink manifest with the same key.
     * Rejection is finite `RepositorySnapshotDatabaseFailure` data. The raw
     * path may be extracted only at filesystem, SQLite, or serialization
     * boundaries.
     */
    fun resolveDatabase(candidate: RepositorySnapshotDatabaseCandidate): RepositorySnapshotDatabaseResolution {
        val expected = databasePath(candidate.key)
        if (candidate.declaredPath != expected) {
            return RepositorySnapshotDatabaseResolution.Rejected(
                RepositorySnapshotDatabaseFailure.PathMismatch(expected, candidate.declaredPath),
            )
        }
        val database = candidate.declaredPath.toJavaPath()
        if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)
        ) {
            return RepositorySnapshotDatabaseResolution.Rejected(
                RepositorySnapshotDatabaseFailure.DatabaseUnavailable(candidate.declaredPath),
            )
        }
        if (Files.isSymbolicLink(database)) {
            return RepositorySnapshotDatabaseResolution.Rejected(
                RepositorySnapshotDatabaseFailure.DatabaseSymlinked(candidate.declaredPath),
            )
        }
        val manifestPath = NormalizedPath.ofAbsolute(database.resolveSibling(MANIFEST_FILE))
        val manifestFile = manifestPath.toJavaPath()
        if (!Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)
        ) {
            return RepositorySnapshotDatabaseResolution.Rejected(
                RepositorySnapshotDatabaseFailure.ManifestUnavailable(manifestPath),
            )
        }
        if (Files.isSymbolicLink(manifestFile)) {
            return RepositorySnapshotDatabaseResolution.Rejected(
                RepositorySnapshotDatabaseFailure.ManifestSymlinked(manifestPath),
            )
        }
        val manifest = runCatching {
            JSON.decodeFromString<SnapshotManifest>(Files.readString(manifestFile))
        }.getOrElse {
            return RepositorySnapshotDatabaseResolution.Rejected(
                RepositorySnapshotDatabaseFailure.ManifestMalformed(manifestPath),
            )
        }
        if (manifest.key != candidate.key) {
            return RepositorySnapshotDatabaseResolution.Rejected(
                RepositorySnapshotDatabaseFailure.ManifestKeyMismatch(candidate.key, manifest.key),
            )
        }
        return RepositorySnapshotDatabaseResolution.Resolved(
            RepositorySnapshotDatabase(candidate.key, candidate.declaredPath, manifest),
        )
    }

    companion object {
        /**
         * Proof transition: `Path -> RepositorySnapshotLayout`.
         *
         * Derives one absolute normalized repository-storage authority and all
         * snapshot paths beneath it. Raw paths are exposed only to filesystem
         * adapters owned by the snapshot store.
         */
        fun from(repositoryDirectory: Path): RepositorySnapshotLayout =
            RepositorySnapshotLayout(NormalizedPath.ofAbsolute(repositoryDirectory))

        /**
         * Derivation transition:
         * `NormalizedPath -> RepositoryRegistryLayout`.
         *
         * Produces either a keyed repositories/workspaces authority derived
         * from the exact flat data-root shape or explicit standalone state.
         */
        private fun deriveRegistry(repositoryDirectory: NormalizedPath): RepositoryRegistryLayout {
            val repository = repositoryDirectory.toJavaPath()
            val repositories = repository.parent
            val data = repositories?.parent
            return if (repositories?.fileName?.toString() == REPOSITORIES_DIRECTORY && data != null) {
                RepositoryRegistryLayout.Keyed(
                    repositoriesDirectory = NormalizedPath.ofAbsolute(repositories),
                    workspacesDirectory = NormalizedPath.ofAbsolute(data.resolve(WORKSPACES_DIRECTORY)),
                )
            } else {
                RepositoryRegistryLayout.Standalone
            }
        }

        private const val DATABASE_FILE = "source-index.db"
        private const val MANIFEST_FILE = "manifest.json"
        private const val REPOSITORIES_DIRECTORY = "repositories"
        private const val SNAPSHOTS_DIRECTORY = "snapshots"
        private const val WORKSPACES_DIRECTORY = "workspaces"
        private val JSON = Json { ignoreUnknownKeys = false }
    }
}
