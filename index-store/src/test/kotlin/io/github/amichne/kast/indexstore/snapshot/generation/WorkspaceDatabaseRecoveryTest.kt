package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceDatabaseRecoveryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `restart recovery atomically rebases the mutable database and overlay from current`() {
        val publicationDirectory = root.resolve("published")
        val mutableDatabase = root.resolve("live/source-index.db")
        val mutableOverlay = mutableDatabase.resolveSibling("repository-overlay.json")
        val baseDatabase = root.resolve("repository-base.db")
        writeDatabase(baseDatabase, content = "repository-base")
        val snapshotKey = SnapshotKey(
            treeOid = GitObjectId.parse("e".repeat(40)),
            buildClasspathFingerprint = BuildClasspathFingerprint.parse("f".repeat(64)),
            indexSchema = SOURCE_INDEX_SCHEMA_VERSION,
            producerVersion = ProducerVersion.parse("test-producer"),
        )
        val overlay = OverlayManifest(
            base = snapshotKey,
            target = snapshotKey,
            tombstones = setOf("removed.kt"),
            shards = emptyMap(),
            baseDatabase = baseDatabase.toAbsolutePath().normalize().toString(),
        )
        val store = workspaceGenerationStore(publicationDirectory, "published", overlay)
        val published = store.publish(PublishedWorkspaceIdentity("workspace-published"))
        val pointer = publicationDirectory.resolve("current.json")
        val pointerBeforeRecovery = Files.readAllBytes(pointer)
        writeDatabase(mutableDatabase, content = "partial-live")
        Files.writeString(mutableOverlay, "partial overlay")
        Files.writeString(mutableSidecar(mutableDatabase, "-wal"), "stale wal")
        Files.writeString(mutableSidecar(mutableDatabase, "-shm"), "stale shm")

        val recovery = store.recoverMutableWorkspaceDatabase(mutableDatabase)

        assertEquals(WorkspaceDatabaseRecovery.Rebased(published), recovery)
        assertEquals("published", readPayload(mutableDatabase))
        assertTrue(Files.isWritable(mutableDatabase))
        assertEquals(
            Files.readString(requireNotNull(store.repositoryOverlay(published))),
            Files.readString(mutableOverlay),
        )
        assertFalse(Files.exists(mutableSidecar(mutableDatabase, "-wal")))
        assertFalse(Files.exists(mutableSidecar(mutableDatabase, "-shm")))
        assertEquals(pointerBeforeRecovery.toList(), Files.readAllBytes(pointer).toList())
        assertEquals(published, store.current())
    }

    @Test
    fun `restart recovery without current clears every partial mutable artifact`() {
        val mutableDatabase = root.resolve("live/source-index.db")
        val mutableOverlay = mutableDatabase.resolveSibling("repository-overlay.json")
        writeDatabase(mutableDatabase, content = "partial-live")
        Files.writeString(mutableOverlay, "partial overlay")
        Files.writeString(mutableSidecar(mutableDatabase, "-wal"), "stale wal")
        Files.writeString(mutableSidecar(mutableDatabase, "-shm"), "stale shm")
        val store = workspaceGenerationStore(root.resolve("published"), "unused")

        val recovery = store.recoverMutableWorkspaceDatabase(mutableDatabase)

        assertEquals(WorkspaceDatabaseRecovery.NoPublishedGeneration, recovery)
        assertFalse(Files.exists(mutableDatabase))
        assertFalse(Files.exists(mutableOverlay))
        assertFalse(Files.exists(mutableSidecar(mutableDatabase, "-wal")))
        assertFalse(Files.exists(mutableSidecar(mutableDatabase, "-shm")))
        assertNull(store.current())
    }

    @Test
    fun `restart recovery rejects an invalid pointer without retaining a partial live database`() {
        val publicationDirectory = root.resolve("published")
        val mutableDatabase = root.resolve("live/source-index.db")
        val store = workspaceGenerationStore(publicationDirectory, "published")
        val published = store.publish(PublishedWorkspaceIdentity("workspace-published"))
        writePointer(
            publicationDirectory.resolve("current.json"),
            published.copy(sourceIndexGeneration = SourceIndexGeneration(published.sourceIndexGeneration.value + 1)),
        )
        writeDatabase(mutableDatabase, content = "partial-live")
        Files.writeString(mutableSidecar(mutableDatabase, "-wal"), "stale wal")

        assertThrows(InvalidPublishedWorkspaceGenerationException::class.java) {
            store.recoverMutableWorkspaceDatabase(mutableDatabase)
        }

        assertFalse(Files.exists(mutableDatabase))
        assertFalse(Files.exists(mutableSidecar(mutableDatabase, "-wal")))
    }
}
