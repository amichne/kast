package io.github.amichne.kast.idea.transition

import java.nio.file.Path
import kotlin.system.exitProcess

object GitWorktreeTransitionGuardPoisonProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 2) { "Expected workspace root and missing Git directory" }
        val workspace = Path.of(arguments[0]).toAbsolutePath().normalize()
        val missingGitDirectory = Path.of(arguments[1]).toAbsolutePath().normalize()
        val expected = GitWorktreeTransitionStatus.MissingLinkedWorktreeGitDirectory(
            gitFile = workspace.resolve(".git"),
            gitDirectory = missingGitDirectory,
        )
        val observed = GitWorktreeTransitionGuard.exactRoot(workspace).inspect()
        if (observed != expected) {
            System.err.println("Expected $expected but observed $observed")
            exitProcess(1)
        }
    }
}
