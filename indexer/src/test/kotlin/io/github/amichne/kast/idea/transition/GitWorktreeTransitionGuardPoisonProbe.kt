package io.github.amichne.kast.idea.transition

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object GitWorktreeTransitionGuardPoisonProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 3) {
            "Expected workspace root, registered Git directory, and displaced Git directory"
        }
        val workspace = Path.of(arguments[0]).toAbsolutePath().normalize()
        val missingGitDirectory = Path.of(arguments[1]).toAbsolutePath().normalize()
        val displacedGitDirectory = Path.of(arguments[2]).toAbsolutePath().normalize()
        val guard = GitWorktreeTransitionGuard.exactRoot(workspace)
        val initial = guard.inspect()
        if (initial != GitWorktreeTransitionStatus.Stable) {
            System.err.println("Expected initial Stable registration proof but observed $initial")
            exitProcess(1)
        }
        Files.move(missingGitDirectory, displacedGitDirectory)
        val expected = GitWorktreeTransitionStatus.MissingLinkedWorktreeGitDirectory(
            gitFile = workspace.resolve(".git"),
            gitDirectory = missingGitDirectory,
        )
        val observed = guard.inspect()
        if (observed != expected) {
            System.err.println("Expected $expected but observed $observed")
            exitProcess(1)
        }
    }
}
