package io.github.amichne.kast.idea.transition

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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
    fun `missing linked-worktree Git directory is explicit nonblocking evidence`() {
        val repository = committedRepository()
        val workspace = root.resolve("broken-worktree")
        git(repository, "worktree", "add", "--detach", workspace.toString(), "HEAD")
        val gitFile = workspace.resolve(".git")
        val missingGitDirectory = Path.of(gitOutput(workspace, "rev-parse", "--absolute-git-dir"))
            .toAbsolutePath()
            .normalize()
        val guard = GitWorktreeTransitionGuard.exactRoot(workspace)
        assertEquals(GitWorktreeTransitionStatus.Stable, guard.inspect())
        Files.move(missingGitDirectory, root.resolve("displaced-worktree-git-directory"))

        val status = guard.inspect()

        assertEquals(
            GitWorktreeTransitionStatus.MissingLinkedWorktreeGitDirectory(
                gitFile = gitFile,
                gitDirectory = missingGitDirectory,
            ),
            status,
        )
    }

    @Test
    fun `ambient Git repository selection cannot hide a missing worktree directory`() {
        val repository = committedRepository()
        val workspace = root.resolve("poisoned-broken-worktree")
        git(repository, "worktree", "add", "--detach", workspace.toString(), "HEAD")
        val missingGitDirectory = Path.of(gitOutput(workspace, "rev-parse", "--absolute-git-dir"))
            .toAbsolutePath()
            .normalize()
        val displacedGitDirectory = root.resolve("displaced-poisoned-worktree-git-directory")
        val unrelatedRepository = root.resolve("unrelated-repository").also(Files::createDirectories)
        git(unrelatedRepository, "init")

        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val probe = ProcessBuilder(
            java.toString(),
            "-cp",
            System.getProperty("java.class.path"),
            GitWorktreeTransitionGuardPoisonProbe::class.java.name,
            workspace.toString(),
            missingGitDirectory.toString(),
            displacedGitDirectory.toString(),
        ).redirectErrorStream(true)
        probe.environment()["GIT_DIR"] = unrelatedRepository.resolve(".git").toString()
        probe.environment()["GIT_WORK_TREE"] = workspace.toString()
        probe.environment()["GIT_COMMON_DIR"] = unrelatedRepository.resolve(".git").toString()
        probe.environment()["GIT_INDEX_FILE"] = unrelatedRepository.resolve(".git/index").toString()
        val process = probe.start()
        val output = process.inputStream.use { input -> input.readAllBytes().toString(Charsets.UTF_8) }

        assertTrue(process.waitFor() == 0, output)
    }

    @Test
    fun `missing linked-worktree Git directory without prior identity proof remains unavailable`() {
        val repository = committedRepository()
        val workspace = root.resolve("unproven-broken-worktree")
        git(repository, "worktree", "add", "--detach", workspace.toString(), "HEAD")
        val missingGitDirectory = Path.of(gitOutput(workspace, "rev-parse", "--absolute-git-dir"))
            .toAbsolutePath()
            .normalize()
        Files.move(missingGitDirectory, root.resolve("displaced-unproven-worktree-git-directory"))

        val status = GitWorktreeTransitionGuard.exactRoot(workspace).inspect()

        assertTrue(status is GitWorktreeTransitionStatus.Unavailable)
    }

    @Test
    fun `missing non-worktree Git directory remains unavailable`() {
        val repository = committedRepository()
        val registeredWorktree = root.resolve("registered-worktree")
        git(repository, "worktree", "add", "--detach", registeredWorktree.toString(), "HEAD")
        val workspace = root.resolve("separate-git-directory-workspace").also(Files::createDirectories)
        val unregisteredDirectory = repository.resolve(".git/worktrees/unregistered")
        Files.writeString(workspace.resolve(".git"), "gitdir: $unregisteredDirectory")

        val status = GitWorktreeTransitionGuard.exactRoot(workspace).inspect()

        assertTrue(status is GitWorktreeTransitionStatus.Unavailable)
    }

    @Test
    fun `indeterminate Git metadata remains unavailable`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val workspace = root.resolve("unreadable-workspace").also(Files::createDirectories)
        val ownerPermissions = PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(workspace, PosixFilePermissions.fromString("r--------"))

        val status = try {
            GitWorktreeTransitionGuard.exactRoot(workspace).inspect()
        } finally {
            Files.setPosixFilePermissions(workspace, ownerPermissions)
        }

        assertTrue(status is GitWorktreeTransitionStatus.Unavailable)
    }

    @Test
    fun `malformed Git metadata remains unavailable`() {
        val workspace = root.resolve("malformed-worktree").also(Files::createDirectories)
        Files.writeString(workspace.resolve(".git"), "not a gitdir directive")

        val status = GitWorktreeTransitionGuard.exactRoot(workspace).inspect()

        assertTrue(status is GitWorktreeTransitionStatus.Unavailable)
    }

    private fun committedRepository(): Path {
        val repository = root.resolve("repository").also(Files::createDirectories)
        git(repository, "init")
        git(repository, "config", "user.name", "Kast Test")
        git(repository, "config", "user.email", "kast@example.invalid")
        Files.writeString(repository.resolve("README.md"), "initial")
        git(repository, "add", "README.md")
        git(repository, "commit", "-m", "initial")
        return repository
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
