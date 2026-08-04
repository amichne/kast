package io.github.amichne.kast.indexstore.snapshot

import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceGenerationStoreTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `pointer changes only after the immutable database is complete`() {
        val publicationDirectory = root.resolve("published")
        var content = "generation-one"
        val store = WorkspaceGenerationStore(publicationDirectory, exportDatabase = { target ->
            Files.writeString(target, content)
            stableEvidence()
        })

        val first = store.publish(PublishedWorkspaceIdentity("workspace-one"))
        content = "generation-two"
        val second = store.publish(PublishedWorkspaceIdentity("workspace-two"))

        assertEquals(WorkspaceSemanticGeneration(2), second.generation)
        assertEquals(SourceIndexGeneration(7), second.sourceIndexGeneration)
        assertEquals(SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION), second.sourceIndexSchemaVersion)
        assertEquals(second, store.current())
        assertEquals("generation-two", Files.readString(store.database(second)))
        assertEquals("generation-one", Files.readString(store.database(first)))
        assertFalse(Files.isWritable(store.database(first)))
    }

    @Test
    fun `crash before pointer replacement preserves the prior published generation`() {
        val publicationDirectory = root.resolve("published")
        val initial = WorkspaceGenerationStore(publicationDirectory, exportDatabase = { target ->
            Files.writeString(target, "stable")
            stableEvidence()
        })
        val first = initial.publish(PublishedWorkspaceIdentity("workspace-one"))
        val interrupted = WorkspaceGenerationStore(
            directory = publicationDirectory,
            exportDatabase = { target ->
                Files.writeString(target, "candidate")
                stableEvidence()
            },
            beforePointerCommit = { error("simulated process interruption") },
        )

        assertThrows(IllegalStateException::class.java) {
            interrupted.publish(PublishedWorkspaceIdentity("workspace-two"))
        }

        assertEquals(first, initial.current())
        assertEquals("stable", Files.readString(initial.database(first)))
        assertTrue(Files.list(publicationDirectory.resolve("generations")).use { it.count() } >= 2)
    }

    @Test
    fun `unstable or incomplete export never becomes current`() {
        val store = WorkspaceGenerationStore(root.resolve("published"), exportDatabase = { target ->
            Files.writeString(target, "candidate")
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

    private fun stableEvidence() = WorkspaceDatabaseExportEvidence(
        generationBefore = SourceIndexGeneration(7),
        generationAfter = SourceIndexGeneration(7),
        moduleProgressCount = 1,
        incompleteModuleCount = 0,
        pendingUpdateCount = 0,
        sourceIndexSchemaVersion = SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
    )
}
