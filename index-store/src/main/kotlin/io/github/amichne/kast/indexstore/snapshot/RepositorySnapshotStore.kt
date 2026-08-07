package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.api.contract.NormalizedPath
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

private const val MAIN_HISTORY_RETENTION = 8
private const val MERGE_BASE_LEASE_MILLIS = 30L * 24 * 60 * 60 * 1_000

/**
 * Construction transition: `Path -> RepositorySnapshotStore`.
 *
 * Derives one normalized repository-storage authority; subsequent operations
 * derive snapshot paths only through its [RepositorySnapshotLayout]. Raw paths
 * are exposed solely at filesystem boundaries.
 */
class RepositorySnapshotStore(repositoryDirectory: Path) {
    private val layout = RepositorySnapshotLayout.from(repositoryDirectory)
    private val metadata = RepositorySnapshotMetadataStore(layout)
    private val snapshotsDirectory get() = layout.snapshotsDirectory
    private val shardsDirectory get() = layout.shardsDirectory

    /**
     * Proof transition:
     * `(SnapshotManifest, NormalizedPath, PublicationEvidence) -> SnapshotPublicationResult`.
     *
     * Publication occurs only when evidence proves a stable, complete index
     * for the manifest key and the source database is available. Expected
     * rejection is finite [SnapshotPublicationFailure] data. The normalized
     * database path is extracted only for filesystem copying.
     */
    fun publishMain(
        manifest: SnapshotManifest,
        sourceDatabase: NormalizedPath,
        evidence: PublicationEvidence,
    ): SnapshotPublicationResult {
        when (val proof = evidence.prove(manifest.key)) {
            is SnapshotPublicationEvidenceResolution.Proven -> Unit
            is SnapshotPublicationEvidenceResolution.Rejected -> return SnapshotPublicationResult.Rejected(
                SnapshotPublicationFailure.EvidenceRejected(proof.failure),
            )
        }
        val sourceDatabasePath = sourceDatabase.toJavaPath()
        if (!Files.isRegularFile(sourceDatabasePath)) {
            return SnapshotPublicationResult.Rejected(
                SnapshotPublicationFailure.SourceDatabaseUnavailable(sourceDatabase),
            )
        }
        val history = when (val resolution = metadata.readMainHistory()) {
            is RepositoryMainHistoryResolution.Resolved -> resolution.history
            is RepositoryMainHistoryResolution.Rejected -> return SnapshotPublicationResult.Rejected(
                SnapshotPublicationFailure.MetadataRejected(resolution.failure),
            )
        }
        when (val latest = latestGood()) {
            LatestGoodSnapshot.Unavailable,
            is LatestGoodSnapshot.Available,
            -> Unit
            is LatestGoodSnapshot.Rejected -> return SnapshotPublicationResult.Rejected(
                SnapshotPublicationFailure.MetadataRejected(latest.failure),
            )
        }
        val destination = layout.snapshotDirectory(manifest.key)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            val existing = when (val resolution = resolveSnapshotDatabase(manifest.key)) {
                is RepositorySnapshotDatabaseResolution.Resolved -> resolution.database
                is RepositorySnapshotDatabaseResolution.Rejected -> return SnapshotPublicationResult.Rejected(
                    SnapshotPublicationFailure.ExistingSnapshotRejected(resolution.failure),
                )
            }
            metadata.recordMainSnapshot(manifest.key, history)
            metadata.publishLatestGood(manifest.key)
            return SnapshotPublicationResult.Reused(existing.manifest)
        }
        Files.createDirectories(snapshotsDirectory)
        val temporary = snapshotsDirectory.resolve(".${manifest.key.directoryName.value}.${UUID.randomUUID()}.tmp")
        try {
            Files.createDirectory(temporary)
            val database = temporary.resolve(DATABASE_FILE)
            Files.copy(sourceDatabasePath, database, StandardCopyOption.COPY_ATTRIBUTES)
            writeJson(temporary.resolve(MANIFEST_FILE), manifest.copy(files = manifest.files.toSortedMap()))
            sync(database)
            sync(temporary.resolve(MANIFEST_FILE))
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            makeImmutable(destination.resolve(DATABASE_FILE))
            makeImmutable(destination.resolve(MANIFEST_FILE))
            metadata.recordMainSnapshot(manifest.key, history)
            metadata.publishLatestGood(manifest.key)
            return SnapshotPublicationResult.Published(manifest)
        } catch (failure: Throwable) {
            temporary.toFile().deleteRecursively()
            throw failure
        }
    }

    /**
     * Proof transition: `RepositorySnapshotLayout -> LatestGoodSnapshot`.
     *
     * An available result carries a manifest whose exact repository-derived
     * database, adjacent manifest, and latest-good key all agree. Absence and
     * finite [RepositorySnapshotMetadataFailure] rejection are explicit. Raw
     * pointer and manifest JSON are confined to this storage boundary.
     */
    fun latestGood(): LatestGoodSnapshot = metadata.latestGood()

    /**
     * Proof transition: `SnapshotKey -> RepositorySnapshotDatabaseResolution`.
     *
     * A resolved value proves exact repository/key path derivation, regular
     * non-symlink storage, and adjacent manifest agreement. Rejection is finite
     * `RepositorySnapshotDatabaseFailure` data. The raw path may be extracted
     * only at filesystem, SQLite, or serialization boundaries.
     */
    fun resolveSnapshotDatabase(key: SnapshotKey): RepositorySnapshotDatabaseResolution =
        layout.resolveDatabase(RepositorySnapshotDatabaseCandidate(key, layout.databasePath(key)))

    /**
     * Proof transition: `OverlayManifest -> RepositoryOverlayBaseResolution`.
     *
     * Resolves the declared base against this store's exact repository
     * authority. A current-repository result carries the validated database;
     * another repository and finite current-repository rejection remain
     * explicit closed outcomes.
     */
    fun resolveOverlayBase(overlay: OverlayManifest): RepositoryOverlayBaseResolution =
        layout.resolveOverlayBase(overlay)

    /**
     * Proof transition:
     * `RepositoryContentShardPayload -> RepositoryContentShardPublicationResult`.
     *
     * Publishes only bytes already proven to match their Git blob identity.
     * Reuse revalidates the stored shard before returning its capability;
     * finite storage or hash rejection is explicit. Raw bytes are extracted
     * only for filesystem persistence.
     */
    fun putContentShard(
        payload: RepositoryContentShardPayload,
    ): RepositoryContentShardPublicationResult {
        val key = payload.key
        Files.createDirectories(shardsDirectory)
        val destination = shardsDirectory.resolve(key.directoryName.value)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return when (val resolution = contentShard(key)) {
                is RepositoryContentShardResolution.Available ->
                    RepositoryContentShardPublicationResult.Reused(resolution.shard)
                RepositoryContentShardResolution.Unavailable -> RepositoryContentShardPublicationResult.Rejected(
                    RepositoryContentShardFailure.StorageInvalid(NormalizedPath.ofAbsolute(destination)),
                )
                is RepositoryContentShardResolution.Rejected ->
                    RepositoryContentShardPublicationResult.Rejected(resolution.failure)
            }
        }
        val temporary = shardsDirectory.resolve(".${key.directoryName.value}.${UUID.randomUUID()}.tmp")
        Files.write(temporary, payload.content)
        sync(temporary)
        runCatching { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse {
                Files.deleteIfExists(temporary)
                if (!Files.isRegularFile(destination)) throw it
        }
        makeImmutable(destination)
        return RepositoryContentShardPublicationResult.Published(
            RepositoryContentShard(key, NormalizedPath.ofAbsolute(destination)),
        )
    }

    /**
     * Proof transition: `ExtractionShardKey -> RepositoryContentShardResolution`.
     *
     * An available shard proves a regular non-symlink file whose bytes match
     * the requested Git blob identity. Absence and finite storage or payload
     * rejection are explicit. Raw bytes are read only at this filesystem
     * boundary.
     */
    fun contentShard(key: ExtractionShardKey): RepositoryContentShardResolution {
        val path = shardsDirectory.resolve(key.directoryName.value)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return RepositoryContentShardResolution.Unavailable
        val normalized = NormalizedPath.ofAbsolute(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return RepositoryContentShardResolution.Rejected(
                RepositoryContentShardFailure.StorageInvalid(normalized),
            )
        }
        return when (val proof = RepositoryContentShardPayload.prove(key, Files.readAllBytes(path))) {
            is RepositoryContentShardPayloadResolution.Proven -> RepositoryContentShardResolution.Available(
                RepositoryContentShard(proof.payload.key, normalized),
            )
            is RepositoryContentShardPayloadResolution.Rejected -> RepositoryContentShardResolution.Rejected(
                RepositoryContentShardFailure.PayloadRejected(proof.failure),
            )
        }
    }

    /**
     * Proof transition:
     * `RepositorySnapshotLayout -> RepositorySnapshotInventoryResolution`.
     *
     * A resolved inventory contains only exact repository-bound snapshot
     * databases with manifest/key/directory agreement. Malformed or ambiguous
     * published entries reject the whole inventory with finite
     * [RepositorySnapshotMetadataFailure] data; they are never silently
     * omitted. Raw directory and manifest data stay at this storage boundary.
     */
    fun retainedManifests(): RepositorySnapshotInventoryResolution = metadata.retainedManifests()

    /**
     * Proof transition:
     * `SnapshotRetentionPins -> RepositorySnapshotGarbageCollectionResult`.
     *
     * Deletes only snapshots outside the union of explicit pins, main history,
     * latest-good state, valid leases, and validated live overlay bases. An
     * invalid overlay authority rejects collection before deletion with finite
     * [RepositoryOverlayRetentionFailure] data.
     */
    fun garbageCollect(pins: SnapshotRetentionPins): RepositorySnapshotGarbageCollectionResult {
        val overlayPins = when (val resolution = RepositoryOverlayRetention(layout).activeBasePins()) {
            is RepositoryOverlayRetentionResolution.Resolved -> resolution.pins
            is RepositoryOverlayRetentionResolution.Rejected -> return RepositorySnapshotGarbageCollectionResult.Rejected(
                RepositorySnapshotGarbageCollectionFailure.OverlayAuthorityRejected(resolution.failure),
            )
        }
        val history = when (val resolution = metadata.readMainHistory()) {
            is RepositoryMainHistoryResolution.Resolved -> resolution.history
            is RepositoryMainHistoryResolution.Rejected -> return RepositorySnapshotGarbageCollectionResult.Rejected(
                RepositorySnapshotGarbageCollectionFailure.MetadataRejected(resolution.failure),
            )
        }
        val inventory = when (val resolution = retainedManifests()) {
            is RepositorySnapshotInventoryResolution.Resolved -> resolution.manifests
            is RepositorySnapshotInventoryResolution.Rejected -> return RepositorySnapshotGarbageCollectionResult.Rejected(
                RepositorySnapshotGarbageCollectionFailure.MetadataRejected(resolution.failure),
            )
        }
        val pinned = buildSet {
            when (val latest = latestGood()) {
                LatestGoodSnapshot.Unavailable -> Unit
                is LatestGoodSnapshot.Available -> add(latest.manifest.key)
                is LatestGoodSnapshot.Rejected -> return RepositorySnapshotGarbageCollectionResult.Rejected(
                    RepositorySnapshotGarbageCollectionFailure.MetadataRejected(latest.failure),
                )
            }
            addAll(history.snapshots.takeLast(MAIN_HISTORY_RETENTION))
            addAll(pins.activeWorktreeTargets)
            addAll(overlayPins.keys)
            pins.mergeBaseLeases.forEach { (key, acquiredAt) ->
                if (pins.now.value - acquiredAt.value <= MERGE_BASE_LEASE_MILLIS) add(key)
            }
        }
        deleteChildren(layout.repositoryDirectory.toJavaPath().resolve("overlays"))
        inventory
            .filterNot { it.key in pinned }
            .forEach { layout.snapshotDirectory(it.key).toFile().deleteRecursively() }
        deleteChildren(layout.shardsDirectory)
        val retainedSize = directorySize(layout.repositoryDirectory.toJavaPath())
        return if (retainedSize.value <= pins.diskBudget.value) {
            RepositorySnapshotGarbageCollectionResult.Completed
        } else {
            RepositorySnapshotGarbageCollectionResult.PinnedDataExceedsBudget(retainedSize, pins.diskBudget)
        }
    }

    private fun deleteChildren(directory: Path) {
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { paths -> paths.forEach { it.toFile().deleteRecursively() } }
    }

    private fun directorySize(directory: Path): SnapshotStorageBytes {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return SnapshotStorageBytes.fromFilesystem(0)
        }
        val bytes = Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
        }
        return SnapshotStorageBytes.fromFilesystem(bytes)
    }

    private fun writeJson(path: Path, value: SnapshotManifest) {
        Files.writeString(path, JSON.encodeToString(value))
    }

    private fun sync(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    }

    private fun makeImmutable(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ),
            )
        }.getOrElse { path.toFile().setWritable(false, false) }
    }

    private companion object {
        const val DATABASE_FILE = "source-index.db"
        const val MANIFEST_FILE = "manifest.json"
        val JSON = Json { prettyPrint = true }
    }
}
