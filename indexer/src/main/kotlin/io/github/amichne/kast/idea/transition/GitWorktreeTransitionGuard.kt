package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.api.client.ReadOnlyGitCommand
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

        fun exactRoot(
            workspaceRoot: Path,
            registrationProof: GitWorktreeRegistrationProof? = null,
            markerReader: GitWorktreeTransitionMarkerReader = GitWorktreeTransitionMarkerReader.filesystem(),
            resolutionObserver: GitWorktreeTransitionResolutionObserver =
                GitWorktreeTransitionResolutionObserver.noop(),
        ): GitWorktreeTransitionGuard = ResolvedGitWorktreeTransitionGuard(
            workspaceRoot,
            registrationProof,
            markerReader,
            resolutionObserver,
        )
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

private enum class GitRepositorySelectionEnvironment(val variable: String) {
    GIT_DIR("GIT_DIR"),
    GIT_WORK_TREE("GIT_WORK_TREE"),
    GIT_COMMON_DIR("GIT_COMMON_DIR"),
    GIT_INDEX_FILE("GIT_INDEX_FILE"),
    GIT_OBJECT_DIRECTORY("GIT_OBJECT_DIRECTORY"),
    GIT_ALTERNATE_OBJECT_DIRECTORIES("GIT_ALTERNATE_OBJECT_DIRECTORIES"),
    GIT_CEILING_DIRECTORIES("GIT_CEILING_DIRECTORIES"),
    GIT_DISCOVERY_ACROSS_FILESYSTEM("GIT_DISCOVERY_ACROSS_FILESYSTEM"),
}

internal data class GitWorktreeTransitionMarkerEvidence(
    val marker: GitWorktreeTransitionMarker,
    val path: Path,
) {
    init {
        require(path.isAbsolute) { "Git worktree transition marker path must be absolute" }
    }
}

internal fun interface GitWorktreeTransitionMarkerReader {
    @Throws(IOException::class, SecurityException::class)
    fun read(path: Path)

    companion object {
        fun filesystem(): GitWorktreeTransitionMarkerReader = GitWorktreeTransitionMarkerReader { path ->
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
    }
}

internal fun interface GitWorktreeTransitionResolutionObserver {
    fun resolved()

    companion object {
        fun noop(): GitWorktreeTransitionResolutionObserver = GitWorktreeTransitionResolutionObserver {}
    }
}

internal sealed class WorkspaceTransitionRetryException(
    message: String,
) : IllegalStateException(message)

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
    private val registrationProof: GitWorktreeRegistrationProof?,
    private val markerReader: GitWorktreeTransitionMarkerReader,
    private val resolutionObserver: GitWorktreeTransitionResolutionObserver,
) : GitWorktreeTransitionGuard {
    private val root = workspaceRoot.toAbsolutePath().normalize()

    override fun inspect(): GitWorktreeTransitionStatus {
        val resolved = resolveMarkerPaths()
        if (resolved is GitMarkerPathResolution.NotGitWorktree) return GitWorktreeTransitionStatus.Stable
        if (resolved is GitMarkerPathResolution.MissingLinkedWorktreeGitDirectory) {
            return resolved.toStatus()
        }
        if (resolved is GitMarkerPathResolution.Unavailable) {
            return GitWorktreeTransitionStatus.Unavailable(resolved.detail)
        }
        resolved as GitMarkerPathResolution.Resolved
        resolutionObserver.resolved()
        val registrationBeforeMarkers = GitWorktreeRegistrationInspector.observe(root, registrationProof)
        val registrationIdentity = when (registrationBeforeMarkers) {
            GitWorktreeRegistrationObservation.NotLinked -> null
            is GitWorktreeRegistrationObservation.Registered -> registrationBeforeMarkers.identity
            is GitWorktreeRegistrationObservation.ProvenMissingDirectory ->
                return registrationBeforeMarkers.toStatus()
            is GitWorktreeRegistrationObservation.UnprovenMissingDirectory ->
                return registrationBeforeMarkers.unprovenStatus()
            is GitWorktreeRegistrationObservation.Unavailable ->
                return GitWorktreeTransitionStatus.Unavailable(registrationBeforeMarkers.detail)
        }
        resolutionMismatch(resolved, registrationIdentity)?.let { detail ->
            return GitWorktreeTransitionStatus.Unavailable(detail)
        }
        val markers = linkedSetOf<GitWorktreeTransitionMarkerEvidence>()
        resolved.markers.forEach { evidence ->
            try {
                markerReader.read(evidence.path)
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
        when (val registrationAfterMarkers = GitWorktreeRegistrationInspector.observe(root, registrationProof)) {
            is GitWorktreeRegistrationObservation.ProvenMissingDirectory ->
                return registrationAfterMarkers.toStatus()
            is GitWorktreeRegistrationObservation.UnprovenMissingDirectory ->
                return registrationAfterMarkers.unprovenStatus()
            is GitWorktreeRegistrationObservation.Unavailable ->
                return GitWorktreeTransitionStatus.Unavailable(registrationAfterMarkers.detail)
            is GitWorktreeRegistrationObservation.Registered -> {
                if (registrationAfterMarkers.identity != registrationIdentity) {
                    return GitWorktreeTransitionStatus.Unavailable(
                        "Linked-worktree registration changed while transition markers were inspected: $root",
                    )
                }
            }
            GitWorktreeRegistrationObservation.NotLinked -> {
                if (registrationIdentity != null) {
                    return GitWorktreeTransitionStatus.Unavailable(
                        "Linked-worktree registration disappeared while transition markers were inspected: $root",
                    )
                }
            }
        }
        return if (markers.isEmpty()) {
            GitWorktreeTransitionStatus.Stable
        } else {
            GitWorktreeTransitionStatus.InProgress(markers)
        }
    }

    private fun resolveMarkerPaths(): GitMarkerPathResolution {
        val command = ReadOnlyGitCommand.transitionMarkerPaths(
            GitWorktreeTransitionMarker.entries.map(GitWorktreeTransitionMarker::gitPath),
        )
        val process = runCatching {
            command.processBuilder().also { builder ->
                GitRepositorySelectionEnvironment.entries.forEach { selection ->
                    builder.environment().remove(selection.variable)
                }
            }
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
        val expectedPathCount = GitWorktreeTransitionMarker.entries.size + GIT_IDENTITY_PATH_COUNT
        if (resolved.size != expectedPathCount) {
            return GitMarkerPathResolution.Unavailable(
                "Git returned ${resolved.size} identity and transition paths; expected $expectedPathCount",
            )
        }
        val paths = resolved.map { rawPath ->
            resolveOutputPath(rawPath) ?: return GitMarkerPathResolution.Unavailable(
                "Git returned an invalid exact-worktree metadata path: $rawPath",
            )
        }
        return GitMarkerPathResolution.Resolved(
            workspaceRoot = paths[0],
            gitDirectory = paths[1],
            commonGitDirectory = paths[2],
            markers = GitWorktreeTransitionMarker.entries.zip(paths.drop(GIT_IDENTITY_PATH_COUNT))
                .mapTo(linkedSetOf()) { (marker, path) ->
                    GitWorktreeTransitionMarkerEvidence(marker, path)
                },
        )
    }

    private fun resolveOutputPath(rawPath: String): Path? {
        val candidate = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            return null
        }
        return (if (candidate.isAbsolute) candidate else root.resolve(candidate))
            .toAbsolutePath()
            .normalize()
    }

    private fun resolutionMismatch(
        resolved: GitMarkerPathResolution.Resolved,
        registrationIdentity: RegisteredLinkedWorktreeIdentity?,
    ): String? {
        if (!sameExistingPath(resolved.workspaceRoot, root)) {
            return "Git resolved transition markers for a different workspace root: ${resolved.workspaceRoot}"
        }
        if (registrationIdentity == null) return null
        if (!sameExistingPath(resolved.gitDirectory, registrationIdentity.gitDirectory)) {
            return "Git resolved transition markers for a different linked-worktree Git directory: " +
                   resolved.gitDirectory
        }
        val registeredCommonGitDirectory = registrationIdentity.gitDirectory.parent?.parent
                                           ?: return "Registered linked-worktree Git directory has no common Git directory"
        if (!sameExistingPath(resolved.commonGitDirectory, registeredCommonGitDirectory)) {
            return "Git resolved transition markers for a different common Git directory: " +
                   resolved.commonGitDirectory
        }
        return null
    }

    private fun sameExistingPath(
        first: Path,
        second: Path,
    ): Boolean = try {
        Files.isSameFile(first, second)
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun unavailableOrNotGit(detail: String): GitMarkerPathResolution {
        when (val registration = GitWorktreeRegistrationInspector.observe(root, registrationProof)) {
            is GitWorktreeRegistrationObservation.ProvenMissingDirectory -> {
                return GitMarkerPathResolution.MissingLinkedWorktreeGitDirectory(
                    registration.gitFile,
                    registration.gitDirectory,
                )
            }
            is GitWorktreeRegistrationObservation.UnprovenMissingDirectory -> {
                return GitMarkerPathResolution.Unavailable(
                    "Missing linked-worktree Git directory has no exact launch registration proof: " +
                    registration.gitDirectory,
                )
            }
            is GitWorktreeRegistrationObservation.Unavailable -> {
                return GitMarkerPathResolution.Unavailable(registration.detail)
            }
            is GitWorktreeRegistrationObservation.Registered -> {
                return GitMarkerPathResolution.Unavailable(detail)
            }
            GitWorktreeRegistrationObservation.NotLinked -> Unit
        }
        var directory: Path? = root
        while (directory != null) {
            val currentDirectory = directory
            val gitFile = currentDirectory.resolve(".git")
            try {
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
            return GitMarkerPathResolution.Unavailable(detail)
        }
        return GitMarkerPathResolution.NotGitWorktree
    }

    private companion object {
        private const val GIT_IDENTITY_PATH_COUNT = 3
    }
}

private fun GitWorktreeRegistrationObservation.ProvenMissingDirectory.toStatus() =
    GitWorktreeTransitionStatus.MissingLinkedWorktreeGitDirectory(gitFile, gitDirectory)

private fun GitWorktreeRegistrationObservation.UnprovenMissingDirectory.unprovenStatus() =
    GitWorktreeTransitionStatus.Unavailable(
        "Missing linked-worktree Git directory has no exact launch registration proof: $gitDirectory",
    )

private fun GitMarkerPathResolution.MissingLinkedWorktreeGitDirectory.toStatus() =
    GitWorktreeTransitionStatus.MissingLinkedWorktreeGitDirectory(gitFile, gitDirectory)

private sealed interface GitMarkerPathResolution {
    data object NotGitWorktree : GitMarkerPathResolution

    data class MissingLinkedWorktreeGitDirectory(
        val gitFile: Path,
        val gitDirectory: Path,
    ) : GitMarkerPathResolution

    data class Unavailable(val detail: String) : GitMarkerPathResolution

    data class Resolved(
        val workspaceRoot: Path,
        val gitDirectory: Path,
        val commonGitDirectory: Path,
        val markers: Set<GitWorktreeTransitionMarkerEvidence>,
    ) : GitMarkerPathResolution
}
