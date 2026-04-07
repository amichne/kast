package io.github.amichne.kast.standalone

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val workspaceDiscoveryCacheSchemaVersion = 1

private val workspaceDiscoveryCacheJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal class WorkspaceDiscoveryCache(
    private val enabled: Boolean = !isCacheDisabled(),
    private val json: Json = workspaceDiscoveryCacheJson,
) {
    fun read(workspaceRoot: Path): CachedWorkspaceDiscovery? {
        if (!enabled) {
            return null
        }
        val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
        val cacheFilePath = workspaceDiscoveryCachePath(normalizedWorkspaceRoot)
        if (!Files.isRegularFile(cacheFilePath)) {
            return null
        }

        val payload = json.decodeFromString<CachedWorkspaceDiscoveryPayload>(Files.readString(cacheFilePath))
        if (payload.schemaVersion != workspaceDiscoveryCacheSchemaVersion) {
            return null
        }
        if (payload.cacheKey != computeWorkspaceDiscoveryCacheKey(normalizedWorkspaceRoot)) {
            return null
        }

        return CachedWorkspaceDiscovery(
            discoveryResult = payload.discoveryResult,
            dependentModuleNamesBySourceModuleName = payload.dependentModuleNamesBySourceModuleName
                .mapValues { (_, moduleNames) -> moduleNames.toSet() },
        )
    }

    fun write(
        workspaceRoot: Path,
        result: GradleWorkspaceDiscoveryResult,
    ) {
        if (!enabled) {
            return
        }
        val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
        val dependentModuleNamesBySourceModuleName = GradleWorkspaceDiscovery
            .buildStandaloneWorkspaceLayout(
                gradleModules = result.modules,
                extraClasspathRoots = emptyList(),
            )
            .dependentModuleNamesBySourceModuleName
            .mapValues { (_, moduleNames) -> moduleNames.toList().sorted() }
        writeCacheFileAtomically(
            path = workspaceDiscoveryCachePath(normalizedWorkspaceRoot),
            payload = json.encodeToString(
                CachedWorkspaceDiscoveryPayload(
                    cacheKey = computeWorkspaceDiscoveryCacheKey(normalizedWorkspaceRoot),
                    discoveryResult = result,
                    dependentModuleNamesBySourceModuleName = dependentModuleNamesBySourceModuleName,
                ),
            ),
        )
    }
}

internal data class CachedWorkspaceDiscovery(
    val discoveryResult: GradleWorkspaceDiscoveryResult,
    val dependentModuleNamesBySourceModuleName: Map<String, Set<String>>,
)

@Serializable
private data class CachedWorkspaceDiscoveryPayload(
    val schemaVersion: Int = workspaceDiscoveryCacheSchemaVersion,
    val cacheKey: String,
    val discoveryResult: GradleWorkspaceDiscoveryResult,
    val dependentModuleNamesBySourceModuleName: Map<String, List<String>>,
)

private fun workspaceDiscoveryCachePath(workspaceRoot: Path): Path =
    kastCacheDirectory(workspaceRoot).resolve("gradle-workspace.json")

private fun computeWorkspaceDiscoveryCacheKey(workspaceRoot: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    trackedGradleBuildFiles(workspaceRoot).forEach { file ->
        digest.update(workspaceRoot.relativize(file).toString().replace('\\', '/').toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(Files.readAllBytes(file))
        digest.update(0.toByte())
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun trackedGradleBuildFiles(workspaceRoot: Path): List<Path> {
    if (!Files.isDirectory(workspaceRoot)) {
        return emptyList()
    }

    return Files.walk(workspaceRoot).use { paths ->
        paths
            .filter(Files::isRegularFile)
            .filter { path ->
                when (path.fileName.toString()) {
                    "settings.gradle",
                    "settings.gradle.kts",
                    "build.gradle",
                    "build.gradle.kts",
                    -> true
                    else -> false
                }
            }
            .toList()
            .sorted()
    }
}
