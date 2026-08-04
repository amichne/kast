package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ExtractionShardKey
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.PublicationEvidence
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotSelector
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.snapshot.SnapshotPublicationResult
import io.github.amichne.kast.indexstore.snapshot.SnapshotRetentionPins
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

        val published = store.publishMain(first, source, exactEvidence(first.key))
        assertTrue(published is SnapshotPublicationResult.Published)
        assertEquals(first.key, store.latestGood()?.key)

        Files.writeString(source, "generation-two")
        val rejected = store.publishMain(
            manifest(tree = 'b', files = mapOf("src/B.kt" to oid('2'))),
            source,
            exactEvidence(first.key).copy(pendingCount = 1),
        )

        assertTrue(rejected is SnapshotPublicationResult.Rejected)
        assertEquals(first.key, store.latestGood()?.key)
        assertEquals("generation-one", Files.readString(store.snapshotDatabase(first.key)))
        assertFalse(Files.isWritable(store.snapshotDatabase(first.key)))
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
            files = target.files,
            fingerprint = BuildClasspathFingerprint.parse("9".repeat(64)),
        )

        assertEquals(cheapest, RepositorySnapshotSelector.choose(target, listOf(expensive, incompatible, cheapest)))
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

        val overlay = OverlayManifest.between(retained, target)

        assertEquals(setOf("gone.kt"), overlay.tombstones)
        assertEquals(setOf("added.kt", "changed.kt"), overlay.shards.keys)
        assertEquals(
            ExtractionShardKey(target.key.compatibility, oid('4')),
            overlay.shards.getValue("changed.kt"),
        )
    }

    @Test
    fun `retention keeps latest eight main snapshots active targets and fresh merge bases`() {
        val repository = root.resolve("repository")
        val store = RepositorySnapshotStore(repository)
        val source = root.resolve("source-index.db")
        Files.writeString(source, "snapshot")
        val snapshots = (('0'..'9') + 'a').mapIndexed { index, tree ->
            manifest(tree, mapOf("$tree.kt" to oid('1'))).copy(createdAtEpochMillis = index.toLong())
                .also { store.publishMain(it, source, exactEvidence(it.key)) }
        }

        store.garbageCollect(
            SnapshotRetentionPins(
                activeWorktreeTargets = setOf(snapshots[0].key),
                mergeBaseLeases = mapOf(snapshots[1].key to 100L),
                nowEpochMillis = 100L,
                diskBudgetBytes = Long.MAX_VALUE,
            ),
        )

        assertEquals(
            snapshots.filterIndexed { index, _ -> index != 2 }.map { it.key }.toSet(),
            store.retainedManifests().map { it.key }.toSet(),
        )
    }

    private fun manifest(
        tree: Char,
        files: Map<String, GitObjectId>,
        fingerprint: BuildClasspathFingerprint = BuildClasspathFingerprint.parse("8".repeat(64)),
    ) = SnapshotManifest(
        key = SnapshotKey(
            treeOid = oid(tree),
            buildClasspathFingerprint = fingerprint,
            indexSchema = 8,
            producerVersion = ProducerVersion.parse("0.13.9"),
        ),
        files = files,
        createdAtEpochMillis = 1,
    )

    private fun exactEvidence(key: SnapshotKey) = PublicationEvidence(
        generationBefore = 7,
        generationAfter = 7,
        moduleProgressCount = 2,
        incompleteModuleCount = 0,
        pendingCount = 0,
        treeOid = key.treeOid,
        indexSchema = key.indexSchema,
        producerVersion = key.producerVersion,
    )

    private fun oid(character: Char) = GitObjectId.parse(character.toString().repeat(40))
}
