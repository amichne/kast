package io.github.amichne.kast.idea

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.idea.snapshot.CommittedGitTree
import io.github.amichne.kast.idea.snapshot.CommittedGitTreeResolution
import io.github.amichne.kast.idea.snapshot.CommittedGitTreeResolver
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotCoordinator
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparation
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPreparationResolution
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublicationOutcome
import io.github.amichne.kast.idea.snapshot.RepositorySnapshotPublicationSkip
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object RepositorySnapshotTreeBindingScenario {
    fun verify(workspace: Path) {
        Fixture(workspace).verify()
    }

    private class Fixture(
        private val workspace: Path,
    ) {
        fun verify() {
            git("init", "-b", "main")
            git("config", "user.email", "kast@example.invalid")
            git("config", "user.name", "Kast Test")
            Files.writeString(workspace.resolve("A.kt"), "class A")
            git("add", "A.kt")
            git("commit", "-m", "tree-a")
            val repositoryDirectory = workspace.resolveSibling("${workspace.fileName}-repository-state")

            SqliteSourceIndexStore(workspace).use { store ->
                store.ensureSchema()
                reconcile(store, lastModifiedMillis = 1)
                val preparedTree = committedTree()
                val preparation = snapshotPreparation(repositoryDirectory)
                val treeAPublication = preparation.capturePublication()

                Files.writeString(workspace.resolve("A.kt"), "class B")
                git("add", "A.kt")
                git("commit", "-m", "tree-b")
                val movedTree = committedTree()

                assertEquals(
                    RepositorySnapshotPublicationOutcome.Skipped(
                        RepositorySnapshotPublicationSkip.CommittedTreeMoved(
                            preparedTree,
                            CommittedGitTreeResolution.Resolved(movedTree),
                        ),
                    ),
                    treeAPublication.publish(store),
                )

                val treeBPublication = preparation.capturePublication()
                reconcile(store, lastModifiedMillis = 2)
                assertTrue(treeBPublication.publish(store) is RepositorySnapshotPublicationOutcome.Completed)
            }
        }

        private fun reconcile(store: SqliteSourceIndexStore, lastModifiedMillis: Long) {
            val path = workspace.resolve("A.kt").toAbsolutePath().normalize().toString()
            store.reconcileFileInventory(
                listOf(
                    fileInventoryEntry(
                        workspace,
                        path,
                        lastModifiedMillis,
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
        }

        private fun git(vararg arguments: String) {
            val process = ProcessBuilder("git", *arguments).directory(workspace.toFile()).start()
            assertTrue(process.waitFor() == 0, process.errorStream.bufferedReader().readText())
        }

        private fun committedTree(): CommittedGitTree = when (
            val resolution = CommittedGitTreeResolver.resolve(NormalizedPath.of(workspace))
        ) {
            is CommittedGitTreeResolution.Resolved -> resolution.tree
            is CommittedGitTreeResolution.Unavailable -> error(resolution.failure)
        }

        private fun snapshotPreparation(repositoryDirectory: Path): RepositorySnapshotPreparation = when (
            val resolution = RepositorySnapshotCoordinator.prepare(
                workspaceRoot = NormalizedPath.of(workspace),
                repositoryDirectory = NormalizedPath.ofAbsolute(repositoryDirectory),
                workspaceDatabase = NormalizedPath.ofAbsolute(
                    WorkspaceIdentity.fromWorkspaceRoot(workspace).sourceIndexDatabaseFile,
                ),
                buildClasspathFingerprint = BuildClasspathFingerprint.fromDigest("8".repeat(64)),
                producerVersion = ProducerVersion.fromVersion("test-producer"),
            )
        ) {
            is RepositorySnapshotPreparationResolution.Resolved -> resolution.preparation
            is RepositorySnapshotPreparationResolution.Rejected -> error(resolution.failure)
        }

        private fun sha256(path: Path): String =
            MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
