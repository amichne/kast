package io.github.amichne.kast.idea.transition

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun interface GitWorktreeTransitionGuard {
    fun inspect(): GitWorktreeTransitionStatus

    companion object {
        fun stable(): GitWorktreeTransitionGuard = GitWorktreeTransitionGuard {
            GitWorktreeTransitionStatus.Stable
        }

        fun exactRoot(workspaceRoot: Path): GitWorktreeTransitionGuard =
            ResolvedGitWorktreeTransitionGuard(workspaceRoot)
    }
}

internal sealed interface GitWorktreeTransitionStatus {
    data object Stable : GitWorktreeTransitionStatus

    data class MissingLinkedWorktreeGitDirectory(
        val gitFile: Path,
        val gitDirectory: Path,
    ) : GitWorktreeTransitionStatus {
        init {
            require(gitFile.isAbsolute) { "Linked-worktree Git file must be absolute" }
            require(gitDirectory.isAbsolute) { "Linked-worktree Git directory must be absolute" }
        }
    }

    data class Unavailable(val detail: String) : GitWorktreeTransitionStatus {
        init {
            require(detail.isNotBlank()) { "Unavailable Git worktree transition evidence requires detail" }
        }
    }

    data class InProgress(
        val markers: Set<GitWorktreeTransitionMarkerEvidence>,
    ) : GitWorktreeTransitionStatus {
        init {
            require(markers.isNotEmpty()) { "An in-progress Git worktree transition requires marker evidence" }
        }
    }
}

internal enum class GitWorktreeTransitionMarker(internal val gitPath: String) {
    INDEX_LOCK("index.lock"),
    HEAD_LOCK("HEAD.lock"),
    REBASE_MERGE("rebase-merge"),
    REBASE_APPLY("rebase-apply"),
    MERGE_HEAD("MERGE_HEAD"),
    CHERRY_PICK_HEAD("CHERRY_PICK_HEAD"),
    REVERT_HEAD("REVERT_HEAD"),
    SEQUENCER("sequencer"),
}

internal data class GitWorktreeTransitionMarkerEvidence(
    val marker: GitWorktreeTransitionMarker,
    val path: Path,
) {
    init {
        require(path.isAbsolute) { "Git worktree transition marker path must be absolute" }
    }
}

internal class GitWorktreeTransitionInProgressException(
    val transition: GitWorktreeTransitionStatus.InProgress,
) : WorkspaceTransitionRetryException(
    "Git worktree transition is in progress: " +
        transition.markers.joinToString { evidence -> "${evidence.marker.name}=${evidence.path}" },
)

internal class GitWorktreeTransitionInspectionException(
    val unavailable: GitWorktreeTransitionStatus.Unavailable,
) : IllegalStateException(unavailable.detail)

private class ResolvedGitWorktreeTransitionGuard(
    workspaceRoot: Path,
) : GitWorktreeTransitionGuard {
    private val root = workspaceRoot.toAbsolutePath().normalize()

    override fun inspect(): GitWorktreeTransitionStatus {
        val resolved = resolveMarkerPaths()
        if (resolved is GitMarkerPathResolution.NotGitWorktree) return GitWorktreeTransitionStatus.Stable
        if (resolved is GitMarkerPathResolution.MissingLinkedWorktreeGitDirectory) {
            return GitWorktreeTransitionStatus.MissingLinkedWorktreeGitDirectory(
                gitFile = resolved.gitFile,
                gitDirectory = resolved.gitDirectory,
            )
        }
        if (resolved is GitMarkerPathResolution.Unavailable) {
            return GitWorktreeTransitionStatus.Unavailable(resolved.detail)
        }
        val markers = linkedSetOf<GitWorktreeTransitionMarkerEvidence>()
        (resolved as GitMarkerPathResolution.Resolved).markers.forEach { evidence ->
            try {
                Files.readAttributes(
                    evidence.path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                markers += evidence
            } catch (_: NoSuchFileException) {
                // The exact-worktree marker is absent.
            } catch (failure: IOException) {
                return GitWorktreeTransitionStatus.Unavailable(
                    "Cannot inspect exact-worktree Git transition marker ${evidence.path}: " +
                        (failure.message ?: failure::class.qualifiedName.orEmpty()),
                )
            } catch (failure: SecurityException) {
                return GitWorktreeTransitionStatus.Unavailable(
                    "Cannot inspect exact-worktree Git transition marker ${evidence.path}: " +
                        (failure.message ?: failure::class.qualifiedName.orEmpty()),
                )
            }
        }
        return if (markers.isEmpty()) {
            GitWorktreeTransitionStatus.Stable
        } else {
            GitWorktreeTransitionStatus.InProgress(markers)
        }
    }

    private fun resolveMarkerPaths(): GitMarkerPathResolution {
        val command = buildList {
            add("git")
            add("rev-parse")
            add("--path-format=absolute")
            GitWorktreeTransitionMarker.entries.forEach { marker ->
                add("--git-path")
                add(marker.gitPath)
            }
        }
        val process = runCatching {
            ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()
        }.getOrElse { failure ->
            return unavailableOrNotGit(
                "Cannot inspect exact-worktree Git transition metadata: " +
                    (failure.message ?: failure::class.qualifiedName.orEmpty()),
            )
        }
        val output = process.inputStream.use { input -> input.readAllBytes().toString(Charsets.UTF_8) }
        if (process.waitFor() != 0) {
            return unavailableOrNotGit(
                output.trim().takeIf(String::isNotBlank)
                    ?: "Git could not resolve exact-worktree transition metadata for $root",
            )
        }
        val resolved = output.trimEnd('\n', '\r')
            .split('\n')
            .map { line -> line.removeSuffix("\r") }
        if (resolved.size != GitWorktreeTransitionMarker.entries.size) {
            return GitMarkerPathResolution.Unavailable(
                "Git returned ${resolved.size} transition paths; expected ${GitWorktreeTransitionMarker.entries.size}",
            )
        }
        return GitMarkerPathResolution.Resolved(
            GitWorktreeTransitionMarker.entries.zip(resolved)
            .mapTo(linkedSetOf()) { (marker, rawPath) ->
                val path = Path.of(rawPath).let { candidate ->
                    if (candidate.isAbsolute) candidate else root.resolve(candidate)
                }.toAbsolutePath().normalize()
                GitWorktreeTransitionMarkerEvidence(marker, path)
            },
        )
    }

    private fun unavailableOrNotGit(detail: String): GitMarkerPathResolution {
        var directory: Path? = root
        while (directory != null) {
            val currentDirectory = directory
            val gitFile = currentDirectory.resolve(".git")
            val attributes = try {
                Files.readAttributes(
                    gitFile,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                directory = currentDirectory.parent
                continue
            } catch (failure: IOException) {
                return GitMarkerPathResolution.Unavailable(
                    "Cannot inspect Git metadata at $gitFile: " +
                        (failure.message ?: failure::class.qualifiedName.orEmpty()),
                )
            } catch (failure: SecurityException) {
                return GitMarkerPathResolution.Unavailable(
                    "Cannot inspect Git metadata at $gitFile: " +
                        (failure.message ?: failure::class.qualifiedName.orEmpty()),
                )
            }
            return missingLinkedWorktreeGitDirectory(gitFile, attributes)
                ?: GitMarkerPathResolution.Unavailable(detail)
        }
        return GitMarkerPathResolution.NotGitWorktree
    }

    private fun missingLinkedWorktreeGitDirectory(
        gitFile: Path,
        gitFileAttributes: BasicFileAttributes,
    ): GitMarkerPathResolution.MissingLinkedWorktreeGitDirectory? {
        if (!gitFileAttributes.isRegularFile) return null
        val directive = try {
            Files.readString(gitFile).trimEnd('\n', '\r')
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        if ('\n' in directive || '\r' in directive || !directive.startsWith(GIT_DIRECTORY_PREFIX)) return null
        val rawGitDirectory = directive.removePrefix(GIT_DIRECTORY_PREFIX).takeIf(String::isNotBlank)
            ?: return null
        val parsed = try {
            Path.of(rawGitDirectory)
        } catch (_: InvalidPathException) {
            return null
        }
        val gitDirectory = (if (parsed.isAbsolute) parsed else gitFile.parent.resolve(parsed))
            .toAbsolutePath()
            .normalize()
        val worktreesDirectory = gitDirectory.parent ?: return null
        if (worktreesDirectory.fileName?.toString() != WORKTREES_DIRECTORY_NAME) return null
        if (readAttributesOrNull(worktreesDirectory)?.isDirectory != true) return null
        val commonGitDirectory = worktreesDirectory.parent ?: return null
        if (!isCommonGitDirectory(commonGitDirectory)) return null
        if (!Files.notExists(gitDirectory, LinkOption.NOFOLLOW_LINKS)) return null
        return GitMarkerPathResolution.MissingLinkedWorktreeGitDirectory(
            gitFile = gitFile.toAbsolutePath().normalize(),
            gitDirectory = gitDirectory,
        )
    }

    private fun isCommonGitDirectory(directory: Path): Boolean {
        val directoryAttributes = readAttributesOrNull(directory) ?: return false
        val headAttributes = readAttributesOrNull(directory.resolve("HEAD")) ?: return false
        val objectsAttributes = readAttributesOrNull(directory.resolve("objects")) ?: return false
        return directoryAttributes.isDirectory && headAttributes.isRegularFile && objectsAttributes.isDirectory
    }

    private fun readAttributesOrNull(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        private const val GIT_DIRECTORY_PREFIX = "gitdir: "
        private const val WORKTREES_DIRECTORY_NAME = "worktrees"
    }
}

private sealed interface GitMarkerPathResolution {
    data object NotGitWorktree : GitMarkerPathResolution

    data class MissingLinkedWorktreeGitDirectory(
        val gitFile: Path,
        val gitDirectory: Path,
    ) : GitMarkerPathResolution

    data class Unavailable(val detail: String) : GitMarkerPathResolution

    data class Resolved(
        val markers: Set<GitWorktreeTransitionMarkerEvidence>,
    ) : GitMarkerPathResolution
}
