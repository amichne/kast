package io.github.amichne.kast.indexstore.snapshot

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

private const val MAIN_HISTORY_RETENTION = 8
private const val MERGE_BASE_LEASE_MILLIS = 30L * 24 * 60 * 60 * 1_000
private const val DEFAULT_DISK_BUDGET_BYTES = 10L * 1024 * 1024 * 1024

data class SnapshotRetentionPins(
    val activeWorktreeTargets: Set<SnapshotKey> = emptySet(),
    val mergeBaseLeases: Map<SnapshotKey, Long> = emptyMap(),
    val nowEpochMillis: Long = System.currentTimeMillis(),
    val diskBudgetBytes: Long = DEFAULT_DISK_BUDGET_BYTES,
) {
    init {
        require(nowEpochMillis >= 0)
        require(diskBudgetBytes >= 0)
    }
}

data class PublicationEvidence(
    val generationBefore: Long,
    val generationAfter: Long,
    val moduleProgressCount: Int,
    val incompleteModuleCount: Int,
    val pendingCount: Int,
    val treeOid: GitObjectId,
    val indexSchema: Int,
    val producerVersion: ProducerVersion,
) {
    fun proves(key: SnapshotKey): Boolean = generationBefore >= 0 &&
        generationBefore == generationAfter &&
        moduleProgressCount > 0 &&
        incompleteModuleCount == 0 &&
        pendingCount == 0 &&
        treeOid == key.treeOid &&
        indexSchema == key.indexSchema &&
        producerVersion == key.producerVersion
}

sealed interface SnapshotPublicationResult {
    data class Published(val manifest: SnapshotManifest) : SnapshotPublicationResult
    data class Reused(val manifest: SnapshotManifest) : SnapshotPublicationResult
    data class Rejected(val reason: String) : SnapshotPublicationResult
}

class RepositorySnapshotStore(private val repositoryDirectory: Path) {
    private val snapshotsDirectory = repositoryDirectory.resolve("snapshots")
    private val latestGoodPath = repositoryDirectory.resolve("main/latest-good.json")
    private val mainHistoryPath = repositoryDirectory.resolve("main/history.json")
    private val shardsDirectory = repositoryDirectory.resolve("shards")

    fun publishMain(
        manifest: SnapshotManifest,
        sourceDatabase: Path,
        evidence: PublicationEvidence,
    ): SnapshotPublicationResult {
        if (!evidence.proves(manifest.key)) {
            return SnapshotPublicationResult.Rejected("Source index evidence is not complete and stable for the target tree")
        }
        if (!Files.isRegularFile(sourceDatabase)) {
            return SnapshotPublicationResult.Rejected("Source index database is unavailable")
        }
        val destination = snapshotDirectory(manifest.key)
        if (Files.isDirectory(destination)) {
            recordMainSnapshot(manifest.key)
            publishLatestGood(manifest.key)
            return SnapshotPublicationResult.Reused(readManifest(destination))
        }
        Files.createDirectories(snapshotsDirectory)
        val temporary = snapshotsDirectory.resolve(".${manifest.key.directoryName}.${UUID.randomUUID()}.tmp")
        try {
            Files.createDirectory(temporary)
            val database = temporary.resolve(DATABASE_FILE)
            Files.copy(sourceDatabase, database, StandardCopyOption.COPY_ATTRIBUTES)
            writeJson(temporary.resolve(MANIFEST_FILE), manifest.copy(files = manifest.files.toSortedMap()))
            sync(database)
            sync(temporary.resolve(MANIFEST_FILE))
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            makeImmutable(destination.resolve(DATABASE_FILE))
            makeImmutable(destination.resolve(MANIFEST_FILE))
            recordMainSnapshot(manifest.key)
            publishLatestGood(manifest.key)
            return SnapshotPublicationResult.Published(manifest)
        } catch (failure: Throwable) {
            temporary.toFile().deleteRecursively()
            throw failure
        }
    }

    fun latestGood(): SnapshotManifest? {
        if (!Files.isRegularFile(latestGoodPath)) return null
        val pointer = Json.decodeFromString<LatestGood>(Files.readString(latestGoodPath))
        val manifest = readManifest(snapshotDirectory(pointer.key))
        return manifest.takeIf { it.key == pointer.key }
    }

    fun snapshotDatabase(key: SnapshotKey): Path = snapshotDirectory(key).resolve(DATABASE_FILE)

    fun putContentShard(key: ExtractionShardKey, content: ByteArray): Path {
        Files.createDirectories(shardsDirectory)
        val destination = shardsDirectory.resolve(key.directoryName)
        if (Files.isRegularFile(destination)) return destination
        val temporary = shardsDirectory.resolve(".${key.directoryName}.${UUID.randomUUID()}.tmp")
        Files.write(temporary, content)
        sync(temporary)
        runCatching { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse {
                Files.deleteIfExists(temporary)
                if (!Files.isRegularFile(destination)) throw it
            }
        makeImmutable(destination)
        return destination
    }

    fun contentShard(key: ExtractionShardKey): Path? =
        shardsDirectory.resolve(key.directoryName).takeIf(Files::isRegularFile)

    fun retainedManifests(): List<SnapshotManifest> {
        if (!Files.isDirectory(snapshotsDirectory)) return emptyList()
        return Files.list(snapshotsDirectory).use { paths ->
            paths.toList().filter(Files::isDirectory)
                .mapNotNull { directory -> runCatching { readManifest(directory) }.getOrNull() }
                .sortedBy(SnapshotManifest::createdAtEpochMillis)
        }
    }

    fun garbageCollect(pins: SnapshotRetentionPins) {
        deleteChildren(repositoryDirectory.resolve("overlays"))
        val history = readMainHistory()
        val pinned = buildSet {
            latestGood()?.key?.let(::add)
            addAll(history.takeLast(MAIN_HISTORY_RETENTION))
            addAll(pins.activeWorktreeTargets)
            pins.mergeBaseLeases.forEach { (key, acquiredAt) ->
                if (pins.nowEpochMillis - acquiredAt <= MERGE_BASE_LEASE_MILLIS) add(key)
            }
        }
        retainedManifests()
            .filterNot { it.key in pinned }
            .forEach { snapshotDirectory(it.key).toFile().deleteRecursively() }
        if (directorySize(repositoryDirectory) > pins.diskBudgetBytes) {
            retainedManifests()
                .filterNot { it.key in pinned }
                .takeWhile { directorySize(repositoryDirectory) > pins.diskBudgetBytes }
                .forEach { snapshotDirectory(it.key).toFile().deleteRecursively() }
        }
        deleteChildren(repositoryDirectory.resolve("shards"))
    }

    private fun snapshotDirectory(key: SnapshotKey): Path = snapshotsDirectory.resolve(key.directoryName)

    private fun readManifest(directory: Path): SnapshotManifest =
        Json.decodeFromString(Files.readString(directory.resolve(MANIFEST_FILE)))

    private fun publishLatestGood(key: SnapshotKey) {
        Files.createDirectories(latestGoodPath.parent)
        val temporary = latestGoodPath.resolveSibling(".${latestGoodPath.fileName}.${UUID.randomUUID()}.tmp")
        writeJson(temporary, LatestGood(key))
        sync(temporary)
        Files.move(
            temporary,
            latestGoodPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun recordMainSnapshot(key: SnapshotKey) {
        val history = (readMainHistory() + key).distinct()
        Files.createDirectories(mainHistoryPath.parent)
        val temporary = mainHistoryPath.resolveSibling(".${mainHistoryPath.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, JSON.encodeToString(MainHistory(history)))
        sync(temporary)
        Files.move(temporary, mainHistoryPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readMainHistory(): List<SnapshotKey> = if (Files.isRegularFile(mainHistoryPath)) {
        runCatching { JSON.decodeFromString<MainHistory>(Files.readString(mainHistoryPath)).snapshots }.getOrDefault(emptyList())
    } else {
        emptyList()
    }

    private fun deleteChildren(directory: Path) {
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { paths -> paths.forEach { it.toFile().deleteRecursively() } }
    }

    private fun directorySize(directory: Path): Long {
        if (!Files.exists(directory)) return 0
        return Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
        }
    }

    private fun writeJson(path: Path, value: SnapshotManifest) {
        Files.writeString(path, JSON.encodeToString(value))
    }

    private fun writeJson(path: Path, value: LatestGood) {
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

    @Serializable
    private data class LatestGood(val key: SnapshotKey)

    @Serializable
    private data class MainHistory(val snapshots: List<SnapshotKey>)

    private companion object {
        const val DATABASE_FILE = "source-index.db"
        const val MANIFEST_FILE = "manifest.json"
        val JSON = Json { prettyPrint = true }
    }
}
