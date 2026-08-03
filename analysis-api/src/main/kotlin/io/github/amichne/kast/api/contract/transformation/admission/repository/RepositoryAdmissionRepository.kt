package io.github.amichne.kast.api.contract.transformation.admission.repository

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

internal class RepositoryAdmissionRepository(
    private val authorityReadCheckpoint: GitAuthorityReadCheckpoint,
) {
    fun parseRoot(input: RawRepositoryInput): CanonicalRepositoryRoot {
        val requestedRoot = input.requestedRoot?.takeIf(String::isNotBlank)
            ?: reject(RepositoryOperationRejection.RepositoryRootUnresolvable(input.requestedRoot))
        val requestedPath = runCatching { Path.of(requestedRoot) }.getOrNull()
            ?: reject(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        val candidate = if (requestedPath.isAbsolute) {
            requestedPath
        } else {
            val base = input.baseDirectory
                ?.takeIf(String::isNotBlank)
                ?.let { raw -> runCatching { Path.of(raw) }.getOrNull() }
                ?: reject(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
            base.resolve(requestedPath)
        }.normalize()
        if (!Files.isDirectory(candidate)) {
            reject(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        }
        val canonical = runCatching { candidate.toRealPath().normalize() }.getOrNull()
            ?: reject(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        if (!Files.exists(canonical.resolve(".git"))) {
            reject(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        }
        val topLevel = gitOutput(canonical, "rev-parse", "--show-toplevel")
            ?.let { raw -> runCatching { Path.of(raw).toRealPath().normalize() }.getOrNull() }
        if (topLevel != canonical || !hasRegisteredGitAuthority(canonical)) {
            reject(RepositoryOperationRejection.RepositoryRootUnresolvable(requestedRoot))
        }
        return CanonicalRepositoryRoot.fromValidated(canonical.toString())
    }

    fun parsePath(
        root: CanonicalRepositoryRoot,
        raw: String,
    ): RepositoryRelativePath {
        val rootPath = Path.of(root.value)
        val rawPath = runCatching { Path.of(raw) }.getOrNull()
            ?: reject(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
        val candidate = (if (rawPath.isAbsolute) rawPath else rootPath.resolve(rawPath))
            .toAbsolutePath()
            .normalize()
        if (!candidate.startsWith(rootPath)) {
            reject(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
        }
        val lexicalRelativePath = rootPath.relativize(candidate).normalize()
        var current = rootPath
        for (segment in lexicalRelativePath) {
            current = current.resolve(segment)
            val attributes = try {
                Files.readAttributes(
                    current,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                break
            } catch (_: Exception) {
                reject(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
            }
            if (attributes.isSymbolicLink) {
                reject(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
            }
            val canonicalCurrent = runCatching { current.toRealPath().normalize() }.getOrNull()
                ?: reject(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
            if (!canonicalCurrent.startsWith(rootPath)) {
                reject(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
            }
        }
        val relative = lexicalRelativePath.portablePath()
        if (relative.isBlank()) {
            reject(RepositoryOperationRejection.RepositoryPathOutsideRoot(raw))
        }
        return RepositoryRelativePath.fromValidated(relative)
    }

    private fun hasRegisteredGitAuthority(root: Path): Boolean {
        val dotGit = root.resolve(".git")
        val attributes = runCatching {
            Files.readAttributes(dotGit, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return false
        if (attributes.isSymbolicLink) return false
        val actualGitDirectory = gitOutput(
            root,
            "rev-parse",
            "--path-format=absolute",
            "--absolute-git-dir",
        )?.let(::canonicalExistingPath) ?: return false
        val commonGitDirectory = gitOutput(
            root,
            "rev-parse",
            "--path-format=absolute",
            "--git-common-dir",
        )?.let(::canonicalExistingPath) ?: return false
        if (attributes.isDirectory) {
            return canonicalExistingPath(dotGit.toString()) == actualGitDirectory &&
                actualGitDirectory == commonGitDirectory
        }
        if (!attributes.isRegularFile) return false
        val declaredGitDirectory = readGitDirectoryReference(dotGit)
            ?.let(::canonicalExistingPath)
            ?: return false
        if (declaredGitDirectory != actualGitDirectory) return false

        val worktreeRegistry = runCatching {
            commonGitDirectory.resolve("worktrees").toRealPath().normalize()
        }.getOrNull()
        val registeredWorktree = worktreeRegistry != null &&
            actualGitDirectory.parent == worktreeRegistry &&
            readPathReference(actualGitDirectory.resolve("gitdir")) == dotGit.toAbsolutePath().normalize()
        if (registeredWorktree) return true

        val configuredWorktree = gitOutput(root, "config", "--path", "--get", "core.worktree")
            ?.let { raw ->
                val rawPath = runCatching { Path.of(raw) }.getOrNull() ?: return@let null
                val resolved = if (rawPath.isAbsolute) rawPath else actualGitDirectory.resolve(rawPath)
                runCatching { resolved.toRealPath().normalize() }.getOrNull()
            }
        return actualGitDirectory == commonGitDirectory && configuredWorktree == root
    }

    private fun canonicalExistingPath(raw: String): Path? = runCatching {
        Path.of(raw).toRealPath().normalize()
    }.getOrNull()

    private fun readGitDirectoryReference(dotGit: Path): String? {
        val value = readSmallTextFile(dotGit) ?: return null
        val rawPath = value.removePrefix(GIT_DIRECTORY_PREFIX)
        if (rawPath == value || rawPath.isBlank()) return null
        val path = runCatching { Path.of(rawPath) }.getOrNull() ?: return null
        return (if (path.isAbsolute) path else dotGit.parent.resolve(path)).normalize().toString()
    }

    private fun readPathReference(path: Path): Path? {
        val value = readSmallTextFile(path) ?: return null
        val rawPath = runCatching { Path.of(value) }.getOrNull() ?: return null
        return (if (rawPath.isAbsolute) rawPath else path.parent.resolve(rawPath))
            .toAbsolutePath()
            .normalize()
    }

    private fun readSmallTextFile(path: Path): String? = try {
        val before = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!before.isRegularFile || before.size() > MAXIMUM_GIT_AUTHORITY_FILE_BYTES) return null
        val canonicalBefore = path.toRealPath().normalize()
        authorityReadCheckpoint.beforeControlFileRead(path)
        val content = FileChannel.open(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.allocate(MAXIMUM_GIT_AUTHORITY_FILE_BYTES.toInt() + 1)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            if (!buffer.hasRemaining()) return null
            buffer.flip()
            StandardCharsets.UTF_8.decode(buffer).toString().trim()
        }
        val after = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val canonicalAfter = path.toRealPath().normalize()
        if (
            !after.isRegularFile ||
            canonicalAfter != canonicalBefore ||
            after.fileKey() != before.fileKey() ||
            after.size() != before.size() ||
            after.lastModifiedTime() != before.lastModifiedTime()
        ) {
            null
        } else {
            content
        }
    } catch (_: Exception) {
        null
    }

    private fun Path.portablePath(): String = joinToString("/") { segment -> segment.toString() }

    private companion object {
        const val MAXIMUM_GIT_AUTHORITY_FILE_BYTES: Long = 4_096
        const val GIT_DIRECTORY_PREFIX: String = "gitdir: "
    }
}

internal fun gitOutput(
    workingDirectory: Path,
    vararg arguments: String,
): String? {
    var process: Process? = null
    return try {
        process = gitProcessBuilder(workingDirectory, *arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
                .takeIf { output -> process.exitValue() == 0 && output.isNotBlank() }
        }
    } catch (_: InterruptedException) {
        process?.destroyForcibly()
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        process?.destroyForcibly()
        null
    }
}

internal fun gitProcessBuilder(
    workingDirectory: Path,
    vararg arguments: String,
): ProcessBuilder = ProcessBuilder("git", *arguments).also { builder ->
    builder.directory(workingDirectory.toFile())
    GIT_ENVIRONMENT_OVERRIDES.forEach { variable -> builder.environment().remove(variable) }
}

internal fun RepositoryRelativePath.isWithin(root: RepositoryRelativePath): Boolean =
    Path.of(value).startsWith(Path.of(root.value))

internal fun interface GitAuthorityReadCheckpoint {
    fun beforeControlFileRead(path: Path)

    companion object {
        val NO_OP: GitAuthorityReadCheckpoint = GitAuthorityReadCheckpoint {}
    }
}

private const val GIT_TIMEOUT_SECONDS: Long = 5

private val GIT_ENVIRONMENT_OVERRIDES: Set<String> = setOf(
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_CEILING_DIRECTORIES",
    "GIT_COMMON_DIR",
    "GIT_DIR",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
    "GIT_INDEX_FILE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_WORK_TREE",
)
