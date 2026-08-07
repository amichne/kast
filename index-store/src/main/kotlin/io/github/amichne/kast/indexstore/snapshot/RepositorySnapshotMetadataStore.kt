package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.api.contract.NormalizedPath
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal sealed interface RepositoryMainHistoryResolution {
    data class Resolved(val history: RepositoryMainHistory) : RepositoryMainHistoryResolution

    data class Rejected(val failure: RepositorySnapshotMetadataFailure) : RepositoryMainHistoryResolution
}

internal class RepositoryMainHistory internal constructor(
    internal val snapshots: List<SnapshotKey>,
)

internal class RepositorySnapshotMetadataStore(
    private val layout: RepositorySnapshotLayout,
) {
    /**
     * Proof transition: `RepositorySnapshotLayout -> LatestGoodSnapshot`.
     *
     * Derives an available exact database/manifest authority, explicit
     * absence, or finite [RepositorySnapshotMetadataFailure] rejection from
     * raw pointer JSON at the repository metadata boundary.
     */
    fun latestGood(): LatestGoodSnapshot {
        val pointerPath = layout.latestGoodPath
        if (!Files.exists(pointerPath, LinkOption.NOFOLLOW_LINKS)) return LatestGoodSnapshot.Unavailable
        val normalizedPointer = NormalizedPath.ofAbsolute(pointerPath)
        if (!Files.isRegularFile(pointerPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(pointerPath)) {
            return LatestGoodSnapshot.Rejected(
                RepositorySnapshotMetadataFailure.LatestGoodPointerInvalid(normalizedPointer),
            )
        }
        val pointer = runCatching { JSON.decodeFromString<LatestGood>(Files.readString(pointerPath)) }
            .getOrElse {
                return LatestGoodSnapshot.Rejected(
                    RepositorySnapshotMetadataFailure.LatestGoodPointerMalformed(normalizedPointer),
                )
            }
        return when (
            val resolution = layout.resolveDatabase(
                RepositorySnapshotDatabaseCandidate(pointer.key, layout.databasePath(pointer.key)),
            )
        ) {
            is RepositorySnapshotDatabaseResolution.Resolved -> LatestGoodSnapshot.Available(
                resolution.database.manifest,
            )
            is RepositorySnapshotDatabaseResolution.Rejected -> LatestGoodSnapshot.Rejected(
                RepositorySnapshotMetadataFailure.SnapshotDatabaseRejected(resolution.failure),
            )
        }
    }

    /**
     * Proof transition:
     * `RepositorySnapshotLayout -> RepositorySnapshotInventoryResolution`.
     *
     * A resolved inventory contains only repository-bound snapshot manifests;
     * any malformed or ambiguous published entry rejects the entire inventory.
     */
    fun retainedManifests(): RepositorySnapshotInventoryResolution {
        val snapshots = layout.snapshotsDirectory
        if (!Files.exists(snapshots, LinkOption.NOFOLLOW_LINKS)) {
            return RepositorySnapshotInventoryResolution.Resolved(emptyList())
        }
        val normalizedAuthority = NormalizedPath.ofAbsolute(snapshots)
        if (!Files.isDirectory(snapshots, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(snapshots)) {
            return RepositorySnapshotInventoryResolution.Rejected(
                RepositorySnapshotMetadataFailure.SnapshotAuthorityInvalid(normalizedAuthority),
            )
        }
        val manifests = mutableListOf<SnapshotManifest>()
        val entries = Files.list(snapshots).use { paths -> paths.toList().sorted() }
        for (directory in entries) {
            if (directory.fileName.toString().startsWith(".")) continue
            val normalizedDirectory = NormalizedPath.ofAbsolute(directory)
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
                return RepositorySnapshotInventoryResolution.Rejected(
                    RepositorySnapshotMetadataFailure.SnapshotEntryInvalid(normalizedDirectory),
                )
            }
            val manifestPath = directory.resolve(MANIFEST_FILE)
            val normalizedManifest = NormalizedPath.ofAbsolute(manifestPath)
            if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifestPath)) {
                return RepositorySnapshotInventoryResolution.Rejected(
                    RepositorySnapshotMetadataFailure.SnapshotManifestInvalid(normalizedManifest),
                )
            }
            val manifest = runCatching { JSON.decodeFromString<SnapshotManifest>(Files.readString(manifestPath)) }
                .getOrElse {
                    return RepositorySnapshotInventoryResolution.Rejected(
                        RepositorySnapshotMetadataFailure.SnapshotManifestMalformed(normalizedManifest),
                    )
                }
            if (directory.fileName.toString() != manifest.key.directoryName.value) {
                return RepositorySnapshotInventoryResolution.Rejected(
                    RepositorySnapshotMetadataFailure.SnapshotDirectoryMismatch(
                        normalizedDirectory,
                        manifest.key.directoryName,
                    ),
                )
            }
            when (
                val resolution = layout.resolveDatabase(
                    RepositorySnapshotDatabaseCandidate(manifest.key, layout.databasePath(manifest.key)),
                )
            ) {
                is RepositorySnapshotDatabaseResolution.Resolved -> manifests += resolution.database.manifest
                is RepositorySnapshotDatabaseResolution.Rejected -> return RepositorySnapshotInventoryResolution.Rejected(
                    RepositorySnapshotMetadataFailure.SnapshotDatabaseRejected(resolution.failure),
                )
            }
        }
        return RepositorySnapshotInventoryResolution.Resolved(
            manifests.sortedBy { manifest -> manifest.createdAt.value },
        )
    }

    /**
     * Proof transition: `RepositorySnapshotLayout -> RepositoryMainHistoryResolution`.
     *
     * A resolved history capability carries only decoded [SnapshotKey] values;
     * invalid storage or JSON is finite [RepositorySnapshotMetadataFailure]
     * data. Raw JSON is confined to this repository metadata boundary.
     */
    fun readMainHistory(): RepositoryMainHistoryResolution {
        val historyPath = layout.mainHistoryPath
        if (!Files.exists(historyPath, LinkOption.NOFOLLOW_LINKS)) {
            return RepositoryMainHistoryResolution.Resolved(RepositoryMainHistory(emptyList()))
        }
        val normalized = NormalizedPath.ofAbsolute(historyPath)
        if (!Files.isRegularFile(historyPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(historyPath)) {
            return RepositoryMainHistoryResolution.Rejected(
                RepositorySnapshotMetadataFailure.MainHistoryInvalid(normalized),
            )
        }
        val history = runCatching { JSON.decodeFromString<MainHistory>(Files.readString(historyPath)).snapshots }
            .getOrElse {
                return RepositoryMainHistoryResolution.Rejected(
                    RepositorySnapshotMetadataFailure.MainHistoryMalformed(normalized),
                )
            }
        return RepositoryMainHistoryResolution.Resolved(RepositoryMainHistory(history))
    }

    fun publishLatestGood(key: SnapshotKey) {
        val pointerPath = layout.latestGoodPath
        Files.createDirectories(pointerPath.parent)
        val temporary = pointerPath.resolveSibling(".${pointerPath.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, JSON.encodeToString(LatestGood(key)))
        sync(temporary)
        Files.move(temporary, pointerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun recordMainSnapshot(key: SnapshotKey, currentHistory: RepositoryMainHistory) {
        val historyPath = layout.mainHistoryPath
        val history = (currentHistory.snapshots + key).distinct()
        Files.createDirectories(historyPath.parent)
        val temporary = historyPath.resolveSibling(".${historyPath.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, JSON.encodeToString(MainHistory(history)))
        sync(temporary)
        Files.move(temporary, historyPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sync(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    }

    @Serializable
    private data class LatestGood(val key: SnapshotKey)

    @Serializable
    private data class MainHistory(val snapshots: List<SnapshotKey>)

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        val JSON = Json { prettyPrint = true }
    }
}
