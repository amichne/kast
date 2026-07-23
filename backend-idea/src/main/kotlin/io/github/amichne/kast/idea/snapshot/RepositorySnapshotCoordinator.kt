package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotSelector
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.snapshot.SnapshotPublicationResult
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RepositorySnapshotCoordinator(
    private val workspaceRoot: Path,
    private val repositoryDirectory: Path,
    private val buildClasspathFingerprint: BuildClasspathFingerprint,
    private val producerVersion: ProducerVersion,
) {
    private var preparedFromSnapshot = false

    fun prepareWorktreeDatabase(databasePath: Path): OverlayManifest? {
        val overlayPath = databasePath.resolveSibling("repository-overlay.json")
        if (Files.exists(databasePath)) {
            preparedFromSnapshot = Files.isRegularFile(overlayPath)
            return null
        }
        var stagedDatabase: Path? = null
        return runCatching {
            val committedTree = CommittedGitTreeResolver.resolve(workspaceRoot) ?: return null
            val target = SnapshotManifest(
                key = SnapshotKey(
                    committedTree.treeOid,
                    buildClasspathFingerprint,
                    SOURCE_INDEX_SCHEMA_VERSION,
                    producerVersion,
                ),
                files = committedTree.files,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
            val snapshotStore = RepositorySnapshotStore(repositoryDirectory)
            val base = RepositorySnapshotSelector.choose(target, snapshotStore.retainedManifests()) ?: return null
            val overlay = OverlayManifest.between(base, target)
            overlay.shards.values.toSet().forEach { shard ->
                gitBlob(shard.blobOid)?.let { content -> snapshotStore.putContentShard(shard, content) }
            }
            Files.createDirectories(databasePath.parent)
            val staged = Files.createTempFile(databasePath.parent, ".source-index-", ".preparing")
            stagedDatabase = staged
            Files.copy(snapshotStore.snapshotDatabase(base.key), staged, StandardCopyOption.REPLACE_EXISTING)
            check(staged.toFile().setWritable(true, true)) { "Snapshot database could not be made writable" }
            Files.writeString(overlayPath, Json { prettyPrint = true }.encodeToString(overlay))
            Files.move(staged, databasePath, StandardCopyOption.ATOMIC_MOVE)
            stagedDatabase = null
            overlay.also { preparedFromSnapshot = true }
        }.getOrElse {
            stagedDatabase?.let { staged -> runCatching { Files.deleteIfExists(staged) } }
            runCatching { Files.deleteIfExists(overlayPath) }
            null
        }
    }

    fun publishCompletedIndex(store: SqliteSourceIndexStore): SnapshotPublicationResult? {
        // ponytail: copied progress has no run identity; compact after publication evidence gains one.
        if (preparedFromSnapshot) return null
        if (currentBranch() != "main") return null
        val committedTree = CommittedGitTreeResolver.resolve(workspaceRoot) ?: return null
        val key = SnapshotKey(
            treeOid = committedTree.treeOid,
            buildClasspathFingerprint = buildClasspathFingerprint,
            indexSchema = SOURCE_INDEX_SCHEMA_VERSION,
            producerVersion = producerVersion,
        )
        val exportedDatabase = Files.createTempFile(repositoryDirectory.parent, ".kast-snapshot-", ".db")
        Files.delete(exportedDatabase)
        return try {
            val evidence = store.exportSnapshotDatabase(exportedDatabase, committedTree.treeOid, producerVersion)
            if (CommittedGitTreeResolver.resolve(workspaceRoot) != committedTree) return null
            RepositorySnapshotStore(repositoryDirectory).publishMain(
                manifest = SnapshotManifest(key, committedTree.files, System.currentTimeMillis()),
                sourceDatabase = exportedDatabase,
                evidence = evidence,
            )
        } finally {
            Files.deleteIfExists(exportedDatabase)
        }
    }

    private fun currentBranch(): String? = runCatching {
        val process = ProcessBuilder("git", "symbolic-ref", "--quiet", "--short", "HEAD")
            .directory(workspaceRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { process.waitFor() == 0 }
    }.getOrNull()

    private fun gitBlob(oid: io.github.amichne.kast.indexstore.snapshot.GitObjectId): ByteArray? = runCatching {
        val process = ProcessBuilder("git", "cat-file", "blob", oid.value)
            .directory(workspaceRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.inputStream.use { it.readAllBytes() }.takeIf { process.waitFor() == 0 }
    }.getOrNull()
}
