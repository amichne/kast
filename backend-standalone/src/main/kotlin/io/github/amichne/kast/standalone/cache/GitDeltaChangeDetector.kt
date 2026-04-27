package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.standalone.normalizeStandalonePath
import java.nio.file.Path
import kotlin.io.path.extension

internal data class GitDeltaCandidates(
    val headCommit: String,
    val paths: Set<String>,
    val trackedPaths: Set<String>,
)

internal interface GitDeltaCandidateDetector {
    fun detectCandidatePaths(
        storedHeadCommit: String?,
        sourceRoots: List<Path>,
    ): GitDeltaCandidates?

    fun currentHeadCommit(): String?
}

internal class GitDeltaChangeDetector(
    private val workspaceRoot: Path,
    private val gitRunner: GitRunner = ProcessGitRunner(workspaceRoot),
) : GitDeltaCandidateDetector {
    override fun detectCandidatePaths(
        storedHeadCommit: String?,
        sourceRoots: List<Path>,
    ): GitDeltaCandidates? {
        val stored = storedHeadCommit?.takeIf { it.isNotBlank() } ?: return null
        val head = currentHeadCommit() ?: return null
        val sourceRootSet = sourceRoots.mapTo(mutableSetOf()) { normalizeStandalonePath(it) }
        val committedDelta = gitRunner.run("diff", "$stored..HEAD", "--name-only", "--", "*.kt")
        val workingTreeDelta = gitRunner.run("diff", "HEAD", "--name-only", "--", "*.kt")
        val trackedFiles = gitRunner.run("ls-files", "--", "*.kt")
        if (!committedDelta.success || !workingTreeDelta.success || !trackedFiles.success) return null
        val changedPaths = (committedDelta.lines + workingTreeDelta.lines)
            .mapNotNull { relativePath -> normalizeGitPath(relativePath, sourceRootSet) }
            .toSet()
        val trackedPaths = trackedFiles.lines
            .mapNotNull { relativePath -> normalizeGitPath(relativePath, sourceRootSet) }
            .toSet()
        return GitDeltaCandidates(headCommit = head, paths = changedPaths, trackedPaths = trackedPaths)
    }

    override fun currentHeadCommit(): String? =
        gitRunner.run("rev-parse", "HEAD").takeIf { it.success }?.lines?.singleOrNull()?.takeIf { it.isNotBlank() }

    private fun normalizeGitPath(
        relativePath: String,
        sourceRoots: Set<Path>,
    ): String? {
        if (relativePath.isBlank()) return null
        val normalizedPath = normalizeStandalonePath(workspaceRoot.resolve(relativePath))
        if (normalizedPath.extension != "kt") return null
        if (sourceRoots.none(normalizedPath::startsWith)) return null
        return normalizedPath.toString()
    }
}

internal interface GitRunner {
    fun run(vararg args: String): GitCommandResult
}

internal data class GitCommandResult(
    val success: Boolean,
    val lines: List<String>,
)

private class ProcessGitRunner(
    private val workspaceRoot: Path,
) : GitRunner {
    override fun run(vararg args: String): GitCommandResult {
        return try {
            val process = ProcessBuilder(listOf("git", *args))
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readLines()
            GitCommandResult(
                success = process.waitFor() == 0,
                lines = output.filter(String::isNotBlank),
            )
        } catch (_: Exception) {
            GitCommandResult(success = false, lines = emptyList())
        }
    }
}
