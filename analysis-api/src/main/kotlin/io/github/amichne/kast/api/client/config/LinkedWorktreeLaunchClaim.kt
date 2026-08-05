package io.github.amichne.kast.api.client

import java.nio.file.Path

class LinkedWorktreeLaunchClaim private constructor(
    val gitFile: Path,
    val gitDirectory: Path,
) {
    override fun equals(other: Any?): Boolean = other is LinkedWorktreeLaunchClaim &&
        gitFile == other.gitFile && gitDirectory == other.gitDirectory

    override fun hashCode(): Int = 31 * gitFile.hashCode() + gitDirectory.hashCode()

    override fun toString(): String = "LinkedWorktreeLaunchClaim(gitFile=$gitFile, gitDirectory=$gitDirectory)"

    companion object {
        const val GIT_FILE_ARGUMENT = "linked-worktree-git-file"
        const val GIT_DIRECTORY_ARGUMENT = "linked-worktree-git-directory"

        fun of(
            gitFile: Path,
            gitDirectory: Path,
        ): LinkedWorktreeLaunchClaim {
            val normalizedGitFile = gitFile.toAbsolutePath().normalize()
            val normalizedGitDirectory = gitDirectory.toAbsolutePath().normalize()
            require(normalizedGitFile.fileName?.toString() == ".git") {
                "Linked-worktree launch Git file must be named .git"
            }
            require(normalizedGitDirectory.parent?.fileName?.toString() == "worktrees") {
                "Linked-worktree launch Git directory must be inside a worktrees directory"
            }
            return LinkedWorktreeLaunchClaim(normalizedGitFile, normalizedGitDirectory)
        }

        internal fun fromValues(values: Map<String, String>): LinkedWorktreeLaunchClaim? {
            val rawGitFile = values[GIT_FILE_ARGUMENT]
            val rawGitDirectory = values[GIT_DIRECTORY_ARGUMENT]
            check((rawGitFile == null) == (rawGitDirectory == null)) {
                "Linked-worktree launch claim requires both --$GIT_FILE_ARGUMENT and --$GIT_DIRECTORY_ARGUMENT"
            }
            return if (rawGitFile == null || rawGitDirectory == null) {
                null
            } else {
                of(Path.of(rawGitFile), Path.of(rawGitDirectory))
            }
        }
    }
}
