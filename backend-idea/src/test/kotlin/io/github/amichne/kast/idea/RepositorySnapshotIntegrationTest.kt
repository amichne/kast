package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.snapshot.CommittedGitTreeResolver
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.PublicationEvidence
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RepositorySnapshotIntegrationTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `committed tree is reusable only while the worktree is clean`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        Files.writeString(workspace.resolve("A.kt"), "class A")
        git("add", "A.kt")
        git("commit", "-m", "initial")

        val committed = CommittedGitTreeResolver.resolve(workspace)
        assertEquals(40, committed?.treeOid?.value?.length)
        assertEquals(setOf("A.kt"), committed?.files?.keys)

        Files.writeString(workspace.resolve("A.kt"), "class Changed")
        assertNull(CommittedGitTreeResolver.resolve(workspace))

        git("checkout", "--", "A.kt")
        Files.writeString(workspace.resolve("untracked.kt"), "class Untracked")
        assertNull(CommittedGitTreeResolver.resolve(workspace))
    }

    @Test
    fun `completed clean index publishes repository latest good`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        Files.writeString(workspace.resolve("A.kt"), "class A")
        git("add", "A.kt")
        git("commit", "-m", "initial")
        val repositoryDirectory = workspace.resolveSibling("${workspace.fileName}-repository-state")

        SqliteSourceIndexStore(workspace).use { store ->
            store.ensureSchema()
            store.initializeModuleProgress(mapOf("main" to 1))
            store.markModuleComplete("main", 1)
            val result = RepositorySnapshotCoordinator(
                workspaceRoot = workspace,
                repositoryDirectory = repositoryDirectory,
                buildClasspathFingerprint = BuildClasspathFingerprint.parse("8".repeat(64)),
                producerVersion = ProducerVersion.parse("test-producer"),
            ).publishCompletedIndex(store)

            assertTrue(result != null)
            assertEquals(
                CommittedGitTreeResolver.resolve(workspace)?.treeOid,
                RepositorySnapshotStore(repositoryDirectory).latestGood()?.key?.treeOid,
            )
        }
    }

    @Test
    fun `clean target bootstraps from cheapest snapshot with one direct overlay and blob shards`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        Files.writeString(workspace.resolve("A.kt"), "class A")
        Files.writeString(workspace.resolve("B.kt"), "class B")
        git("add", ".")
        git("commit", "-m", "base")
        val baseTree = requireNotNull(CommittedGitTreeResolver.resolve(workspace))
        val fingerprint = BuildClasspathFingerprint.parse("8".repeat(64))
        val producer = ProducerVersion.parse("test-producer")
        val key = SnapshotKey(baseTree.treeOid, fingerprint, SOURCE_INDEX_SCHEMA_VERSION, producer)
        val repositoryDirectory = workspace.resolveSibling("${workspace.fileName}-repository-state")
        val source = repositoryDirectory.resolveSibling("${workspace.fileName}-base.db")
        Files.writeString(source, "immutable base")
        RepositorySnapshotStore(repositoryDirectory).publishMain(
            SnapshotManifest(key, baseTree.files, 1),
            source,
            PublicationEvidence(1, 1, 1, 0, 0, key.treeOid, key.indexSchema, key.producerVersion),
        )

        Files.writeString(workspace.resolve("A.kt"), "class A2")
        Files.delete(workspace.resolve("B.kt"))
        Files.writeString(workspace.resolve("C.kt"), "class C")
        git("add", "-A")
        git("commit", "-m", "target")
        val targetDatabase = repositoryDirectory.resolveSibling("${workspace.fileName}-worktree/source-index.db")
        val overlay = RepositorySnapshotCoordinator(workspace, repositoryDirectory, fingerprint, producer)
            .prepareWorktreeDatabase(targetDatabase)

        assertEquals(setOf("B.kt"), overlay?.tombstones)
        assertEquals(setOf("A.kt", "C.kt"), overlay?.shards?.keys)
        assertEquals("immutable base", Files.readString(targetDatabase))
        overlay?.shards?.values?.forEach { shard ->
            assertTrue(RepositorySnapshotStore(repositoryDirectory).contentShard(shard)?.let(Files::isRegularFile) == true)
        }
    }

    private fun git(vararg arguments: String) {
        val process = ProcessBuilder("git", *arguments).directory(workspace.toFile()).start()
        assertTrue(process.waitFor() == 0, process.errorStream.bufferedReader().readText())
    }
}
