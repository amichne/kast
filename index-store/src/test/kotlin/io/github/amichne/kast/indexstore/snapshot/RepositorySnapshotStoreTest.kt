package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ExtractionShardKey
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.PublicationEvidence
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotSelector
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotInventoryResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotMetadataFailure
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotSelection
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePath
import io.github.amichne.kast.indexstore.snapshot.RepositoryContentShardPayload
import io.github.amichne.kast.indexstore.snapshot.RepositoryContentShardPayloadResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabasePath
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotCreationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.LatestGoodSnapshot
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.snapshot.SnapshotPublicationResult
import io.github.amichne.kast.indexstore.snapshot.SnapshotRetentionPins
import io.github.amichne.kast.indexstore.snapshot.SnapshotRetentionEpochMillis
import io.github.amichne.kast.indexstore.snapshot.SnapshotDiskBudgetBytes
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RepositorySnapshotStoreTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `publishes only complete stable evidence and never rewrites a retained snapshot`() {
        val source = root.resolve("source-index.db")
        Files.writeString(source, "generation-one")
        val store = RepositorySnapshotStore(root.resolve("repository"))
        val first = manifest(tree = 'a', files = mapOf("src/A.kt" to oid('1')))

        val published = store.publishMain(first, NormalizedPath.ofAbsolute(source), exactEvidence(first.key))
        assertTrue(published is SnapshotPublicationResult.Published)
        assertEquals(first.key, (store.latestGood() as LatestGoodSnapshot.Available).manifest.key)

        Files.writeString(source, "generation-two")
        val rejected = store.publishMain(
            manifest(tree = 'b', files = mapOf("src/B.kt" to oid('2'))),
            NormalizedPath.ofAbsolute(source),
            exactEvidence(first.key).copy(pendingCount = NonNegativeInt(1)),
        )

        assertTrue(rejected is SnapshotPublicationResult.Rejected)
        assertEquals(first.key, (store.latestGood() as LatestGoodSnapshot.Available).manifest.key)
        assertEquals("generation-one", Files.readString(snapshotDatabase(store, first.key)))
        assertFalse(Files.isWritable(snapshotDatabase(store, first.key)))
    }

    @Test
    fun `chooses the compatible retained tree with the cheapest direct manifest difference`() {
        val target = manifest(
            tree = 'f',
            files = mapOf("A.kt" to oid('1'), "B.kt" to oid('2'), "C.kt" to oid('3')),
        )
        val expensive = manifest(tree = 'a', files = mapOf("A.kt" to oid('9')))
        val cheapest = manifest(tree = 'b', files = mapOf("A.kt" to oid('1'), "B.kt" to oid('2')))
        val incompatible = manifest(
            tree = 'c',
            files = target.files.mapKeys { (path, _) -> path.value },
            fingerprint = BuildClasspathFingerprint.fromDigest("9".repeat(64)),
        )

        assertEquals(
            RepositorySnapshotSelection.Selected(cheapest),
            RepositorySnapshotSelector.choose(target, listOf(expensive, incompatible, cheapest)),
        )
    }

    @Test
    fun `overlay is the direct retained tree to target tree delta`() {
        val retained = manifest(
            tree = 'a',
            files = mapOf("gone.kt" to oid('1'), "same.kt" to oid('2'), "changed.kt" to oid('3')),
        )
        val target = manifest(
            tree = 'b',
            files = mapOf("same.kt" to oid('2'), "changed.kt" to oid('4'), "added.kt" to oid('5')),
        )

        val overlay = OverlayManifest.between(
            retained,
            target,
            RepositorySnapshotDatabasePath.from(root.resolve("source-index.db")),
        )

        assertEquals(setOf(RepositoryRelativePath.fromCanonical("gone.kt")), overlay.tombstones)
        assertEquals(
            setOf(RepositoryRelativePath.fromCanonical("added.kt"), RepositoryRelativePath.fromCanonical("changed.kt")),
            overlay.shards.keys,
        )
        assertEquals(
            ExtractionShardKey(target.key.compatibility, oid('4')),
            overlay.shards.getValue(RepositoryRelativePath.fromCanonical("changed.kt")),
        )
    }

    @Test
    fun `content shard payload must prove the declared Git blob identity`() {
        val manifest = manifest(tree = 'a', files = mapOf("A.kt" to oid('1')))
        val key = ExtractionShardKey(manifest.key.compatibility, oid('1'))

        assertTrue(
            RepositoryContentShardPayload.prove(key, "different content".toByteArray()) is
                RepositoryContentShardPayloadResolution.Rejected,
        )
    }

    @Test
    fun `retained inventory rejects malformed published metadata instead of skipping it`() {
        val repository = root.resolve("malformed-repository")
        val invalidSnapshot = repository.resolve("snapshots/not-a-snapshot")
        Files.createDirectories(invalidSnapshot)
        Files.writeString(invalidSnapshot.resolve("manifest.json"), "not-json")

        assertTrue(
            RepositorySnapshotStore(repository).retainedManifests() is
                RepositorySnapshotInventoryResolution.Rejected,
        )
    }

    @Test
    fun `retained inventory rejects a symlinked manifest before decoding it`() {
        val repository = root.resolve("symlinked-manifest-repository")
        val snapshot = repository.resolve("snapshots/snapshot")
        val externalManifest = root.resolve("external-manifest.json")
        Files.createDirectories(snapshot)
        Files.writeString(externalManifest, "not-json")
        Files.createSymbolicLink(snapshot.resolve("manifest.json"), externalManifest)

        val resolution = RepositorySnapshotStore(repository).retainedManifests()

        assertTrue(
            resolution is RepositorySnapshotInventoryResolution.Rejected &&
                resolution.failure is RepositorySnapshotMetadataFailure.SnapshotManifestInvalid,
        )
    }

    @Test
    fun `retention keeps latest main snapshots active targets merge bases and overlay bases`() {
        val repository = root.resolve("data/repositories/repository-key")
        val store = RepositorySnapshotStore(repository)
        val source = root.resolve("source-index.db")
        Files.writeString(source, "snapshot")
        val snapshots = (('0'..'9') + 'a').mapIndexed { index, tree ->
            manifest(tree, mapOf("$tree.kt" to oid('1'))).copy(
                createdAt = SnapshotCreationEpochMillis.fromClock(index.toLong()),
            ).also { store.publishMain(it, NormalizedPath.ofAbsolute(source), exactEvidence(it.key)) }
        }
        val overlay = OverlayManifest(
            base = snapshots[2].key,
            target = snapshots[3].key,
            tombstones = emptySet(),
            shards = emptyMap(),
            baseDatabase = RepositorySnapshotDatabasePath.from(snapshotDatabase(store, snapshots[2].key)),
        )
        val workspaceCache = root.resolve("data/workspaces/workspace-key/cache")
        Files.createDirectories(workspaceCache)
        Files.writeString(workspaceCache.resolve("repository-overlay.json"), Json.encodeToString(overlay))

        store.garbageCollect(
            SnapshotRetentionPins(
                activeWorktreeTargets = setOf(snapshots[0].key),
                mergeBaseLeases = mapOf(
                    snapshots[1].key to SnapshotRetentionEpochMillis.fromClock(100L),
                ),
                now = SnapshotRetentionEpochMillis.fromClock(100L),
                diskBudget = SnapshotDiskBudgetBytes.fromConfiguration(Long.MAX_VALUE),
            ),
        )

        assertEquals(
            snapshots.map { it.key }.toSet(),
            (store.retainedManifests() as RepositorySnapshotInventoryResolution.Resolved)
                .manifests
                .map { it.key }
                .toSet(),
        )
    }

    private fun manifest(
        tree: Char,
        files: Map<String, GitObjectId>,
        fingerprint: BuildClasspathFingerprint = BuildClasspathFingerprint.fromDigest("8".repeat(64)),
    ) = SnapshotManifest(
        key = SnapshotKey(
            treeOid = oid(tree),
            buildClasspathFingerprint = fingerprint,
            indexSchema = SourceIndexSchemaVersion(8),
            producerVersion = ProducerVersion.fromVersion("0.13.9"),
        ),
        files = files.mapKeys { (path, _) -> RepositoryRelativePath.fromCanonical(path) },
        createdAt = SnapshotCreationEpochMillis.fromClock(1),
    )

    private fun exactEvidence(key: SnapshotKey) = PublicationEvidence(
        generationBefore = SourceIndexGeneration(7),
        generationAfter = SourceIndexGeneration(7),
        moduleProgressCount = NonNegativeInt(2),
        incompleteModuleCount = NonNegativeInt(0),
        pendingCount = NonNegativeInt(0),
        treeOid = key.treeOid,
        indexSchema = key.indexSchema,
        producerVersion = key.producerVersion,
    )

    private fun oid(character: Char) = GitObjectId.fromCanonical(character.toString().repeat(40))

    private fun snapshotDatabase(store: RepositorySnapshotStore, key: SnapshotKey): Path =
        when (val resolution = store.resolveSnapshotDatabase(key)) {
            is RepositorySnapshotDatabaseResolution.Resolved -> resolution.database.path.toJavaPath()
            is RepositorySnapshotDatabaseResolution.Rejected -> error(resolution.failure)
        }
}
