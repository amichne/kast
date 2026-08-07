package io.github.amichne.kast.idea.transition

import io.github.amichne.kast.api.client.LinkedWorktreeLaunchClaim
import io.github.amichne.kast.api.client.ReadOnlyGitCommand
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

@JvmInline
internal value class GitMetadataFileSystemIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Git file-system identity must not be blank" }
    }
}

internal data class RegisteredLinkedWorktreeIdentity(
    val gitFile: Path,
    val gitDirectory: Path,
    val gitFileSystemIdentity: GitMetadataFileSystemIdentity,
    val gitDirectoryFileSystemIdentity: GitMetadataFileSystemIdentity,
) {
    init {
        require(gitFile.isAbsolute) { "Registered linked-worktree Git file must be absolute" }
        require(gitDirectory.isAbsolute) { "Registered linked-worktree Git directory must be absolute" }
    }
}

internal class GitWorktreeRegistrationProof private constructor(
    internal val identity: RegisteredLinkedWorktreeIdentity,
) {
    companion object {
        fun capture(
            workspaceRoot: Path,
            claim: LinkedWorktreeLaunchClaim,
        ): GitWorktreeRegistrationProof {
            val registeredBeforeGit = GitWorktreeRegistrationInspector.observe(workspaceRoot)
                as? GitWorktreeRegistrationObservation.Registered
                ?: error("Linked-worktree launch claim is not currently registered")
            check(registeredBeforeGit.identity.gitFile == claim.gitFile) {
                "Linked-worktree launch Git file does not match the exact workspace"
            }
            check(registeredBeforeGit.identity.gitDirectory == claim.gitDirectory) {
                "Linked-worktree launch Git directory does not match the registered workspace"
            }
            val resolvedByGit = resolveRegisteredWorktree(workspaceRoot)
                ?: error("Git does not resolve the linked-worktree launch claim")
            check(sameExistingPath(resolvedByGit.workspaceRoot, workspaceRoot)) {
                "Git resolved a different linked-worktree root"
            }
            check(sameExistingPath(resolvedByGit.gitDirectory, claim.gitDirectory)) {
                "Git resolved a different linked-worktree Git directory"
            }
            check(sameExistingPath(resolvedByGit.commonGitDirectory, claim.gitDirectory.parent.parent)) {
                "Git resolved a different common Git directory"
            }
            val registeredAfterGit = GitWorktreeRegistrationInspector.observe(workspaceRoot)
                as? GitWorktreeRegistrationObservation.Registered
                ?: error("Linked-worktree registration changed during launch proof capture")
            check(registeredAfterGit.identity == registeredBeforeGit.identity) {
                "Linked-worktree registration changed during launch proof capture"
            }
            return GitWorktreeRegistrationProof(registeredAfterGit.identity)
        }

        private fun sameExistingPath(first: Path, second: Path): Boolean = try {
            Files.isSameFile(first, second)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

        private fun resolveRegisteredWorktree(workspaceRoot: Path): ResolvedGitWorktreeRegistration? {
            val command = ReadOnlyGitCommand.linkedWorktreeRegistration()
            val process = runCatching {
                command.processBuilder().also { builder ->
                    GIT_REPOSITORY_SELECTION_ENVIRONMENT.forEach(builder.environment()::remove)
                }.directory(workspaceRoot.toFile()).redirectErrorStream(true).start()
            }.getOrNull() ?: return null
            val output = process.inputStream.use { input -> input.readAllBytes().toString(Charsets.UTF_8) }
            if (process.waitFor() != 0) return null
            val paths = output.trimEnd('\n', '\r').lineSequence()
                .map { raw -> Path.of(raw.removeSuffix("\r")).toAbsolutePath().normalize() }
                .toList()
            if (paths.size != 3) return null
            return ResolvedGitWorktreeRegistration(
                workspaceRoot = paths[0],
                gitDirectory = paths[1],
                commonGitDirectory = paths[2],
            )
        }

        private val GIT_REPOSITORY_SELECTION_ENVIRONMENT = setOf(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_COMMON_DIR",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_CEILING_DIRECTORIES",
            "GIT_DISCOVERY_ACROSS_FILESYSTEM",
        )
    }
}

private data class ResolvedGitWorktreeRegistration(
    val workspaceRoot: Path,
    val gitDirectory: Path,
    val commonGitDirectory: Path,
)

internal sealed interface GitWorktreeRegistrationObservation {
    data object NotLinked : GitWorktreeRegistrationObservation

    data class Registered(
        val identity: RegisteredLinkedWorktreeIdentity,
    ) : GitWorktreeRegistrationObservation

    data class ProvenMissingDirectory(
        val gitFile: Path,
        val gitDirectory: Path,
    ) : GitWorktreeRegistrationObservation

    data class UnprovenMissingDirectory(
        val gitFile: Path,
        val gitDirectory: Path,
    ) : GitWorktreeRegistrationObservation

    data class Unavailable(val detail: String) : GitWorktreeRegistrationObservation
}

internal object GitWorktreeRegistrationInspector {
    fun observe(
        workspaceRoot: Path,
        proof: GitWorktreeRegistrationProof? = null,
    ): GitWorktreeRegistrationObservation {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val expected = proof?.identity
        val gitFile = expected?.gitFile ?: root.resolve(".git")
        if (gitFile != root.resolve(".git")) {
            return unavailable("Launch proof Git file does not belong to exact workspace $root")
        }
        val gitFileAttributes = readAttributes(gitFile)
        if (gitFileAttributes == null) {
            return if (!Files.notExists(gitFile, LinkOption.NOFOLLOW_LINKS)) {
                unavailable("Cannot inspect linked-worktree Git file: $gitFile")
            } else if (expected == null) {
                GitWorktreeRegistrationObservation.NotLinked
            } else {
                unavailable("Registered linked-worktree Git file is unavailable: $gitFile")
            }
        }
        if (!gitFileAttributes.isRegularFile) {
            return if (expected == null && gitFileAttributes.isDirectory) {
                GitWorktreeRegistrationObservation.NotLinked
            } else {
                unavailable("Linked-worktree Git file is not a regular file: $gitFile")
            }
        }
        val gitDirectory = resolveGitDirectory(gitFile)
            ?: return unavailable("Linked-worktree Git file is malformed: $gitFile")
        if (expected != null && gitDirectory != expected.gitDirectory) {
            return unavailable("Linked-worktree Git directory changed after launch: $gitDirectory")
        }
        val worktreesDirectory = gitDirectory.parent
            ?: return GitWorktreeRegistrationObservation.NotLinked
        if (worktreesDirectory.fileName?.toString() != WORKTREES_DIRECTORY_NAME) {
            return if (expected == null) {
                GitWorktreeRegistrationObservation.NotLinked
            } else {
                unavailable("Launch proof Git directory is not a linked-worktree directory: $gitDirectory")
            }
        }
        val commonGitDirectory = worktreesDirectory.parent
            ?: return unavailable("Linked-worktree Git directory has no common Git directory: $gitDirectory")
        if (!isCommonGitDirectory(commonGitDirectory)) {
            return unavailable("Linked-worktree common Git directory is unavailable: $commonGitDirectory")
        }
        val fileSystemIdentity = gitFileAttributes.fileKey()
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?.let(::GitMetadataFileSystemIdentity)
            ?: return unavailable("Linked-worktree Git file has no stable file-system identity: $gitFile")
        if (expected != null && fileSystemIdentity != expected.gitFileSystemIdentity) {
            return unavailable("Linked-worktree Git file identity changed after launch: $gitFile")
        }
        val gitDirectoryAttributes = readAttributes(gitDirectory)
        if (gitDirectoryAttributes == null) {
            if (!Files.notExists(gitDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return unavailable("Cannot inspect linked-worktree Git directory: $gitDirectory")
            }
            return if (expected == null) {
                GitWorktreeRegistrationObservation.UnprovenMissingDirectory(gitFile, gitDirectory)
            } else {
                GitWorktreeRegistrationObservation.ProvenMissingDirectory(gitFile, gitDirectory)
            }
        }
        if (!gitDirectoryAttributes.isDirectory) {
            return unavailable("Linked-worktree Git directory is not a directory: $gitDirectory")
        }
        val gitDirectoryFileSystemIdentity = gitDirectoryAttributes.fileKey()
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?.let(::GitMetadataFileSystemIdentity)
            ?: return unavailable("Linked-worktree Git directory has no stable file-system identity: $gitDirectory")
        if (expected != null && gitDirectoryFileSystemIdentity != expected.gitDirectoryFileSystemIdentity) {
            return unavailable("Linked-worktree Git directory identity changed after launch: $gitDirectory")
        }
        val registrationFile = gitDirectory.resolve(GIT_FILE_REGISTRATION_NAME)
        val registeredGitFile = resolvePathFile(registrationFile)
            ?: return unavailable("Linked-worktree registration backlink is unavailable: $registrationFile")
        val sameGitFile = try {
            Files.isSameFile(registeredGitFile, gitFile)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (!sameGitFile) {
            return unavailable("Linked-worktree registration backlink does not match $gitFile")
        }
        val identity = RegisteredLinkedWorktreeIdentity(
            gitFile = gitFile,
            gitDirectory = gitDirectory,
            gitFileSystemIdentity = fileSystemIdentity,
            gitDirectoryFileSystemIdentity = gitDirectoryFileSystemIdentity,
        )
        if (expected != null && identity != expected) {
            return unavailable("Linked-worktree registration identity changed after launch: $gitFile")
        }
        return GitWorktreeRegistrationObservation.Registered(identity)
    }

    private fun resolveGitDirectory(gitFile: Path): Path? {
        val directive = readSingleLine(gitFile) ?: return null
        if (!directive.startsWith(GIT_DIRECTORY_PREFIX)) return null
        val rawGitDirectory = directive.removePrefix(GIT_DIRECTORY_PREFIX).takeIf(String::isNotBlank)
            ?: return null
        return resolvePath(rawGitDirectory, gitFile.parent)
    }

    private fun resolvePathFile(path: Path): Path? {
        val rawPath = readSingleLine(path)?.takeIf(String::isNotBlank) ?: return null
        return resolvePath(rawPath, path.parent)
    }

    private fun readSingleLine(path: Path): String? = try {
        Files.readString(path).trimEnd('\n', '\r').takeUnless { value -> '\n' in value || '\r' in value }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun resolvePath(rawPath: String, relativeTo: Path): Path? {
        val parsed = try {
            Path.of(rawPath)
        } catch (_: InvalidPathException) {
            return null
        }
        return (if (parsed.isAbsolute) parsed else relativeTo.resolve(parsed)).toAbsolutePath().normalize()
    }

    private fun isCommonGitDirectory(directory: Path): Boolean {
        val directoryAttributes = readAttributes(directory) ?: return false
        val headAttributes = readAttributes(directory.resolve("HEAD")) ?: return false
        val objectsAttributes = readAttributes(directory.resolve("objects")) ?: return false
        return directoryAttributes.isDirectory && headAttributes.isRegularFile && objectsAttributes.isDirectory
    }

    private fun readAttributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun unavailable(detail: String): GitWorktreeRegistrationObservation.Unavailable =
        GitWorktreeRegistrationObservation.Unavailable(detail)

    private const val GIT_DIRECTORY_PREFIX = "gitdir: "
    private const val GIT_FILE_REGISTRATION_NAME = "gitdir"
    private const val WORKTREES_DIRECTORY_NAME = "worktrees"
}
