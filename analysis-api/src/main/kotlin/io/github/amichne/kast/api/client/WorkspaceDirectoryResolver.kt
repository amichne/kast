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
    configHome: () -> Path = { kastConfigHome() },
    private val installRoot: () -> Path = ::kastInstallRoot,
    private val gitRemoteResolver: (Path) -> GitRemote? = GitRemoteParser::origin,
    private val uuidGenerator: () -> UUID = UUID::randomUUID,
) {
    fun workspaceDataDirectory(workspaceRoot: Path): Path {
        val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
        val remote = gitRemoteResolver(normalizedRoot)
        return if (remote != null) {
            workspacesRoot()
                .resolve(remote.host)
                .resolve(remote.owner)
                .resolve(remote.repo)
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

    fun workspaceDatabasePath(workspaceRoot: Path): Path =
        workspaceCacheDirectory(workspaceRoot).resolve("source-index.db")

    fun workspaceHash(workspaceRoot: Path): String = FileHashing.sha256(
        workspaceRoot.toAbsolutePath().normalize().toString(),
    ).take(12)

    private fun isEphemeralLocalWorkspace(workspaceRoot: Path): Boolean {
        val tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        return workspaceRoot.startsWith(tempRoot)
    }

    private fun workspacesRoot(): Path = installRoot().resolve("workspaces").toAbsolutePath().normalize()

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

    private fun writeRegistry(
        registryPath: Path,
        registry: Map<String, String>,
    ) {
        registryPath.parent?.let(Files::createDirectories)
        val json = JsonObject(registry.toSortedMap().mapValues { (_, value) -> JsonPrimitive(value) })
        Files.writeString(registryPath, Json.encodeToString(JsonObject.serializer(), json))
    }

    private fun sanitizedPath(workspaceRoot: Path): String = workspaceRoot
        .toString()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { "workspace" }
        .take(80)
}

fun kastInstallRoot(): Path = Path.of(System.getProperty("user.home")).resolve(".kast").toAbsolutePath().normalize()

fun workspaceDataDirectory(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver(configHome = { kastConfigHome() }).workspaceDataDirectory(workspaceRoot)

fun workspaceDataDirectory(
    workspaceRoot: Path,
    envLookup: (String) -> String?,
): Path = WorkspaceDirectoryResolver(configHome = { kastConfigHome(envLookup) }).workspaceDataDirectory(workspaceRoot)

fun workspaceCacheDirectory(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver(configHome = { kastConfigHome() }).workspaceCacheDirectory(workspaceRoot)

fun workspaceCacheDirectory(
    workspaceRoot: Path,
    envLookup: (String) -> String?,
): Path = WorkspaceDirectoryResolver(configHome = { kastConfigHome(envLookup) }).workspaceCacheDirectory(workspaceRoot)

fun workspaceDatabasePath(workspaceRoot: Path): Path =
    WorkspaceDirectoryResolver(configHome = { kastConfigHome() }).workspaceDatabasePath(workspaceRoot)

fun workspaceDatabasePath(
    workspaceRoot: Path,
    envLookup: (String) -> String?,
): Path = WorkspaceDirectoryResolver(configHome = { kastConfigHome(envLookup) }).workspaceDatabasePath(workspaceRoot)
