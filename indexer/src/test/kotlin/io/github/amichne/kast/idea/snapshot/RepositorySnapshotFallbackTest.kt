package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.GitObjectId
import io.github.amichne.kast.indexstore.snapshot.OverlayManifest
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import io.github.amichne.kast.indexstore.snapshot.RepositorySnapshotDatabasePath
import io.github.amichne.kast.indexstore.snapshot.SnapshotCreationEpochMillis
import io.github.amichne.kast.indexstore.snapshot.SnapshotKey
import io.github.amichne.kast.indexstore.snapshot.SnapshotManifest
import io.github.amichne.kast.indexstore.snapshot.SourceIndexSchemaVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class RepositorySnapshotFallbackTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `full index fallback revokes an interrupted overlay descriptor`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        Files.writeString(workspace.resolve("A.kt"), "class A")
        git("add", "A.kt")
        git("commit", "-m", "initial")

        val repositoryDirectory = workspace.resolveSibling("${workspace.fileName}-repository-state")
        val database = workspace.resolveSibling("${workspace.fileName}-worktree/source-index.db")
        Files.createDirectories(database.parent)
        val descriptor = database.resolveSibling("repository-overlay.json")
        Files.writeString(descriptor, "interrupted overlay")
        Files.writeString(workspace.resolve("A.kt"), "class Changed")

        val resolution = RepositorySnapshotCoordinator.prepare(
            workspaceRoot = NormalizedPath.of(workspace),
            repositoryDirectory = NormalizedPath.ofAbsolute(repositoryDirectory),
            workspaceDatabase = NormalizedPath.ofAbsolute(database),
            buildClasspathFingerprint = BuildClasspathFingerprint.fromDigest("8".repeat(64)),
            producerVersion = ProducerVersion.fromVersion("test-producer"),
        )

        val preparation = assertInstanceOf(
            RepositorySnapshotPreparationResolution.Resolved::class.java,
            resolution,
        ).preparation
        val seed = assertInstanceOf(WorktreeOverlaySeed.None::class.java, preparation.overlaySeed)
        assertInstanceOf(WorktreeOverlayAbsence.CommittedTreeUnavailable::class.java, seed.reason)
        assertFalse(Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `repository replacement revokes its persisted overlay authority`() {
        git("init", "-b", "main")
        git("config", "user.email", "kast@example.invalid")
        git("config", "user.name", "Kast Test")
        Files.writeString(workspace.resolve("Current.kt"), "class Current")
        git("add", "Current.kt")
        git("commit", "-m", "current repository")

        val producer = ProducerVersion.fromVersion("test-producer")
        val fingerprint = BuildClasspathFingerprint.fromDigest("9".repeat(64))
        val baseKey = SnapshotKey(
            GitObjectId.fromCanonical("1".repeat(40)),
            fingerprint,
            SourceIndexSchemaVersion(1),
            producer,
        )
        val oldRepository = workspace.resolveSibling("${workspace.fileName}-old-repository")
        val oldSnapshot = oldRepository.resolve("snapshots").resolve(baseKey.directoryName.value)
        Files.createDirectories(oldSnapshot)
        val oldBaseDatabase = oldSnapshot.resolve("source-index.db")
        Files.writeString(oldBaseDatabase, "old repository base")
        Files.writeString(
            oldSnapshot.resolve("manifest.json"),
            Json.encodeToString(SnapshotManifest(baseKey, emptyMap(), SnapshotCreationEpochMillis.fromClock(1))),
        )
        val workspaceDatabase = workspace.resolveSibling("${workspace.fileName}-worktree/source-index.db")
        Files.createDirectories(workspaceDatabase.parent)
        Files.writeString(workspaceDatabase, "persisted overlay")
        val descriptor = workspaceDatabase.resolveSibling("repository-overlay.json")
        Files.writeString(
            descriptor,
            Json.encodeToString(
                OverlayManifest(
                    baseKey,
                    baseKey,
                    emptySet(),
                    emptyMap(),
                    RepositorySnapshotDatabasePath.from(oldBaseDatabase),
                ),
            ),
        )

        val resolution = RepositorySnapshotCoordinator.prepare(
            workspaceRoot = NormalizedPath.of(workspace),
            repositoryDirectory = NormalizedPath.ofAbsolute(
                workspace.resolveSibling("${workspace.fileName}-current-repository"),
            ),
            workspaceDatabase = NormalizedPath.ofAbsolute(workspaceDatabase),
            buildClasspathFingerprint = fingerprint,
            producerVersion = producer,
        )

        val preparation = assertInstanceOf(
            RepositorySnapshotPreparationResolution.Resolved::class.java,
            resolution,
        ).preparation
        val seed = assertInstanceOf(WorktreeOverlaySeed.None::class.java, preparation.overlaySeed)
        assertEquals(WorktreeOverlayAbsence.RepositoryAuthorityChanged, seed.reason)
        assertFalse(Files.exists(descriptor, LinkOption.NOFOLLOW_LINKS))
        assertNotEquals(RepositorySnapshotPublication.Suppressed, preparation.capturePublication())
    }

    private fun git(vararg arguments: String) {
        val process = ProcessBuilder("git", *arguments).directory(workspace.toFile()).start()
        assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
    }
}
