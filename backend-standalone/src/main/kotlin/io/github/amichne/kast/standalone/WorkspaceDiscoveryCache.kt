package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.ModuleName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.security.MessageDigest
import java.nio.file.attribute.BasicFileAttributes

private const val workspaceDiscoveryCacheSchemaVersion = 1

private val trackedGradleBuildFileNames = setOf(
    "settings.gradle",
    "settings.gradle.kts",
    "build.gradle",
    "build.gradle.kts",
)

private val trackedGradleBuildSkipDirs = setOf(
    ".git",
    ".gradle",
    ".kast",
    "build",
    "out",
    "node_modules",
    ".idea",
)

internal class WorkspaceDiscoveryCache(
    enabled: Boolean = !isCacheDisabled(),
    json: Json = defaultCacheJson,
) : VersionedFileCache<CachedWorkspaceDiscoveryPayload>(
    schemaVersion = workspaceDiscoveryCacheSchemaVersion,
    serializer = CachedWorkspaceDiscoveryPayload.serializer(),
    enabled = enabled,
    json = json,
) {
    override fun payloadSchemaVersion(payload: CachedWorkspaceDiscoveryPayload): Int = payload.schemaVersion

    fun read(workspaceRoot: Path): CachedWorkspaceDiscovery? {
        val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
        val payload = readPayload(workspaceDiscoveryCachePath(normalizedWorkspaceRoot)) ?: return null
        if (payload.cacheKey != computeWorkspaceDiscoveryCacheKey(normalizedWorkspaceRoot)) {
            return null
        }

        return CachedWorkspaceDiscovery(
            discoveryResult = payload.discoveryResult,
            dependentModuleNamesBySourceModuleName = payload.dependentModuleNamesBySourceModuleName
                .map { (key, moduleNames) -> ModuleName(key) to moduleNames.map(::ModuleName).toSet() }
                .toMap(),
        )
    }

    fun write(
        workspaceRoot: Path,
        result: GradleWorkspaceDiscoveryResult,
    ) {
        val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
        val dependentModuleNamesBySourceModuleName = GradleWorkspaceDiscovery
            .buildStandaloneWorkspaceLayout(
                gradleModules = result.modules,
                extraClasspathRoots = emptyList(),
            )
            .dependentModuleNamesBySourceModuleName
            .map { (key, moduleNames) -> key.value to moduleNames.map { it.value }.sorted() }
            .toMap()
        writePayload(
            workspaceDiscoveryCachePath(normalizedWorkspaceRoot),
            CachedWorkspaceDiscoveryPayload(
                cacheKey = computeWorkspaceDiscoveryCacheKey(normalizedWorkspaceRoot),
                discoveryResult = result,
                dependentModuleNamesBySourceModuleName = dependentModuleNamesBySourceModuleName,
            ),
        )
    }
}

internal data class CachedWorkspaceDiscovery(
    val discoveryResult: GradleWorkspaceDiscoveryResult,
    val dependentModuleNamesBySourceModuleName: Map<ModuleName, Set<ModuleName>>,
)

@Serializable
internal data class CachedWorkspaceDiscoveryPayload(
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

    val trackedFiles = mutableListOf<Path>()
    Files.walkFileTree(workspaceRoot, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult {
            if (dir != workspaceRoot && dir.fileName?.toString() in trackedGradleBuildSkipDirs) {
                return FileVisitResult.SKIP_SUBTREE
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(
            file: Path,
            attrs: BasicFileAttributes,
        ): FileVisitResult {
            if (attrs.isRegularFile && file.fileName?.toString() in trackedGradleBuildFileNames) {
                trackedFiles.add(file)
            }
            return FileVisitResult.CONTINUE
        }
    })
    return trackedFiles.sorted()
}
