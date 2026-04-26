package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.indexstore.SqliteSourceIndexStore
import io.github.amichne.kast.indexstore.defaultCacheJson
import io.github.amichne.kast.standalone.normalizeStandalonePath
import io.github.amichne.kast.standalone.workspace.GradleWorkspaceDiscovery
import io.github.amichne.kast.standalone.workspace.GradleWorkspaceDiscoveryResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

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
    private val enabled: Boolean = !isCacheDisabled(),
    private val json: Json = defaultCacheJson,
    private val store: SqliteSourceIndexStore? = null,
) {

    fun read(workspaceRoot: Path): CachedWorkspaceDiscovery? {
        if (!enabled) return null
        val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
        val cacheKey = computeWorkspaceDiscoveryCacheKey(normalizedWorkspaceRoot)
        return withStore(normalizedWorkspaceRoot) { s ->
            val payload = s.readWorkspaceDiscovery(cacheKey) ?: return@withStore null
            val cached = runCatching {
                json.decodeFromString(CachedWorkspaceDiscoveryPayload.serializer(), payload)
            }.getOrNull() ?: return@withStore null
            if (cached.schemaVersion != workspaceDiscoveryCacheSchemaVersion) return@withStore null
            CachedWorkspaceDiscovery(
                discoveryResult = cached.discoveryResult,
                dependentModuleNamesBySourceModuleName = cached.dependentModuleNamesBySourceModuleName
                    .map { (key, moduleNames) -> ModuleName(key) to moduleNames.map(::ModuleName).toSet() }
                    .toMap(),
            )
        }
    }

    fun write(
        workspaceRoot: Path,
        result: GradleWorkspaceDiscoveryResult,
    ) {
        if (!enabled) return
        val normalizedWorkspaceRoot = normalizeStandalonePath(workspaceRoot)
        val cacheKey = computeWorkspaceDiscoveryCacheKey(normalizedWorkspaceRoot)
        val dependentModuleNamesBySourceModuleName = GradleWorkspaceDiscovery
            .buildStandaloneWorkspaceLayout(
                gradleModules = result.modules,
                extraClasspathRoots = emptyList(),
            )
            .dependentModuleNamesBySourceModuleName
            .map { (key, moduleNames) -> key.value to moduleNames.map { it.value }.sorted() }
            .toMap()
        val payload = json.encodeToString(
            CachedWorkspaceDiscoveryPayload.serializer(),
            CachedWorkspaceDiscoveryPayload(
                cacheKey = cacheKey,
                discoveryResult = result,
                dependentModuleNamesBySourceModuleName = dependentModuleNamesBySourceModuleName,
            ),
        )
        withStore(normalizedWorkspaceRoot) { s ->
            s.writeWorkspaceDiscovery(cacheKey, workspaceDiscoveryCacheSchemaVersion, payload)
        }
    }

    private inline fun <T> withStore(workspaceRoot: Path, block: (SqliteSourceIndexStore) -> T): T {
        if (store != null) return block(store)
        return SqliteSourceIndexStore(workspaceRoot).use { s ->
            s.ensureSchema()
            block(s)
        }
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
