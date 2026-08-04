package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceGenerationStoreTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `prepared generation is invisible until commit`() {
        val publicationDirectory = root.resolve("published")
        val store = workspaceGenerationStore(publicationDirectory, "generation-one")

        val prepared = store.prepare(PublishedWorkspaceIdentity("workspace-one"))

        assertNull(store.current())
        assertTrue(Files.isRegularFile(store.database(prepared.manifest)))

        val commit = store.commit(prepared)
        val published = commit.manifest

        assertEquals(WorkspaceGenerationCommit.Durable(prepared.manifest), commit)
        assertEquals(prepared.manifest, published)
        assertEquals(published, store.current())
        assertEquals("generation-one", readPayload(store.database(published)))
    }

    @Test
    fun `reopen after prepare preserves the prior current generation`() {
        val publicationDirectory = root.resolve("published")
        val firstStore = workspaceGenerationStore(publicationDirectory, "generation-one")
        val first = firstStore.publish(PublishedWorkspaceIdentity("workspace-one"))
        val preparingStore = workspaceGenerationStore(publicationDirectory, "generation-two")

        val prepared = preparingStore.prepare(PublishedWorkspaceIdentity("workspace-two"))
        val reopened = workspaceGenerationStore(publicationDirectory, "unused")

        assertEquals(first, prepared.expectedCurrent)
        assertEquals(WorkspaceSemanticGeneration(2), prepared.manifest.generation)
        assertEquals(first, reopened.current())
        assertEquals("generation-one", readPayload(reopened.database(first)))

        preparingStore.discard(prepared)
        assertFalse(Files.exists(preparingStore.database(prepared.manifest)))
    }

    @Test
    fun `commit rejects a prepared generation when current moved`() {
        val publicationDirectory = root.resolve("published")
        val firstStore = workspaceGenerationStore(publicationDirectory, "generation-one")
        firstStore.publish(PublishedWorkspaceIdentity("workspace-one"))
        val staleStore = workspaceGenerationStore(publicationDirectory, "stale-candidate")
        val stale = staleStore.prepare(PublishedWorkspaceIdentity("workspace-stale"))
        val competingStore = workspaceGenerationStore(publicationDirectory, "generation-two")
        val competing = competingStore.publish(PublishedWorkspaceIdentity("workspace-two"))

        assertThrows(StaleWorkspaceGenerationCommitException::class.java) {
            staleStore.commit(stale)
        }

        assertEquals(competing, firstStore.current())
        staleStore.discard(stale)
    }

    @Test
    fun `current rejects database escape schema mismatch and source generation mismatch`() {
        val publicationDirectory = root.resolve("published")
        val store = workspaceGenerationStore(publicationDirectory, "generation-one")
        val published = store.publish(PublishedWorkspaceIdentity("workspace-one"))
        val pointer = publicationDirectory.resolve("current.json")

        Files.writeString(
            pointer,
            WORKSPACE_GENERATION_TEST_JSON.encodeToString(published).replace(published.databaseFile, "../outside.db"),
        )
        assertThrows(InvalidPublishedWorkspaceGenerationException::class.java, store::current)

        writePointer(
            pointer,
            published.copy(sourceIndexSchemaVersion = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION + 1)),
        )
        assertThrows(InvalidPublishedWorkspaceGenerationException::class.java, store::current)

        writePointer(
            pointer,
            published.copy(sourceIndexGeneration = SourceIndexGeneration(published.sourceIndexGeneration.value + 1)),
        )
        assertThrows(InvalidPublishedWorkspaceGenerationException::class.java, store::current)
    }

    @Test
    fun `published generation owns its repository base after the external snapshot is deleted`() {
        val publicationDirectory = root.resolve("published")
        val baseDatabase = root.resolve("repository-base.db")
        writeDatabase(baseDatabase, content = "repository-base")
        val snapshotKey = SnapshotKey(
            treeOid = GitObjectId.parse("a".repeat(40)),
            buildClasspathFingerprint = BuildClasspathFingerprint.parse("b".repeat(64)),
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
        val store = workspaceGenerationStore(publicationDirectory, "overlay", overlay)

        val published = store.publish(PublishedWorkspaceIdentity("workspace-overlay"))
        val descriptor = store.repositoryOverlay(published)

        assertNotNull(descriptor)
        assertEquals("repository-overlay.json", published.repositoryOverlayFile)
        val publishedOverlay = WORKSPACE_GENERATION_TEST_JSON.decodeFromString<OverlayManifest>(
            Files.readString(requireNotNull(descriptor)),
        )
        val publishedBase = Path.of(requireNotNull(publishedOverlay.baseDatabase))
        assertEquals(overlay.copy(baseDatabase = publishedBase.toString()), publishedOverlay)
        assertEquals(store.database(published).parent, publishedBase.parent)
        assertEquals("repository-base", readPayload(publishedBase))

        Files.delete(baseDatabase)

        assertEquals(published, workspaceGenerationStore(publicationDirectory, "unused").current())
        assertEquals("repository-base", readPayload(publishedBase))
    }

    @Test
    fun `commit rejects a symbolic repository overlay without changing current`() {
        val publicationDirectory = root.resolve("published")
        val baseDatabase = root.resolve("repository-base.db")
        writeDatabase(baseDatabase, content = "repository-base")
        val snapshotKey = SnapshotKey(
            treeOid = GitObjectId.parse("c".repeat(40)),
            buildClasspathFingerprint = BuildClasspathFingerprint.parse("d".repeat(64)),
            indexSchema = SOURCE_INDEX_SCHEMA_VERSION,
            producerVersion = ProducerVersion.parse("test-producer"),
        )
        val overlay = OverlayManifest(
            base = snapshotKey,
            target = snapshotKey,
            tombstones = emptySet(),
            shards = emptyMap(),
            baseDatabase = baseDatabase.toAbsolutePath().normalize().toString(),
        )
        val store = workspaceGenerationStore(publicationDirectory, "overlay", overlay)
        val prepared = store.prepare(PublishedWorkspaceIdentity("workspace-overlay"))
        val descriptor = requireNotNull(store.repositoryOverlay(prepared.manifest))
        Files.delete(descriptor)
        Files.createSymbolicLink(descriptor, baseDatabase)

        assertThrows(IllegalArgumentException::class.java) {
            store.commit(prepared)
        }
        assertNull(store.current())
    }

    @Test
    fun `directory sync failure after pointer replacement is classified as a committed generation`() {
        val publicationDirectory = root.resolve("published")
        var failPointerDirectorySync = false
        val store = WorkspaceGenerationStore(
            directory = publicationDirectory,
            exportDatabase = { target ->
                writeDatabase(target, content = "generation-one")
                stableEvidence()
            },
            directorySync = { path ->
                if (failPointerDirectorySync && path == publicationDirectory.toAbsolutePath().normalize()) {
                    throw IOException("simulated directory sync failure")
                }
            },
        )
        val prepared = store.prepare(PublishedWorkspaceIdentity("workspace-one"))
        failPointerDirectorySync = true

        val commit = store.commit(prepared)
        val published = commit.manifest

        assertTrue(commit is WorkspaceGenerationCommit.DurabilityUncertain)
        assertEquals(
            "simulated directory sync failure",
            (commit as WorkspaceGenerationCommit.DurabilityUncertain).cause.message,
        )
        assertEquals(prepared.manifest, published)
        assertEquals(prepared.manifest, store.current())
        val mutableDatabase = root.resolve("live/source-index.db")
        assertEquals(
            WorkspaceDatabaseRecovery.Rebased(prepared.manifest),
            store.recoverMutableWorkspaceDatabase(mutableDatabase),
        )
        assertEquals("generation-one", readPayload(mutableDatabase))
        assertThrows(IllegalStateException::class.java) {
            store.discard(prepared)
        }
    }

    @Test
    fun `discard failure is reported and leaves the prepared generation usable`() {
        val publicationDirectory = root.resolve("published")
        val store = WorkspaceGenerationStore(
            directory = publicationDirectory,
            exportDatabase = { target ->
                writeDatabase(target, content = "generation-one")
                stableEvidence()
            },
            deleteGenerationDirectory = { false },
        )
        val prepared = store.prepare(PublishedWorkspaceIdentity("workspace-one"))

        assertThrows(IllegalStateException::class.java) {
            store.discard(prepared)
        }

        assertTrue(Files.isRegularFile(store.database(prepared.manifest)))
        assertEquals(WorkspaceGenerationCommit.Durable(prepared.manifest), store.commit(prepared))
    }

    @Test
    fun `pointer changes only after the immutable database is complete`() {
        val publicationDirectory = root.resolve("published")
        var content = "generation-one"
        val store = workspaceGenerationStore(publicationDirectory, content = { content })

        val first = store.publish(PublishedWorkspaceIdentity("workspace-one"))
        content = "generation-two"
        val second = store.publish(PublishedWorkspaceIdentity("workspace-two"))

        assertEquals(WorkspaceSemanticGeneration(2), second.generation)
        assertEquals(SourceIndexGeneration(7), second.sourceIndexGeneration)
        assertEquals(SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION), second.sourceIndexSchemaVersion)
        assertEquals(second, store.current())
        assertEquals("generation-two", readPayload(store.database(second)))
        assertEquals("generation-one", readPayload(store.database(first)))
        assertFalse(Files.isWritable(store.database(first)))
    }

    @Test
    fun `crash before pointer replacement preserves the prior published generation`() {
        val publicationDirectory = root.resolve("published")
        val initial = workspaceGenerationStore(publicationDirectory, "stable")
        val first = initial.publish(PublishedWorkspaceIdentity("workspace-one"))
        val interrupted = WorkspaceGenerationStore(
            directory = publicationDirectory,
            exportDatabase = { target ->
                writeDatabase(target, content = "candidate")
                stableEvidence()
            },
            beforePointerCommit = { error("simulated process interruption") },
        )
        val prepared = interrupted.prepare(PublishedWorkspaceIdentity("workspace-two"))

        assertThrows(IllegalStateException::class.java) {
            interrupted.commit(prepared)
        }

        assertEquals(first, initial.current())
        assertEquals("stable", readPayload(initial.database(first)))
        interrupted.discard(prepared)
        assertFalse(Files.exists(interrupted.database(prepared.manifest)))
    }

    @Test
    fun `unstable or incomplete export never becomes current`() {
        val store = WorkspaceGenerationStore(root.resolve("published"), exportDatabase = { target ->
            writeDatabase(target, content = "candidate")
            stableEvidence().copy(generationAfter = SourceIndexGeneration(8))
        })

        assertThrows(IllegalArgumentException::class.java) {
            store.publish(PublishedWorkspaceIdentity("workspace-one"))
        }

        assertEquals(null, store.current())
    }

    @Test
    fun `empty module progress cannot become current`() {
        SqliteSourceIndexStore(root.resolve("workspace")).use { sourceIndex ->
            sourceIndex.ensureSchema()
            val store = WorkspaceGenerationStore(
                directory = root.resolve("published"),
                exportDatabase = sourceIndex::exportVerifiedWorkspaceDatabase,
            )

            assertThrows(IllegalArgumentException::class.java) {
                store.publish(PublishedWorkspaceIdentity("workspace-one"))
            }

            assertEquals(null, store.current())
        }
    }

}
