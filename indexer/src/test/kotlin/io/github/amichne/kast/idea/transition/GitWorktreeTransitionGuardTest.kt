package io.github.amichne.kast.idea.transition

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitWorktreeTransitionGuardTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `linked worktree resolves and observes its per-worktree index lock`() {
        val repository = root.resolve("repository").also(Files::createDirectories)
        git(repository, "init")
        git(repository, "config", "user.name", "Kast Test")
        git(repository, "config", "user.email", "kast@example.invalid")
        Files.writeString(repository.resolve("README.md"), "initial")
        git(repository, "add", "README.md")
        git(repository, "commit", "-m", "initial")
        val worktree = root.resolve("linked-worktree")
        git(repository, "worktree", "add", "--detach", worktree.toString(), "HEAD")
        val indexLock = Path.of(
            gitOutput(worktree, "rev-parse", "--path-format=absolute", "--git-path", "index.lock"),
        ).toAbsolutePath().normalize()
        assertFalse(indexLock.startsWith(worktree), "linked-worktree Git metadata must be resolved outside its root")
        val guard = GitWorktreeTransitionGuard.exactRoot(worktree)

        Files.writeString(indexLock, "checkout in progress")

        val inProgress = guard.inspect() as GitWorktreeTransitionStatus.InProgress
        assertEquals(
            setOf(GitWorktreeTransitionMarkerEvidence(GitWorktreeTransitionMarker.INDEX_LOCK, indexLock)),
            inProgress.markers,
        )

        Files.delete(indexLock)
        assertEquals(GitWorktreeTransitionStatus.Stable, guard.inspect())
    }

    @Test
    fun `Git metadata with unavailable exact-worktree paths fails closed`() {
        val workspace = root.resolve("broken-worktree").also(Files::createDirectories)
        Files.writeString(workspace.resolve(".git"), "gitdir: ${root.resolve("missing-git-directory")}")

        val unavailable = GitWorktreeTransitionGuard.exactRoot(workspace).inspect()

        assertTrue(unavailable is GitWorktreeTransitionStatus.Unavailable)
    }

    private fun git(directory: Path, vararg arguments: String) {
        val process = ProcessBuilder("git", *arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.use { input -> input.readAllBytes().toString(Charsets.UTF_8) }
        assertTrue(process.waitFor() == 0, "git ${arguments.joinToString(" ")} failed: $output")
    }

    private fun gitOutput(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder("git", *arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.use { input -> input.readAllBytes().toString(Charsets.UTF_8) }
        assertTrue(process.waitFor() == 0, "git ${arguments.joinToString(" ")} failed: $output")
        return output.trim()
    }
}
