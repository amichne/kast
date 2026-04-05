package io.github.amichne.kast.server

import io.github.amichne.kast.api.ServerInstanceDescriptor
import io.github.amichne.kast.api.workspaceMetadataDirectory
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DescriptorStore(
    private val directory: Path,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun write(descriptor: ServerInstanceDescriptor): Path {
        ensureWorkspaceMetadataIgnored(descriptor)
        Files.createDirectories(directory)
        val path = pathFor(descriptor)
        path.writeText(json.encodeToString(ServerInstanceDescriptor.serializer(), descriptor))
        return path
    }

    fun delete(descriptor: ServerInstanceDescriptor) {
        pathFor(descriptor).deleteIfExists()
    }

    fun pathFor(descriptor: ServerInstanceDescriptor): Path {
        val identity = FileNameHasher.hash("${descriptor.backendName}:${descriptor.workspaceRoot}")
        return directory.resolve("$identity.json")
    }

    private fun ensureWorkspaceMetadataIgnored(descriptor: ServerInstanceDescriptor) {
        val workspaceRoot = Path.of(descriptor.workspaceRoot).toAbsolutePath().normalize()
        val metadataDirectory = workspaceMetadataDirectory(workspaceRoot)
        if (!directory.toAbsolutePath().normalize().startsWith(metadataDirectory)) {
            return
        }

        WorkspaceGitExclude.ensureIgnored(
            workspaceRoot = workspaceRoot,
            pathToIgnore = metadataDirectory,
        )
    }
}

private object FileNameHasher {
    fun hash(input: String): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
}

private object WorkspaceGitExclude {
    private const val header = "# Kast local workspace metadata"

    fun ensureIgnored(
        workspaceRoot: Path,
        pathToIgnore: Path,
    ) {
        val repository = findRepositoryMetadata(workspaceRoot) ?: return
        val normalizedIgnorePath = pathToIgnore.toAbsolutePath().normalize()
        if (!normalizedIgnorePath.startsWith(repository.repoRoot)) {
            return
        }

        val relativePath = repository.repoRoot
            .relativize(normalizedIgnorePath)
            .toString()
            .replace('\\', '/')
            .trim('/')
        if (relativePath.isEmpty()) {
            return
        }

        val entry = "/$relativePath/"
        val excludeFile = repository.gitDirectory.resolve("info").resolve("exclude")
        Files.createDirectories(excludeFile.parent)
        val existingContent = if (excludeFile.exists()) excludeFile.readText() else ""
        val existingLines = existingContent.lines().map(String::trim)
        if (existingLines.any { it == entry }) {
            return
        }

        val updatedContent = buildString {
            if (existingContent.isNotEmpty()) {
                append(existingContent)
                if (!existingContent.endsWith('\n')) {
                    append('\n')
                }
            }
            if (existingLines.none { it == header }) {
                append(header)
                append('\n')
            }
            append(entry)
            append('\n')
        }
        excludeFile.writeText(updatedContent)
    }

    private fun findRepositoryMetadata(workspaceRoot: Path): RepositoryMetadata? {
        var candidate: Path? = workspaceRoot.toAbsolutePath().normalize()
        while (candidate != null) {
            val dotGit = candidate.resolve(".git")
            if (Files.isDirectory(dotGit)) {
                return RepositoryMetadata(repoRoot = candidate, gitDirectory = dotGit)
            }
            if (Files.isRegularFile(dotGit)) {
                val gitDirectory = parseGitDirectory(dotGit, candidate) ?: return null
                return RepositoryMetadata(repoRoot = candidate, gitDirectory = gitDirectory)
            }
            candidate = candidate.parent
        }
        return null
    }

    private fun parseGitDirectory(
        dotGitFile: Path,
        repoRoot: Path,
    ): Path? {
        val content = runCatching { dotGitFile.readText() }.getOrNull()?.trim() ?: return null
        val prefix = "gitdir:"
        if (!content.startsWith(prefix)) {
            return null
        }

        return Path.of(content.removePrefix(prefix).trim())
            .let { gitDirectory ->
                if (gitDirectory.isAbsolute) gitDirectory else repoRoot.resolve(gitDirectory)
            }
            .toAbsolutePath()
            .normalize()
    }

    private data class RepositoryMetadata(
        val repoRoot: Path,
        val gitDirectory: Path,
    )
}
