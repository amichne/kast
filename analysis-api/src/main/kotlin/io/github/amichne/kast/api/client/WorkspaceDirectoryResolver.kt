package io.github.amichne.kast.api.client

import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class WorkspaceDirectoryResolver(
    private val installRoot: () -> Path = ::kastInstallRoot,
    private val dataRoot: () -> Path = { kastDataRoot(System::getenv, installRoot()) },
    private val gitWorkspaceResolver: (Path) -> GitWorkspace? = GitWorkspaceResolver::discover,
    private val gitRemoteResolver: (Path) -> GitRemote? = GitRemoteParser::origin,
    private val uuidGenerator: () -> UUID = UUID::randomUUID,
) {
    fun workspaceDataDirectory(workspaceRoot: Path): Path {
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        val gitWorkspace = gitWorkspaceResolver(normalizedRoot)
        val remote = gitWorkspace?.remote ?: gitRemoteResolver(normalizedRoot)
        return if (gitWorkspace != null) {
            gitWorkspaceDataDirectory(gitWorkspace, remote)
        } else if (remote != null) {
            gitWorkspaceDataDirectory(
                GitWorkspace(
                    toplevel = normalizedRoot,
                    commonDir = normalizedRoot.resolve(".git"),
                    gitDir = normalizedRoot.resolve(".git"),
                    remote = remote,
                ),
                remote,
            )
        } else if (isEphemeralLocalWorkspace(normalizedRoot)) {
            normalizedRoot
                .resolve(".gradle")
                .resolve("kast")
        } else {
            workspacesRoot()
                .resolve("local")
                .resolve("${sanitizedPath(normalizedRoot)}--${localWorkspaceId(normalizedRoot)}")
        }.toAbsolutePath().normalize()
    }

    fun workspaceCacheDirectory(workspaceRoot: Path): Path = workspaceDataDirectory(workspaceRoot).resolve("cache")

    fun workspaceDatabasePath(workspaceRoot: Path): Path = workspaceCacheDirectory(workspaceRoot).resolve("source-index.db")

    fun workspaceIdentity(
        workspaceRoot: Path,
        descriptorDirectory: Path = defaultDescriptorDirectory(),
    ): WorkspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot, this, descriptorDirectory)

    fun workspaceHash(workspaceRoot: Path): String = FileHashing.sha256(
        workspaceRoot.toAbsolutePath().normalize().toString(),
    ).take(12)

    private fun isEphemeralLocalWorkspace(workspaceRoot: Path): Boolean {
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        return workspaceRoot.startsWith(tempRoot)
    }

    private fun workspacesRoot(): Path = dataRoot().resolve("workspaces").toAbsolutePath().normalize()

    private fun gitWorkspaceDataDirectory(workspace: GitWorkspace, remote: GitRemote?): Path {
        val repoRoot = if (remote != null) {
            workspacesRoot()
                .resolve("git")
                .resolve(remote.host)
                .resolve(remote.owner)
                .resolve(remote.repo)
        } else {
            workspacesRoot()
                .resolve("git")
                .resolve("local")
                .resolve(gitCommonDirHash(workspace.commonDir))
        }
        return repoRoot
            .resolve("worktrees")
            .resolve("${workspaceSlug(workspace.toplevel)}--${gitWorktreeHash(workspace.toplevel, workspace.gitDir)}")
            .toAbsolutePath()
            .normalize()
    }

    private fun localWorkspaceId(workspaceRoot: Path): String {
        val registryPath = workspacesRoot().resolve("local-workspaces.json").toAbsolutePath().normalize()
        val workspaceKey = workspaceRoot.toString()
        val lockPath = registryPath.resolveSibling("local-workspaces.json.lock")
        registryPath.parent?.let(Files::createDirectories)
        java.io.RandomAccessFile(lockPath.toFile(), "rw").use { raf ->
            raf.channel.lock().use {
                val registry = readRegistry(registryPath).toMutableMap()
                registry[workspaceKey]?.let { return it }
                val id = uuidGenerator().toString()
                registry[workspaceKey] = id
                writeRegistry(registryPath, registry)
                return id
            }
        }
    }

    private fun readRegistry(registryPath: Path): Map<String, String> {
        if (!Files.isRegularFile(registryPath)) {
            return emptyMap()
        }
        return runCatching {
            val json = Json.parseToJsonElement(Files.readString(registryPath)) as? JsonObject ?: return emptyMap()
            json.mapNotNull { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { id -> key to id }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeRegistry(registryPath: Path, registry: Map<String, String>) {
        registryPath.parent?.let(Files::createDirectories)
        val json = JsonObject(registry.toSortedMap().mapValues { (_, value) -> JsonPrimitive(value) })
        Files.writeString(registryPath, Json.encodeToString(JsonObject.serializer(), json))
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
        .ifBlank { "workspace" }
}

fun workspaceDataDirectory(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceDataDirectory(workspaceRoot)

fun workspaceCacheDirectory(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceCacheDirectory(workspaceRoot)

fun workspaceDatabasePath(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver().workspaceDatabasePath(workspaceRoot)
