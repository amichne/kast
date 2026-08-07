package io.github.amichne.kast.idea.snapshot

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.indexstore.snapshot.BuildClasspathFingerprint
import io.github.amichne.kast.indexstore.snapshot.ProducerVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

    private fun git(vararg arguments: String) {
        val process = ProcessBuilder("git", *arguments).directory(workspace.toFile()).start()
        assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
    }
}
