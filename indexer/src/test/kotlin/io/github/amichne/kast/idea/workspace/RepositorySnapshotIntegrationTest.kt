package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.snapshot.CommittedGitTreeResolver
import io.github.amichne.kast.idea.snapshot.CommittedGitTree
import io.github.amichne.kast.idea.snapshot.CommittedGitTreeResolution
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparation
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparationFailure
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparationResolution
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublicationOutcome
import io.github.amichne.kast.idea.snapshot.WorktreeOverlaySeed
import io.github.amichne.kast.idea.snapshot.gitWorkspaceScope
import io.github.amichne.kast.idea.snapshot.stableClasspathRootUrl
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.client.WorkspaceRepository
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotStore
import io.github.amichne.kast.indexstore.snapshot.RepositoryRelativePath
import io.github.amichne.kast.indexstore.snapshot.RepositoryContentShardResolution
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabaseResolution
import io.github.amichne.kast.indexstore.snapshot.PublicationEvidence
import io.github.amichne.kast.indexstore.snapshot.LatestGoodSnapshot
import io.github.amichne.kast.indexstore.snapshot.SnapshotCreationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SOURCE_INDEX_SCHEMA_VERSION
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStoreAccess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class RepositorySnapshotIntegrationTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `committed tree is reusable only while the worktree is clean`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        Files.writeString(workspace.resolve("A.kt"), "class A")
        Files.writeString(workspace.resolve("Notes.txt"), "not indexed")
        git("add", "A.kt", "Notes.txt")
        git("commit", "-m", "initial")

        val committed = committedTree(workspace)
        assertEquals(40, committed.treeOid.value.length)
        assertEquals(setOf(RepositoryRelativePath.fromCanonical("A.kt")), committed.files.keys)

        Files.writeString(workspace.resolve("A.kt"), "class Changed")
        assertTrue(resolveTree(workspace) is CommittedGitTreeResolution.Unavailable)

        git("checkout", "--", "A.kt")
        Files.writeString(workspace.resolve("untracked.kt"), "class Untracked")
        assertTrue(resolveTree(workspace) is CommittedGitTreeResolution.Unavailable)

        Files.delete(workspace.resolve("untracked.kt"))
        Files.writeString(workspace.resolve(".gitignore"), "ignored.kt\n")
        git("add", ".gitignore")
        git("commit", "-m", "ignore local source")
        Files.writeString(workspace.resolve("ignored.kt"), "class Ignored")
        assertTrue(resolveTree(workspace) is CommittedGitTreeResolution.Unavailable)
    }

    @Test
    fun `subdirectory tree identity matches its scoped manifest`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        val projectDirectory = workspace.resolve(" app ")
        Files.createDirectories(projectDirectory)
        Files.writeString(projectDirectory.resolve("A.kt"), "class A")
        Files.writeString(workspace.resolve("Root.kt"), "class Root")
        git("add", ".")
        git("commit", "-m", "initial")

        val repositoryTree = committedTree(workspace)
        val subdirectoryTree = committedTree(projectDirectory)

        assertEquals(setOf(RepositoryRelativePath.fromCanonical("A.kt")), subdirectoryTree.files.keys)
        assertNotEquals(repositoryTree.treeOid, subdirectoryTree.treeOid)
        assertEquals("", gitWorkspaceScope(workspace))
        assertEquals(" app /", gitWorkspaceScope(projectDirectory))
    }

    @Test
    fun `workspace-local classpath roots have worktree-stable identity`() {
        val firstWorktree = workspace.resolveSibling("worktree-a").toAbsolutePath()
        val secondWorktree = workspace.resolveSibling("worktree-b").toAbsolutePath()

        assertEquals(
            stableClasspathRootUrl("file://$firstWorktree/build/classes/kotlin/main", firstWorktree),
            stableClasspathRootUrl("file://$secondWorktree/build/classes/kotlin/main", secondWorktree),
        )
        val externalRoot = "file:///external$firstWorktree/build/classes/kotlin/main"
        assertEquals(externalRoot, stableClasspathRootUrl(externalRoot, firstWorktree))
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
            val path = workspace.resolve("A.kt").toAbsolutePath().normalize().toString()
            store.reconcileFileInventory(
                listOf(
                    fileInventoryEntry(
                        workspace,
                        path,
                        1,
                        FileContentHash.parse(sha256(workspace.resolve("A.kt"))),
                        "main",
                        "main",
                    ),
                ),
                FileStageVersions.CURRENT,
            )
            val work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single()
            store.commitRelationshipBatch(
                listOf(RelationshipFileStageUpdate(work, work.contentHash, emptyList(), emptyList())),
            )
            val result = snapshotPreparation(
                repositoryDirectory = repositoryDirectory,
                database = WorkspaceIdentity.fromWorkspaceRoot(workspace).sourceIndexDatabaseFile,
                fingerprint = BuildClasspathFingerprint.fromDigest("8".repeat(64)),
                producer = ProducerVersion.fromVersion("test-producer"),
            ).capturePublication().publish(store)

            assertTrue(result is RepositorySnapshotPublicationOutcome.Completed)
            assertEquals(
                committedTree(workspace).treeOid,
                (RepositorySnapshotStore(repositoryDirectory).latestGood() as LatestGoodSnapshot.Available)
                    .manifest.key.treeOid,
            )
        }
    }

    @Test
    fun `prepared tree cannot be replaced before completed index publication`() {
        RepositorySnapshotTreeBindingScenario.verify(workspace)
    }

    @Test
    fun `clean target preserves immutable base facts in isolated worktree databases and blob shards`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        Files.writeString(workspace.resolve("A.kt"), "class A")
        Files.writeString(workspace.resolve("B.kt"), "class B")
        Files.writeString(workspace.resolve("asset.bin"), "base asset")
        git("add", ".")
        git("commit", "-m", "base")
        val baseTree = committedTree(workspace)
        val fingerprint = BuildClasspathFingerprint.fromDigest("8".repeat(64))
        val producer = ProducerVersion.fromVersion("test-producer")
        val key = SnapshotKey(
            baseTree.treeOid,
            fingerprint,
            SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
            producer,
        )
        val repositoryDirectory = workspace.resolveSibling("${workspace.fileName}-repository-state")
        val source = repositoryDirectory.resolveSibling("${workspace.fileName}-base.db")
        val basePayload = "x".repeat(1_000_000)
        SqliteSourceIndexStore(identityFor(source)).use { store ->
            store.ensureSchema()
            store.writeWorkspaceDiscovery("base-payload", 1, basePayload)
        }
        RepositorySnapshotStore(repositoryDirectory).publishMain(
            SnapshotManifest(key, baseTree.files, SnapshotCreationEpochMillis.fromClock(1)),
            NormalizedPath.ofAbsolute(source),
            publicationEvidence(key),
        )

        Files.writeString(workspace.resolve("A.kt"), "class A2")
        Files.delete(workspace.resolve("B.kt"))
        Files.writeString(workspace.resolve("C.kt"), "class C")
        Files.writeString(workspace.resolve("asset.bin"), "changed asset")
        git("add", "-A")
        git("commit", "-m", "target")
        val targetDatabase = repositoryDirectory.resolveSibling("${workspace.fileName}-worktree/source-index.db")
        val preparation = snapshotPreparation(repositoryDirectory, targetDatabase, fingerprint, producer)
        val overlay = (preparation.overlaySeed as WorktreeOverlaySeed.Prepared).manifest
        val immutableBase = snapshotDatabase(repositoryDirectory, key)
        val baseDigest = sha256(immutableBase)

        assertEquals(setOf(RepositoryRelativePath.fromCanonical("B.kt")), overlay.tombstones)
        assertEquals(
            setOf(RepositoryRelativePath.fromCanonical("A.kt"), RepositoryRelativePath.fromCanonical("C.kt")),
            overlay.shards.keys,
        )
        assertEquals(NormalizedPath.ofAbsolute(immutableBase).value, overlay.baseDatabase.value)
        assertFalse(Files.exists(targetDatabase))
        assertTrue(Files.isRegularFile(targetDatabase.resolveSibling("repository-overlay.json")))
        assertFalse(Files.isWritable(immutableBase))
        overlay.shards.values.forEach { shard ->
            val resolution = RepositorySnapshotStore(repositoryDirectory).contentShard(shard)
            assertTrue(
                resolution is RepositoryContentShardResolution.Available &&
                    Files.isRegularFile(resolution.shard.path.toJavaPath()),
            )
        }
        SqliteSourceIndexStore(identityFor(targetDatabase, repositoryDirectory)).use { store ->
            store.ensureSchema()
            assertEquals(basePayload, store.readWorkspaceDiscovery("base-payload"))

            val staged = store.beginWorkspaceWrite()
            store.writeWorkspaceDiscovery("staged-worktree", 1, "hidden")
            store.ensureSchema()
            SqliteSourceIndexStore(
                identityFor(targetDatabase, repositoryDirectory),
                SqliteSourceIndexStoreAccess.READ_ONLY,
            ).use { reader ->
                assertNull(reader.readWorkspaceDiscovery("staged-worktree"))
            }
            store.discardWorkspaceWrite(staged)

            store.writeWorkspaceDiscovery("worktree-a", 1, "first")
            assertEquals("first", store.readWorkspaceDiscovery("worktree-a"))
        }
        SqliteSourceIndexStore(
            identityFor(targetDatabase, repositoryDirectory),
            SqliteSourceIndexStoreAccess.READ_ONLY,
        ).use { store ->
            assertEquals(basePayload, store.readWorkspaceDiscovery("base-payload"))
            assertEquals("first", store.readWorkspaceDiscovery("worktree-a"))
        }

        val siblingDatabase = targetDatabase.parent
            .resolveSibling("${workspace.fileName}-sibling-worktree")
            .resolve("source-index.db")
        val siblingOverlay = (
            snapshotPreparation(repositoryDirectory, siblingDatabase, fingerprint, producer).overlaySeed as
                WorktreeOverlaySeed.Prepared
            ).manifest
        assertEquals(overlay.base, siblingOverlay.base)
        SqliteSourceIndexStore(identityFor(siblingDatabase, repositoryDirectory)).use { store ->
            store.ensureSchema()
            assertNull(store.readWorkspaceDiscovery("worktree-a"))
            store.writeWorkspaceDiscovery("worktree-b", 1, "second")
        }
        SqliteSourceIndexStore(identityFor(targetDatabase, repositoryDirectory)).use { store ->
            assertNull(store.readWorkspaceDiscovery("worktree-b"))
        }
        assertEquals(baseDigest, sha256(immutableBase))

        val restarted = snapshotPreparation(repositoryDirectory, targetDatabase, fingerprint, producer)
        assertTrue(restarted.overlaySeed is WorktreeOverlaySeed.None)
        SqliteSourceIndexStore(workspace).use { store ->
            assertEquals(
                RepositorySnapshotPublicationOutcome.SuppressedForWorktreeOverlay,
                restarted.capturePublication().publish(store),
            )
        }
    }

    @Test
    fun `missing retained database is rejected before overlay publication`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        Files.writeString(workspace.resolve("A.kt"), "class A")
        git("add", ".")
        git("commit", "-m", "base")
        val tree = committedTree(workspace)
        val fingerprint = BuildClasspathFingerprint.fromDigest("8".repeat(64))
        val producer = ProducerVersion.fromVersion("test-producer")
        val key = SnapshotKey(
            tree.treeOid,
            fingerprint,
            SourceIndexSchemaVersion(SOURCE_INDEX_SCHEMA_VERSION),
            producer,
        )
        val repositoryDirectory = workspace.resolveSibling("${workspace.fileName}-repository-state")
        val source = repositoryDirectory.resolveSibling("${workspace.fileName}-base.db")
        Files.writeString(source, "immutable base")
        val snapshotStore = RepositorySnapshotStore(repositoryDirectory)
        snapshotStore.publishMain(
            SnapshotManifest(key, tree.files, SnapshotCreationEpochMillis.fromClock(1)),
            NormalizedPath.ofAbsolute(source),
            publicationEvidence(key),
        )
        Files.delete(snapshotDatabase(repositoryDirectory, key))
        val targetDatabase = repositoryDirectory.resolveSibling("${workspace.fileName}-worktree/source-index.db")

        val resolution = snapshotPreparationResolution(repositoryDirectory, targetDatabase, fingerprint, producer)
        assertTrue(
            resolution is RepositorySnapshotPreparationResolution.Rejected &&
                resolution.failure is RepositorySnapshotPreparationFailure.SnapshotMetadataRejected,
        )
        assertFalse(Files.exists(targetDatabase))
    }

    private fun git(vararg arguments: String) {
        val process = ProcessBuilder("git", *arguments).directory(workspace.toFile()).start()
        assertTrue(process.waitFor() == 0, process.errorStream.bufferedReader().readText())
    }

    private fun identityFor(database: Path, repositoryDirectory: Path? = null): WorkspaceIdentity =
        WorkspaceIdentity.fromWorkspaceRoot(workspace).copy(
            repository = repositoryDirectory
                ?.let { WorkspaceRepository.Git(NormalizedPath.ofAbsolute(it)) }
                ?: WorkspaceRepository.None,
            sourceIndexDatabasePath = NormalizedPath.ofAbsolute(database),
        )

    private fun resolveTree(root: Path): CommittedGitTreeResolution =
        CommittedGitTreeResolver.resolve(NormalizedPath.of(root))

    private fun committedTree(root: Path): CommittedGitTree = when (val resolution = resolveTree(root)) {
        is CommittedGitTreeResolution.Resolved -> resolution.tree
        is CommittedGitTreeResolution.Unavailable -> error(resolution.failure)
    }

    private fun snapshotPreparation(
        repositoryDirectory: Path,
        database: Path,
        fingerprint: BuildClasspathFingerprint,
        producer: ProducerVersion,
    ): RepositorySnapshotPreparation = when (
        val resolution = snapshotPreparationResolution(repositoryDirectory, database, fingerprint, producer)
    ) {
        is RepositorySnapshotPreparationResolution.Resolved -> resolution.preparation
        is RepositorySnapshotPreparationResolution.Rejected -> error(resolution.failure)
    }

    private fun snapshotPreparationResolution(
        repositoryDirectory: Path,
        database: Path,
        fingerprint: BuildClasspathFingerprint,
        producer: ProducerVersion,
    ): RepositorySnapshotPreparationResolution = RepositorySnapshotCoordinator.prepare(
        workspaceRoot = NormalizedPath.of(workspace),
        repositoryDirectory = NormalizedPath.ofAbsolute(repositoryDirectory),
        workspaceDatabase = NormalizedPath.ofAbsolute(database),
        buildClasspathFingerprint = fingerprint,
        producerVersion = producer,
    )

    private fun snapshotDatabase(repositoryDirectory: Path, key: SnapshotKey): Path = when (
        val resolution = RepositorySnapshotStore(repositoryDirectory).resolveSnapshotDatabase(key)
    ) {
        is RepositorySnapshotDatabaseResolution.Resolved -> resolution.database.path.toJavaPath()
        is RepositorySnapshotDatabaseResolution.Rejected -> error(resolution.failure)
    }

    private fun publicationEvidence(key: SnapshotKey) = PublicationEvidence(
        generationBefore = SourceIndexGeneration(1),
        generationAfter = SourceIndexGeneration(1),
        moduleProgressCount = NonNegativeInt(1),
        incompleteModuleCount = NonNegativeInt(0),
        pendingCount = NonNegativeInt(0),
        treeOid = key.treeOid,
        indexSchema = key.indexSchema,
        producerVersion = key.producerVersion,
    )

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte) }
}
