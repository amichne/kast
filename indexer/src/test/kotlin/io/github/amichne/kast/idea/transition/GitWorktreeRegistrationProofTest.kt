package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.api.client.LinkedWorktreeLaunchClaim
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitWorktreeRegistrationProofTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `capture rejects a launch claim for a different worktree admin directory`() {
        val repository = committedRepository()
        val workspace = root.resolve("claimed-worktree")
        val alternateWorkspace = root.resolve("alternate-worktree")
        git(repository, "worktree", "add", "--detach", workspace.toString(), "HEAD")
        git(repository, "worktree", "add", "--detach", alternateWorkspace.toString(), "HEAD")
        val alternateGitDirectory = absoluteGitDirectory(alternateWorkspace)

        assertThrows(IllegalStateException::class.java) {
            GitWorktreeRegistrationProof.capture(
                workspace,
                LinkedWorktreeLaunchClaim.of(workspace.resolve(".git"), alternateGitDirectory),
            )
        }
    }

    @Test
    fun `capture rejects a registration with a mismatched backlink`() {
        val repository = committedRepository()
        val workspace = root.resolve("mismatched-backlink-worktree")
        git(repository, "worktree", "add", "--detach", workspace.toString(), "HEAD")
        val gitDirectory = absoluteGitDirectory(workspace)
        val forgedGitFile = root.resolve("forged/.git")
        Files.createDirectories(forgedGitFile.parent)
        Files.writeString(forgedGitFile, "forged\n")
        Files.writeString(gitDirectory.resolve("gitdir"), "$forgedGitFile\n")

        assertThrows(IllegalStateException::class.java) {
            GitWorktreeRegistrationProof.capture(
                workspace,
                LinkedWorktreeLaunchClaim.of(workspace.resolve(".git"), gitDirectory),
            )
        }
    }

    @Test
    fun `proof rejects a same-path replacement admin directory`() {
        val repository = committedRepository()
        val workspace = root.resolve("replaced-admin-worktree")
        git(repository, "worktree", "add", "--detach", workspace.toString(), "HEAD")
        val gitDirectory = absoluteGitDirectory(workspace)
        val proof = GitWorktreeRegistrationProof.capture(
            workspace,
            LinkedWorktreeLaunchClaim.of(workspace.resolve(".git"), gitDirectory),
        )
        Files.move(gitDirectory, root.resolve("displaced-admin-directory"))
        Files.createDirectory(gitDirectory)
        Files.writeString(gitDirectory.resolve("gitdir"), "${workspace.resolve(".git")}\n")

        val status = GitWorktreeTransitionGuard.exactRoot(workspace, proof).inspect()

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

    private fun absoluteGitDirectory(workspace: Path): Path = Path.of(
        gitOutput(workspace, "rev-parse", "--absolute-git-dir"),
    ).toAbsolutePath().normalize()

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
