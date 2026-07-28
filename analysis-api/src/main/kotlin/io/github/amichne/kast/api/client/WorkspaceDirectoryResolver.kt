package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class WorkspaceDirectoryLayout(
    val repositoryDataDirectory: Path?,
    val workspaceDataDirectory: Path,
) {
    val workspaceCacheDirectory: Path
        get() = workspaceDataDirectory.resolve("cache")

    val workspaceDatabasePath: Path
        get() = workspaceCacheDirectory.resolve("source-index.db")
}

class WorkspaceDirectoryResolver(
    private val installRoot: () -> Path = ::kastInstallRoot,
    private val dataRoot: () -> Path = { kastDataRoot(System::getenv, installRoot()) },
    private val gitWorkspaceResolver: (Path) -> GitWorkspace? = GitWorkspaceResolver::discover,
) {
    internal fun resolveLayout(workspaceRoot: Path): WorkspaceDirectoryLayout {
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        val gitWorkspace = gitWorkspaceResolver(normalizedRoot)
        val repositoryDataDirectory = gitWorkspace?.let {
            gitRepositoryDataDirectory(it)
        }
        val workspaceDataDirectory = if (gitWorkspace != null) {
            val repositoryDirectory = requireNotNull(repositoryDataDirectory)
            val leaf = "${workspaceSlug(gitWorkspace.toplevel)}--" +
                gitWorktreeHash(gitWorkspace.toplevel, gitWorkspace.gitDir)
            val target = repositoryDirectory
                .resolve("worktrees")
                .resolve(leaf)
            migrateLegacyGitWorkspaceState(target, leaf)
            target
        } else {
            workspacesRoot()
                .resolve("local")
                .resolve("${sanitizedPath(normalizedRoot)}--${localWorkspaceId(normalizedRoot)}")
        }.toAbsolutePath().normalize()
        return WorkspaceDirectoryLayout(
            repositoryDataDirectory = repositoryDataDirectory,
            workspaceDataDirectory = workspaceDataDirectory,
        )
    }

    fun workspaceDataDirectory(workspaceRoot: Path): Path =
        resolveLayout(workspaceRoot).workspaceDataDirectory

    fun repositoryDataDirectory(workspaceRoot: Path): Path? =
        resolveLayout(workspaceRoot).repositoryDataDirectory

    fun workspaceCacheDirectory(workspaceRoot: Path): Path =
        resolveLayout(workspaceRoot).workspaceCacheDirectory

    fun workspaceDatabasePath(workspaceRoot: Path): Path =
        resolveLayout(workspaceRoot).workspaceDatabasePath

    fun workspaceIdentity(
        workspaceRoot: Path,
        descriptorDirectory: Path = defaultDescriptorDirectory(),
    ): WorkspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot, this, descriptorDirectory)

    fun workspaceHash(workspaceRoot: Path): String = FileHashing.sha256(
        workspaceRoot.toAbsolutePath().normalize().toString(),
    ).take(12)

    private fun workspacesRoot(): Path = dataRoot().resolve("workspaces").toAbsolutePath().normalize()

    private fun gitRepositoryDataDirectory(workspace: GitWorkspace): Path =
        workspacesRoot()
            .resolve("git")
            .resolve("local")
            .resolve(gitCommonDirHash(workspace.commonDir))

    private fun migrateLegacyGitWorkspaceState(
        target: Path,
        leaf: String,
    ) {
        val targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        if (targetExists && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw migrationFailure(
                code = migrationConflictCode,
                message = "Stable Kast workspace state is not a directory: $target",
                target = target,
            )
        }
        val legacy = legacyGitWorkspaceDirectories(leaf)
        when {
            targetExists && legacy.isEmpty() -> return
            targetExists -> throw migrationFailure(
                code = migrationConflictCode,
                message = "Stable and legacy Kast workspace state both exist for $leaf",
                target = target,
                legacy = legacy,
            )
            legacy.isEmpty() -> return
            legacy.size > 1 -> throw migrationFailure(
                code = migrationAmbiguousCode,
                message = "Multiple legacy Kast workspace directories match $leaf",
                target = target,
                legacy = legacy,
            )
        }
        val source = legacy.single()
        Files.createDirectories(target.parent)
        runCatching {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse { failure ->
            val remaining = legacyGitWorkspaceDirectories(leaf)
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && remaining.isEmpty()) {
                return
            }
            throw migrationFailure(
                code = migrationFailedCode,
                message = "Could not atomically migrate Kast workspace state from $source to $target",
                target = target,
                legacy = remaining.ifEmpty { listOf(source) },
                cause = failure,
            )
        }
    }

    private fun legacyGitWorkspaceDirectories(leaf: String): List<Path> {
        val gitRoot = workspacesRoot().resolve("git")
        if (!Files.isDirectory(gitRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val candidates = mutableListOf<Path>()
        val pending = ArrayDeque<Pair<Path, Int>>()
        childDirectories(gitRoot)
            .filterNot { host -> host.fileName.toString() == "local" }
            .forEach { host ->
                childDirectories(host).forEach { owner ->
                    childDirectories(owner).forEach { repositorySegment ->
                        pending.addLast(repositorySegment to 1)
                    }
                }
            }
        while (pending.isNotEmpty()) {
            val (repositoryPath, depth) = pending.removeLast()
            val worktrees = repositoryPath.resolve("worktrees")
            if (Files.isDirectory(worktrees, LinkOption.NOFOLLOW_LINKS)) {
                val candidate = worktrees.resolve(leaf)
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        throw migrationFailure(
                            code = migrationConflictCode,
                            message = "Legacy Kast workspace state is not a directory: $candidate",
                            target = candidate,
                        )
                    }
                    candidates.add(candidate.toAbsolutePath().normalize())
                }
                continue
            }
            val children = childDirectories(repositoryPath)
            if (children.isNotEmpty() && depth >= maxLegacyRepositoryDepth) {
                throw migrationFailure(
                    code = migrationDepthExceededCode,
                    message = "Legacy Kast repository state exceeds $maxLegacyRepositoryDepth nested path segments",
                    target = repositoryPath,
                )
            }
            children.asReversed().forEach { child ->
                pending.addLast(child to (depth + 1))
            }
        }
        return candidates.sortedBy(Path::toString)
    }

    private fun childDirectories(parent: Path): List<Path> {
        val directories = mutableListOf<Path>()
        Files.newDirectoryStream(parent).use { entries ->
            entries.forEach { entry ->
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    directories.add(entry)
                }
            }
        }
        return directories.sortedBy(Path::toString)
    }

    private fun migrationFailure(
        code: String,
        message: String,
        target: Path,
        legacy: List<Path> = emptyList(),
        cause: Throwable? = null,
    ): AnalysisException = AnalysisException(
        statusCode = if (code == migrationFailedCode) 500 else 409,
        errorCode = code,
        message = message,
        details = buildMap {
            put("target", target.toString())
            if (legacy.isNotEmpty()) {
                put("legacy", legacy.joinToString(separator = "\n"))
            }
        },
    ).also { failure ->
        cause?.let(failure::initCause)
    }

    private fun localWorkspaceId(workspaceRoot: Path): String {
        val registryPath = workspacesRoot().resolve("local-workspaces.json").toAbsolutePath().normalize()
        val workspaceKey = workspaceRoot.toString()
        return (readRegistry(registryPath)[workspaceKey] ?: workspaceHash(workspaceRoot))
            .sanitizedComponent()
    }

    private fun readRegistry(registryPath: Path): Map<String, String> {
        if (!Files.isRegularFile(registryPath)) {
            return emptyMap()
        }
        return runCatching {
            val json = Json.parseToJsonElement(Files.readString(registryPath)) as? JsonObject ?: return emptyMap()
            json.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { id -> key to id }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun sanitizedPath(workspaceRoot: Path): String = workspaceRoot
        .toString()
        .sanitizedSegment()
        .take(80)

    private fun workspaceSlug(workspaceRoot: Path): String = (workspaceRoot.fileName?.toString() ?: "workspace")
        .sanitizedSegment()
        .take(80)

    private fun String.sanitizedSegment(): String = replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-')
        .let { value -> value.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "workspace" }

    private fun String.sanitizedComponent(): String = sanitizedSegment().take(80)

    private companion object {
        const val migrationConflictCode = "WORKSPACE_STATE_MIGRATION_CONFLICT"
        const val migrationAmbiguousCode = "WORKSPACE_STATE_MIGRATION_AMBIGUOUS"
        const val migrationFailedCode = "WORKSPACE_STATE_MIGRATION_FAILED"
        const val migrationDepthExceededCode = "WORKSPACE_STATE_MIGRATION_DEPTH_EXCEEDED"
        const val maxLegacyRepositoryDepth = 32
    }
}

fun workspaceDataDirectory(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceDataDirectory(workspaceRoot)

fun workspaceCacheDirectory(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceCacheDirectory(workspaceRoot)

fun workspaceDatabasePath(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceDatabasePath(workspaceRoot)
