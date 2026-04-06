package io.github.amichne.kast.standalone

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal const val maxIncludedProjectsForToolingApi = 200
internal const val defaultToolingApiTimeoutMillis = 30_000L
internal const val maxToolingApiTimeoutMillis = 300_000L
private const val toolingApiTimeoutMillisPerModule = 200L
private const val toolingApiTimeoutEnvVar = "KAST_GRADLE_TOOLING_TIMEOUT_MS"
private const val toolingApiCacheDisabledEnvVar = "KAST_GRADLE_CACHE_DISABLED"
private const val preferBuildOutputEnvVar = "KAST_PREFER_BUILD_OUTPUT"
private const val toolingApiResultCacheSchemaVersion = 1

internal fun resolveToolingApiTimeoutMillis(
    moduleCount: Int,
    envReader: (String) -> String? = System::getenv,
): Long = envReader(toolingApiTimeoutEnvVar)
    ?.toLongOrNull()
    ?: maxOf(defaultToolingApiTimeoutMillis, moduleCount.toLong() * toolingApiTimeoutMillisPerModule)
        .coerceAtMost(maxToolingApiTimeoutMillis)

internal data class WorkspaceDiscoveryDiagnostics(
    val warnings: List<String> = emptyList(),
)

internal data class GradleWorkspaceDiscoveryResult(
    val modules: List<GradleModuleModel>,
    val diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
)

internal object GradleWorkspaceDiscovery {
    fun discover(
        workspaceRoot: Path,
        extraClasspathRoots: List<Path>,
        toolingApiCache: ToolingApiResultCache = ToolingApiResultCache(),
        envReader: (String) -> String? = System::getenv,
        toolingApiLoader: (Path, Long) -> List<GradleModuleModel> = { root, timeoutMillis ->
            loadModulesWithToolingApi(root, timeoutMillis = timeoutMillis)
        },
    ): StandaloneWorkspaceLayout {
        val settingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot)
        val timeoutMillis = resolveToolingApiTimeoutMillis(
            moduleCount = settingsSnapshot.includedProjectPaths.size,
            envReader = envReader,
        )
        val cacheDisabled = envReader(toolingApiCacheDisabledEnvVar).isTruthy()
        val preferBuildOutput = envReader(preferBuildOutputEnvVar).isTruthy()
        logWorkspaceDiscoveryInfo(
            "resolved Gradle Tooling API timeout to ${timeoutMillis}ms for ${settingsSnapshot.includedProjectPaths.size} included projects",
        )
        val staticModules = {
            StaticGradleWorkspaceDiscovery.discoverModules(workspaceRoot, settingsSnapshot)
        }
        val cachedModules = readToolingApiModulesFromCache(
            workspaceRoot = workspaceRoot,
            toolingApiCache = toolingApiCache.takeUnless { cacheDisabled },
        )
        val discoveryResult = when {
            cachedModules != null -> GradleWorkspaceDiscoveryResult(modules = cachedModules)
            preferBuildOutput -> {
                logWorkspaceDiscoveryInfo("KAST_PREFER_BUILD_OUTPUT is set; using static discovery with build output classpath")
                GradleWorkspaceDiscoveryResult(modules = staticModules())
            }
            settingsSnapshot.shouldPreferStaticDiscovery() -> {
                val resolvedStaticModules = staticModules()
                enrichStaticModulesWithToolingApiLibraries(
                    workspaceRoot = workspaceRoot,
                    staticModules = resolvedStaticModules,
                    settingsSnapshot = settingsSnapshot,
                    toolingApiLoader = { root -> toolingApiLoader(root, timeoutMillis) },
                    compositeBuildLoader = { root, compositeBuilds ->
                        loadCompositeBuildsInParallel(
                            workspaceRoot = root,
                            compositeBuilds = compositeBuilds,
                            timeoutMillis = timeoutMillis,
                            toolingApiLoader = toolingApiLoader,
                        )
                    },
                    toolingApiCache = toolingApiCache.takeUnless { cacheDisabled },
                )
            }
            else -> {
                discoverToolingApiModules(
                    workspaceRoot = workspaceRoot,
                    settingsSnapshot = settingsSnapshot,
                    staticModules = staticModules,
                    timeoutMillis = timeoutMillis,
                    toolingApiLoader = toolingApiLoader,
                    toolingApiCache = toolingApiCache.takeUnless { cacheDisabled },
                )
            }
        }
        val diagnostics = WorkspaceDiscoveryDiagnostics(
            warnings = (discoveryResult.diagnostics.warnings + detectIncompleteClasspath(discoveryResult.modules))
                .distinct(),
        )

        return buildStandaloneWorkspaceLayout(
            gradleModules = discoveryResult.modules,
            extraClasspathRoots = extraClasspathRoots,
            diagnostics = diagnostics,
        )
    }

    internal fun loadModulesWithToolingApi(
        workspaceRoot: Path,
        timeoutMillis: Long = defaultToolingApiTimeoutMillis,
    ): List<GradleModuleModel> {
        val executor = Executors.newSingleThreadExecutor()
        val cancellationTokenSource = GradleConnector.newCancellationTokenSource()
        val future = executor.submit<List<GradleModuleModel>> {
            ToolingApiPathNormalizer().let { pathNormalizer ->
                GradleConnector.newConnector()
                    .forProjectDirectory(workspaceRoot.toFile())
                    .connect()
                    .use { connection ->
                        connection.model(IdeaProject::class.java)
                            .withCancellationToken(cancellationTokenSource.token())
                            .get()
                            .modules
                            .map { module -> toGradleModuleModel(module, pathNormalizer) }
                            .sortedBy(GradleModuleModel::gradlePath)
                    }
            }
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            cancellationTokenSource.cancel()
            throw TimeoutException(
                "Timed out after ${timeoutMillis}ms while loading the Gradle Tooling API model for $workspaceRoot",
            )
        } catch (error: InterruptedException) {
            future.cancel(true)
            cancellationTokenSource.cancel()
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Large workspaces prefer static discovery for module structure (source roots,
     * inter-project dependencies) because the Tooling API can be too slow. However,
     * static discovery cannot resolve external Maven/Gradle repository dependencies —
     * only `project(...)` and `files(...)` references are parsed from build scripts.
     * It also misses dependencies declared via convention plugins, `allprojects`, or
     * `subprojects` blocks in parent build scripts.
     *
     * This function bridges that gap: it still uses the Tooling API in best-effort mode
     * to extract resolved dependencies and merges them onto the statically discovered
     * modules. If the Tooling API fails entirely, the static modules are returned as-is.
     */
    internal fun enrichStaticModulesWithToolingApiLibraries(
        workspaceRoot: Path,
        staticModules: List<GradleModuleModel>,
        settingsSnapshot: GradleSettingsSnapshot? = null,
        toolingApiLoader: (Path) -> List<GradleModuleModel> = { root -> loadModulesWithToolingApi(root) },
        compositeBuildLoader: (Path, List<String>) -> List<GradleModuleModel> = { root, compositeBuilds ->
            loadCompositeBuildsInParallel(
                workspaceRoot = root,
                compositeBuilds = compositeBuilds,
                timeoutMillis = defaultToolingApiTimeoutMillis,
            )
        },
        toolingApiCache: ToolingApiResultCache? = null,
        warningSink: (String) -> Unit = ::logWorkspaceDiscoveryWarning,
    ): GradleWorkspaceDiscoveryResult {
        val warnings = mutableListOf<String>()
        val toolingModules = runCatching {
            val rootToolingModules = toolingApiLoader(workspaceRoot)
            val compositeBuildModules = if (settingsSnapshot?.hasCompositeBuilds == true &&
                settingsSnapshot.compositeBuilds.isNotEmpty()
            ) {
                compositeBuildLoader(workspaceRoot, settingsSnapshot.compositeBuilds)
            } else {
                emptyList()
            }
            rootToolingModules + compositeBuildModules
        }.onFailure { error ->
            val warning = toolingApiFailureWarning(
                prefix = "Gradle Tooling API library enrichment failed; using static workspace discovery results",
                error = error,
            )
            warnings += warning
            warningSink(warning)
        }.getOrNull()

        if (toolingModules.isNullOrEmpty()) {
            return GradleWorkspaceDiscoveryResult(
                modules = staticModules,
                diagnostics = WorkspaceDiscoveryDiagnostics(warnings = warnings),
            )
        }

        persistToolingApiModulesToCache(
            workspaceRoot = workspaceRoot,
            toolingApiCache = toolingApiCache,
            modules = toolingModules,
        )

        return GradleWorkspaceDiscoveryResult(
            modules = mergeToolingAndStaticModules(
                toolingModules = toolingModules,
                staticModules = staticModules,
            ),
            diagnostics = WorkspaceDiscoveryDiagnostics(warnings = warnings),
        )
    }

    internal fun buildStandaloneWorkspaceLayout(
        gradleModules: List<GradleModuleModel>,
        extraClasspathRoots: List<Path>,
        diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
    ): StandaloneWorkspaceLayout {
        val moduleModelsByIdeaName = buildMap {
            gradleModules.forEach { module ->
                putIfAbsent(module.ideaModuleName, module)
                putIfAbsent(module.gradlePath, module)
            }
        }
        val availableMainSourceModuleNames = gradleModules
            .mapNotNull(GradleModuleModel::mainDependencyModuleName)
            .toSet()
        val normalizedExtraClasspathRoots = extraClasspathRoots.distinct().sorted()
        val sourceModules = gradleModules.flatMap { module ->
            module.toStandaloneSourceModuleSpecs(
                moduleModelsByIdeaName = moduleModelsByIdeaName,
                availableMainSourceModuleNames = availableMainSourceModuleNames,
                extraClasspathRoots = normalizedExtraClasspathRoots,
            )
        }.mergeDuplicateSourceModules()

        return StandaloneWorkspaceLayout(
            sourceModules = sourceModules,
            diagnostics = diagnostics,
        )
    }

    private fun toGradleModuleModel(
        module: IdeaModule,
        pathNormalizer: ToolingApiPathNormalizer,
    ): GradleModuleModel {
        val projectDirectory = normalizeStandaloneModelPath(module.gradleProject.projectDirectory.toPath())
        val contentRoots = module.contentRoots
        val normalizedMainSourceRoots = pathNormalizer.normalizeExistingSourceRoots(
            contentRoots
                .asSequence()
                .flatMap { contentRoot -> contentRoot.sourceDirectories.asSequence().map { directory -> directory.directory.toPath() } },
        )
        val normalizedTestSourceRoots = pathNormalizer.normalizeExistingSourceRoots(
            contentRoots
                .asSequence()
                .flatMap { contentRoot -> contentRoot.testDirectories.asSequence().map { directory -> directory.directory.toPath() } },
        )
        val testFixturesSourceRoots = (
            normalizedMainSourceRoots.filter { sourceRoot -> sourceRoot.matchesGradleSourceSet(GradleSourceSet.TEST_FIXTURES) } +
                normalizedTestSourceRoots.filter { sourceRoot -> sourceRoot.matchesGradleSourceSet(GradleSourceSet.TEST_FIXTURES) } +
                pathNormalizer.normalizeExistingSourceRoots(
                    conventionalGradleSourceRootCandidates(
                        projectDirectory = projectDirectory,
                        sourceSet = GradleSourceSet.TEST_FIXTURES,
                    ).asSequence().filter(Files::isDirectory),
                )
            ).distinct().sorted()
        val dependencies = module.dependencies.mapNotNull(::toGradleDependency)
        val compilerOutput = module.compilerOutput
        val normalizedOutputDir = compilerOutput.outputDir?.toPath()?.let(::normalizeStandaloneModelPath)
        val normalizedTestOutputDir = compilerOutput.testOutputDir?.toPath()?.let(::normalizeStandaloneModelPath)
        val testFixturesOutputRoots = (
            listOfNotNull(normalizedOutputDir, normalizedTestOutputDir)
                .filter { outputRoot -> outputRoot.matchesGradleOutputRoot(GradleSourceSet.TEST_FIXTURES) } +
                conventionalGradleOutputRootCandidates(
                    projectDirectory = projectDirectory,
                    sourceSet = GradleSourceSet.TEST_FIXTURES,
                ).filter(Files::isDirectory).map(::normalizeStandaloneModelPath)
            ).distinct().sorted()
        return GradleModuleModel(
            gradlePath = module.gradleProject.path,
            ideaModuleName = module.name,
            mainSourceRoots = normalizedMainSourceRoots
                .filterNot { sourceRoot -> sourceRoot.matchesGradleSourceSet(GradleSourceSet.TEST_FIXTURES) },
            testSourceRoots = normalizedTestSourceRoots
                .filterNot { sourceRoot -> sourceRoot.matchesGradleSourceSet(GradleSourceSet.TEST_FIXTURES) },
            testFixturesSourceRoots = testFixturesSourceRoots,
            mainOutputRoots = listOfNotNull(normalizedOutputDir)
                .filterNot { outputRoot -> outputRoot.matchesGradleOutputRoot(GradleSourceSet.TEST_FIXTURES) },
            testOutputRoots = listOfNotNull(normalizedTestOutputDir)
                .filterNot { outputRoot -> outputRoot.matchesGradleOutputRoot(GradleSourceSet.TEST_FIXTURES) },
            testFixturesOutputRoots = testFixturesOutputRoots,
            dependencies = dependencies,
        )
    }

    private fun toGradleDependency(dependency: IdeaDependency): GradleDependency? {
        val scope = GradleDependencyScope.from(dependency)
        return when (dependency) {
            is IdeaModuleDependency -> GradleDependency.ModuleDependency(
                targetIdeaModuleName = dependency.targetModuleName,
                scope = scope,
            )
            is IdeaSingleEntryLibraryDependency -> dependency.file
                ?.toPath()
                ?.let(::normalizeStandaloneModelPath)
                ?.let { file -> GradleDependency.LibraryDependency(binaryRoot = file, scope = scope) }
            else -> null
        }
    }
}

private fun discoverToolingApiModules(
    workspaceRoot: Path,
    settingsSnapshot: GradleSettingsSnapshot,
    staticModules: () -> List<GradleModuleModel>,
    timeoutMillis: Long,
    toolingApiLoader: (Path, Long) -> List<GradleModuleModel>,
    toolingApiCache: ToolingApiResultCache? = null,
): GradleWorkspaceDiscoveryResult {
    val warnings = mutableListOf<String>()
    val toolingModules = runCatching {
        toolingApiLoader(workspaceRoot, timeoutMillis)
    }.onFailure { error ->
        val warning = toolingApiFailureWarning(
            prefix = "Gradle Tooling API workspace discovery failed; falling back to static workspace discovery",
            error = error,
        )
        warnings += warning
        logWorkspaceDiscoveryWarning(warning)
    }.getOrNull()
        ?: return GradleWorkspaceDiscoveryResult(
            modules = staticModules(),
            diagnostics = WorkspaceDiscoveryDiagnostics(warnings = warnings),
        )

    persistToolingApiModulesToCache(
        workspaceRoot = workspaceRoot,
        toolingApiCache = toolingApiCache,
        modules = toolingModules,
    )

    val modules = when {
        toolingModules.isEmpty() -> staticModules()
        toolingModules.shouldFallbackToStaticModules(settingsSnapshot) -> mergeToolingAndStaticModules(
            toolingModules = toolingModules,
            staticModules = staticModules(),
        )
        else -> toolingModules
    }

    return GradleWorkspaceDiscoveryResult(
        modules = modules,
        diagnostics = WorkspaceDiscoveryDiagnostics(warnings = warnings),
    )
}

internal fun detectIncompleteClasspath(modules: List<GradleModuleModel>): List<String> = modules
    .filter { module -> module.dependencies.isEmpty() && module.hasSourceRoots() }
    .map { module ->
        "Gradle workspace discovery did not resolve any dependencies for ${module.gradlePath}; standalone classpath may be incomplete."
    }

private fun GradleModuleModel.hasSourceRoots(): Boolean = mainSourceRoots.isNotEmpty() ||
    testSourceRoots.isNotEmpty() ||
    testFixturesSourceRoots.isNotEmpty()

private fun toolingApiFailureWarning(prefix: String, error: Throwable): String {
    val details = error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName
    return "$prefix: $details"
}

private fun logWorkspaceDiscoveryWarning(message: String) {
    System.err.println("kast gradle workspace discovery warning: $message")
}

private fun logWorkspaceDiscoveryInfo(message: String) {
    System.err.println("kast gradle workspace discovery: $message")
}

private fun readToolingApiModulesFromCache(
    workspaceRoot: Path,
    toolingApiCache: ToolingApiResultCache?,
): List<GradleModuleModel>? = toolingApiCache
    ?.let { cache -> runCatching { cache.read(workspaceRoot) }.getOrNull() }
    ?.takeIf(List<GradleModuleModel>::isNotEmpty)

private fun persistToolingApiModulesToCache(
    workspaceRoot: Path,
    toolingApiCache: ToolingApiResultCache?,
    modules: List<GradleModuleModel>,
) {
    if (toolingApiCache == null || modules.isEmpty()) {
        return
    }
    runCatching {
        toolingApiCache.write(workspaceRoot, modules)
    }
}

internal fun loadCompositeBuildsInParallel(
    workspaceRoot: Path,
    compositeBuilds: List<String>,
    timeoutMillis: Long,
    toolingApiLoader: (Path, Long) -> List<GradleModuleModel> = { root, timeout ->
        GradleWorkspaceDiscovery.loadModulesWithToolingApi(root, timeoutMillis = timeout)
    },
    warningSink: (String) -> Unit = ::logWorkspaceDiscoveryWarning,
): List<GradleModuleModel> {
    if (compositeBuilds.isEmpty()) {
        return emptyList()
    }

    val executor = Executors.newFixedThreadPool(minOf(compositeBuilds.size, 4))
    return try {
        compositeBuilds.map { compositeBuild ->
            executor.submit<CompositeBuildLoadResult> {
                val compositeBuildRoot = normalizeStandalonePath(workspaceRoot.resolve(compositeBuild))
                runCatching {
                    toolingApiLoader(compositeBuildRoot, timeoutMillis)
                }.fold(
                    onSuccess = { modules ->
                        CompositeBuildLoadResult(
                            compositeBuild = compositeBuild,
                            modules = modules,
                        )
                    },
                    onFailure = { error ->
                        CompositeBuildLoadResult(
                            compositeBuild = compositeBuild,
                            failure = error,
                        )
                    },
                )
            }
        }.flatMap { future ->
            val result = future.get()
            result.failure?.let { error ->
                warningSink(
                    toolingApiFailureWarning(
                        prefix = "Gradle Tooling API loading failed for composite build ${result.compositeBuild}; continuing with remaining builds",
                        error = error,
                    ),
                )
                emptyList()
            } ?: result.modules
        }
    } finally {
        executor.shutdownNow()
    }
}

internal class ToolingApiPathNormalizer(
    private val pathExists: (Path) -> Boolean = Files::exists,
) {
    private val pathExistsCache = linkedMapOf<Path, Boolean>()

    fun normalizeExistingSourceRoots(paths: Sequence<Path>): List<Path> = paths
        .map(::normalizeStandaloneModelPath)
        .distinct()
        .filter(::exists)
        .toList()
        .sorted()

    private fun exists(path: Path): Boolean = pathExistsCache.getOrPut(path) {
        pathExists(path)
    }
}

private fun mergeToolingAndStaticModules(
    toolingModules: List<GradleModuleModel>,
    staticModules: List<GradleModuleModel>,
): List<GradleModuleModel> {
    val toolingModulesByPath = toolingModules.associateBy(GradleModuleModel::gradlePath)
    val staticModulesByPath = staticModules.associateBy(GradleModuleModel::gradlePath)
    return (toolingModulesByPath.keys + staticModulesByPath.keys)
        .sorted()
        .map { gradlePath ->
            val toolingModule = toolingModulesByPath[gradlePath]
            val staticModule = staticModulesByPath[gradlePath]
            when {
                toolingModule != null && staticModule != null -> toolingModule.mergeWithStaticModule(staticModule)
                toolingModule != null -> toolingModule
                staticModule != null -> staticModule
                else -> error("No Gradle module model was available for $gradlePath")
            }
        }
}

private fun GradleModuleModel.mergeWithStaticModule(staticModule: GradleModuleModel): GradleModuleModel = copy(
    mainSourceRoots = (mainSourceRoots + staticModule.mainSourceRoots).distinct().sorted(),
    testSourceRoots = (testSourceRoots + staticModule.testSourceRoots).distinct().sorted(),
    testFixturesSourceRoots = (testFixturesSourceRoots + staticModule.testFixturesSourceRoots).distinct().sorted(),
    dependencies = (dependencies + staticModule.dependencies).distinct(),
    mainOutputRoots = (mainOutputRoots + staticModule.mainOutputRoots).distinct().sorted(),
    testOutputRoots = (testOutputRoots + staticModule.testOutputRoots).distinct().sorted(),
    testFixturesOutputRoots = (testFixturesOutputRoots + staticModule.testFixturesOutputRoots).distinct().sorted(),
)

private fun List<StandaloneSourceModuleSpec>.mergeDuplicateSourceModules(): List<StandaloneSourceModuleSpec> {
    val mergedModules = linkedMapOf<String, StandaloneSourceModuleSpec>()
    forEach { module ->
        val existing = mergedModules[module.name]
        mergedModules[module.name] = if (existing == null) {
            module
        } else {
            StandaloneSourceModuleSpec(
                name = module.name,
                sourceRoots = (existing.sourceRoots + module.sourceRoots).distinct().sorted(),
                binaryRoots = (existing.binaryRoots + module.binaryRoots).distinct().sorted(),
                dependencyModuleNames = (existing.dependencyModuleNames + module.dependencyModuleNames).distinct(),
            )
        }
    }
    return mergedModules.values.toList()
}

private fun Path.matchesGradleSourceSet(sourceSet: GradleSourceSet): Boolean {
    val normalizedPath = normalizeStandaloneModelPath(this).toString().replace('\\', '/')
    return normalizedPath.contains("/src/${sourceSet.id}/")
}

private fun Path.matchesGradleOutputRoot(sourceSet: GradleSourceSet): Boolean {
    val normalizedPath = normalizeStandaloneModelPath(this).toString().replace('\\', '/')
    return listOf(
        "/build/classes/${sourceSet.id}",
        "/build/classes/java/${sourceSet.id}",
        "/build/classes/kotlin/${sourceSet.id}",
        "/build/resources/${sourceSet.id}",
    ).any(normalizedPath::contains)
}


private fun List<GradleModuleModel>.shouldFallbackToStaticModules(
    settingsSnapshot: GradleSettingsSnapshot,
): Boolean {
    if (settingsSnapshot.includedProjectPaths.isEmpty()) {
        return false
    }

    val hasModuleDependencies = any { module ->
        module.dependencies.any(GradleDependency::isModuleDependency)
    }
    return !hasModuleDependencies
}

private fun GradleDependency.isModuleDependency(): Boolean = this is GradleDependency.ModuleDependency

internal class ToolingApiResultCache(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
    private val pathExists: (Path) -> Boolean = Path::exists,
    private val fileReader: (Path) -> String = Path::readText,
    private val fileWriter: (Path, String) -> Unit = { path, content ->
        path.parent?.createDirectories()
        path.writeText(content)
    },
    private val fileWalker: (Path) -> List<Path> = ::defaultGradleWorkspaceDiscoveryCacheKeyFiles,
) {
    fun cacheKey(workspaceRoot: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fileWalker(workspaceRoot)
            .sortedBy { path -> workspaceRoot.relativize(path).toString().replace('\\', '/') }
            .forEach { path ->
                digest.update(workspaceRoot.relativize(path).toString().replace('\\', '/').toByteArray())
                digest.update(0)
                digest.update(fileReader(path).toByteArray())
                digest.update(0)
            }
        return digest.digest().toHexString()
    }

    fun read(workspaceRoot: Path): List<GradleModuleModel>? {
        val cacheFilePath = cacheFilePath(workspaceRoot)
        if (!pathExists(cacheFilePath)) {
            return null
        }

        val payload = json.decodeFromString<ToolingApiResultCachePayload>(fileReader(cacheFilePath))
        if (payload.schemaVersion != toolingApiResultCacheSchemaVersion) {
            return null
        }

        return payload.modules.takeIf { payload.cacheKey == cacheKey(workspaceRoot) }
    }

    fun write(
        workspaceRoot: Path,
        modules: List<GradleModuleModel>,
    ) {
        val payload = ToolingApiResultCachePayload(
            schemaVersion = toolingApiResultCacheSchemaVersion,
            cacheKey = cacheKey(workspaceRoot),
            modules = modules,
        )
        fileWriter(cacheFilePath(workspaceRoot), json.encodeToString(payload))
    }

    fun cacheFilePath(workspaceRoot: Path): Path = workspaceRoot
        .resolve(".kast")
        .resolve("cache")
        .resolve("gradle-workspace-discovery.json")
}

private fun defaultGradleWorkspaceDiscoveryCacheKeyFiles(workspaceRoot: Path): List<Path> {
    val settingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot)
    return buildList {
        settingsFileCandidates(workspaceRoot).filter { path -> Files.isRegularFile(path) }.forEach(::add)
        buildFileCandidates(workspaceRoot).filter { path -> Files.isRegularFile(path) }.forEach(::add)
        val versionCatalog = workspaceRoot.resolve("gradle/libs.versions.toml")
        if (Files.isRegularFile(versionCatalog)) {
            add(versionCatalog)
        }
        settingsSnapshot.includedProjectPaths.forEach { projectPath ->
            val projectDir = projectDirectoryFor(workspaceRoot, projectPath)
            buildFileCandidates(projectDir)
                .filter { path -> Files.isRegularFile(path) }
                .forEach(::add)
        }
        settingsSnapshot.compositeBuilds.forEach { compositeBuild ->
            val compositeBuildRoot = workspaceRoot.resolve(compositeBuild)
            settingsFileCandidates(compositeBuildRoot).filter { path -> Files.isRegularFile(path) }.forEach(::add)
            buildFileCandidates(compositeBuildRoot).filter { path -> Files.isRegularFile(path) }.forEach(::add)
        }
    }.distinct()
}

private fun settingsFileCandidates(workspaceRoot: Path): List<Path> = listOf(
    workspaceRoot.resolve("settings.gradle.kts"),
    workspaceRoot.resolve("settings.gradle"),
)

private fun buildFileCandidates(projectDirectory: Path): List<Path> = listOf(
    projectDirectory.resolve("build.gradle.kts"),
    projectDirectory.resolve("build.gradle"),
)

private fun projectDirectoryFor(workspaceRoot: Path, projectPath: String): Path {
    if (projectPath == ":") return workspaceRoot
    val relativePath = projectPath.removePrefix(":").replace(':', '/')
    return workspaceRoot.resolve(relativePath)
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun String?.isTruthy(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "true", "yes", "on" -> true
    else -> false
}

@Serializable
private data class ToolingApiResultCachePayload(
    val schemaVersion: Int,
    val cacheKey: String,
    val modules: List<GradleModuleModel>,
)

internal object PathAsStringSerializer : KSerializer<Path> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Path", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Path,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Path = Path.of(decoder.decodeString())
}

internal object PathListSerializer : KSerializer<List<Path>> {
    private val delegate = ListSerializer(PathAsStringSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<Path>,
    ) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<Path> = delegate.deserialize(decoder)
}

@Serializable
internal data class GradleModuleModel(
    val gradlePath: String,
    val ideaModuleName: String,
    @Serializable(with = PathListSerializer::class)
    val mainSourceRoots: List<Path>,
    @Serializable(with = PathListSerializer::class)
    val testSourceRoots: List<Path>,
    @Serializable(with = PathListSerializer::class)
    val testFixturesSourceRoots: List<Path> = emptyList(),
    @Serializable(with = PathListSerializer::class)
    val mainOutputRoots: List<Path>,
    @Serializable(with = PathListSerializer::class)
    val testOutputRoots: List<Path>,
    @Serializable(with = PathListSerializer::class)
    val testFixturesOutputRoots: List<Path> = emptyList(),
    val dependencies: List<GradleDependency>,
) {
    private fun analysisModuleName(sourceSet: GradleSourceSet): String = "$gradlePath[${sourceSet.id}]"

    fun toStandaloneSourceModuleSpecs(
        moduleModelsByIdeaName: Map<String, GradleModuleModel>,
        availableMainSourceModuleNames: Set<String>,
        extraClasspathRoots: List<Path>,
    ): List<StandaloneSourceModuleSpec> {
        val resolvedDependencies = resolveSourceSetDependencies(
            moduleModelsByIdeaName = moduleModelsByIdeaName,
            availableMainSourceModuleNames = availableMainSourceModuleNames,
        )
        return buildList {
            mainSourceRoots.takeIf(List<Path>::isNotEmpty)?.let { sourceRoots ->
                add(
                    StandaloneSourceModuleSpec(
                        name = analysisModuleName(GradleSourceSet.MAIN),
                        sourceRoots = sourceRoots,
                        binaryRoots = (resolvedDependencies.mainBinaryRoots + extraClasspathRoots).distinct().sorted(),
                        dependencyModuleNames = resolvedDependencies.mainDependencyNames,
                    ),
                )
            }
            testFixturesSourceRoots.takeIf(List<Path>::isNotEmpty)?.let { sourceRoots ->
                add(
                    StandaloneSourceModuleSpec(
                        name = analysisModuleName(GradleSourceSet.TEST_FIXTURES),
                        sourceRoots = sourceRoots,
                        binaryRoots = (resolvedDependencies.testFixturesBinaryRoots + extraClasspathRoots).distinct().sorted(),
                        dependencyModuleNames = resolvedDependencies.testFixturesDependencyNames,
                    ),
                )
            }
            testSourceRoots.takeIf(List<Path>::isNotEmpty)?.let { sourceRoots ->
                add(
                    StandaloneSourceModuleSpec(
                        name = analysisModuleName(GradleSourceSet.TEST),
                        sourceRoots = sourceRoots,
                        binaryRoots = (resolvedDependencies.testBinaryRoots + extraClasspathRoots).distinct().sorted(),
                        dependencyModuleNames = resolvedDependencies.testDependencyNames,
                    ),
                )
            }
        }
    }

    fun mainDependencyModuleName(): String? = mainSourceRoots
        .takeIf(List<Path>::isNotEmpty)
        ?.let { analysisModuleName(GradleSourceSet.MAIN) }

    private fun resolveSourceSetDependencies(
        moduleModelsByIdeaName: Map<String, GradleModuleModel>,
        availableMainSourceModuleNames: Set<String>,
    ): ResolvedSourceSetDependencies {
        val mainBinaryRoots = linkedSetOf<Path>()
        val testFixturesBinaryRoots = linkedSetOf<Path>()
        val testBinaryRoots = linkedSetOf<Path>()
        val mainDependencyNames = linkedSetOf<String>()
        val testFixturesDependencyNames = linkedSetOf<String>()
        val testDependencyNames = linkedSetOf<String>()

        if (mainSourceRoots.isEmpty()) {
            testFixturesBinaryRoots.addAll(mainOutputRoots)
            testBinaryRoots.addAll(mainOutputRoots)
        } else {
            if (testFixturesSourceRoots.isNotEmpty()) {
                testFixturesDependencyNames.add(analysisModuleName(GradleSourceSet.MAIN))
            }
            if (testSourceRoots.isNotEmpty()) {
                testDependencyNames.add(analysisModuleName(GradleSourceSet.MAIN))
            }
        }
        if (testFixturesSourceRoots.isEmpty()) {
            testBinaryRoots.addAll(testFixturesOutputRoots)
        } else if (testSourceRoots.isNotEmpty()) {
            testDependencyNames.add(analysisModuleName(GradleSourceSet.TEST_FIXTURES))
        }

        dependencies.forEach { dependency ->
            when (dependency) {
                is GradleDependency.LibraryDependency -> {
                    if (dependency.scope in GradleSourceSet.MAIN.supportedDependencyScopes) {
                        mainBinaryRoots.add(dependency.binaryRoot)
                    }
                    if (dependency.scope in GradleSourceSet.TEST_FIXTURES.supportedDependencyScopes) {
                        testFixturesBinaryRoots.add(dependency.binaryRoot)
                    }
                    if (dependency.scope in GradleSourceSet.TEST.supportedDependencyScopes) {
                        testBinaryRoots.add(dependency.binaryRoot)
                    }
                }
                is GradleDependency.ModuleDependency -> {
                    val targetModule = moduleModelsByIdeaName[dependency.targetIdeaModuleName] ?: return@forEach
                    if (dependency.scope in GradleSourceSet.MAIN.supportedDependencyScopes) {
                        mainBinaryRoots.addAll(
                            targetModule.addSourceSetDependency(
                                dependencyNames = mainDependencyNames,
                                availableMainSourceModuleNames = availableMainSourceModuleNames,
                            ),
                        )
                    }
                    if (dependency.scope in GradleSourceSet.TEST_FIXTURES.supportedDependencyScopes) {
                        testFixturesBinaryRoots.addAll(
                            targetModule.addSourceSetDependency(
                                dependencyNames = testFixturesDependencyNames,
                                availableMainSourceModuleNames = availableMainSourceModuleNames,
                            ),
                        )
                    }
                    if (dependency.scope in GradleSourceSet.TEST.supportedDependencyScopes) {
                        testBinaryRoots.addAll(
                            targetModule.addSourceSetDependency(
                                dependencyNames = testDependencyNames,
                                availableMainSourceModuleNames = availableMainSourceModuleNames,
                            ),
                        )
                    }
                }
            }
        }

        return ResolvedSourceSetDependencies(
            mainBinaryRoots = mainBinaryRoots.toList(),
            testFixturesBinaryRoots = testFixturesBinaryRoots.toList(),
            testBinaryRoots = testBinaryRoots.toList(),
            mainDependencyNames = mainDependencyNames.toList(),
            testFixturesDependencyNames = testFixturesDependencyNames.toList(),
            testDependencyNames = testDependencyNames.toList(),
        )
    }

    private fun addSourceSetDependency(
        dependencyNames: MutableSet<String>,
        availableMainSourceModuleNames: Set<String>,
    ): List<Path> {
        val dependencyName = mainDependencyModuleName()
        if (dependencyName != null && dependencyName in availableMainSourceModuleNames) {
            dependencyNames.add(dependencyName)
            return emptyList()
        }
        return mainOutputRoots
    }
}

private data class ResolvedSourceSetDependencies(
    val mainBinaryRoots: List<Path>,
    val testFixturesBinaryRoots: List<Path>,
    val testBinaryRoots: List<Path>,
    val mainDependencyNames: List<String>,
    val testFixturesDependencyNames: List<String>,
    val testDependencyNames: List<String>,
)

private data class CompositeBuildLoadResult(
    val compositeBuild: String,
    val modules: List<GradleModuleModel> = emptyList(),
    val failure: Throwable? = null,
)

@Serializable
internal sealed interface GradleDependency {
    val scope: GradleDependencyScope

    @Serializable
    data class ModuleDependency(
        val targetIdeaModuleName: String,
        override val scope: GradleDependencyScope,
    ) : GradleDependency

    @Serializable
    data class LibraryDependency(
        @Serializable(with = PathAsStringSerializer::class)
        val binaryRoot: Path,
        override val scope: GradleDependencyScope,
    ) : GradleDependency
}

internal enum class GradleSourceSet(
    val id: String,
    val supportedDependencyScopes: Set<GradleDependencyScope>,
) {
    MAIN(
        id = "main",
        supportedDependencyScopes = setOf(
            GradleDependencyScope.COMPILE,
            GradleDependencyScope.PROVIDED,
            GradleDependencyScope.RUNTIME,
            GradleDependencyScope.UNKNOWN,
        ),
    ),
    TEST_FIXTURES(
        id = "testFixtures",
        supportedDependencyScopes = setOf(
            GradleDependencyScope.COMPILE,
            GradleDependencyScope.PROVIDED,
            GradleDependencyScope.TEST_FIXTURES,
            GradleDependencyScope.RUNTIME,
            GradleDependencyScope.UNKNOWN,
        ),
    ),
    TEST(
        id = "test",
        supportedDependencyScopes = setOf(
            GradleDependencyScope.COMPILE,
            GradleDependencyScope.PROVIDED,
            GradleDependencyScope.TEST,
            GradleDependencyScope.TEST_FIXTURES,
            GradleDependencyScope.RUNTIME,
            GradleDependencyScope.UNKNOWN,
        ),
    ),
}

@Serializable
internal enum class GradleDependencyScope {
    COMPILE,
    PROVIDED,
    TEST,
    TEST_FIXTURES,
    RUNTIME,
    UNKNOWN,
    ;

    companion object {
        fun from(dependency: IdeaDependency): GradleDependencyScope = when (dependency.scope?.scope?.uppercase()) {
            "COMPILE" -> COMPILE
            "PROVIDED" -> PROVIDED
            "TEST" -> TEST
            "TEST_FIXTURES" -> TEST_FIXTURES
            "RUNTIME" -> RUNTIME
            else -> UNKNOWN
        }
    }
}

internal data class GradleSettingsSnapshot(
    val includedProjectPaths: List<String>,
    val hasCompositeBuilds: Boolean,
    val compositeBuilds: List<String> = emptyList(),
) {
    fun shouldPreferStaticDiscovery(): Boolean = includedProjectPaths.size > maxIncludedProjectsForToolingApi

    fun projectPathsForStaticDiscovery(): List<String> = buildList {
        add(":")
        addAll(includedProjectPaths)
    }.distinct()

    companion object {
        private val includeBlockPattern = Regex("""(?s)\binclude\s*\((.*?)\)""")
        private val stringLiteralPattern = Regex("""[\"']([^\"']+)[\"']""")
        private val compositeBuildPattern = Regex("""\bincludeBuild\s*\(\s*[\"']([^\"']+)[\"']\s*\)""")

        fun read(workspaceRoot: Path): GradleSettingsSnapshot {
            val settingsText = settingsFileCandidates(workspaceRoot)
                .firstOrNull(Path::isRegularFile)
                ?.readText()
                .orEmpty()

            val includedProjectPaths = includeBlockPattern.findAll(settingsText)
                .flatMap { match ->
                    stringLiteralPattern.findAll(match.groupValues[1]).map { literal ->
                        normalizeGradleProjectPath(literal.groupValues[1])
                    }
                }
                .distinct()
                .sorted()
                .toList()
            val compositeBuilds = compositeBuildPattern.findAll(settingsText)
                .map { match -> match.groupValues[1] }
                .distinct()
                .toList()

            return GradleSettingsSnapshot(
                includedProjectPaths = includedProjectPaths,
                hasCompositeBuilds = compositeBuilds.isNotEmpty(),
                compositeBuilds = compositeBuilds,
            )
        }

        private fun settingsFileCandidates(workspaceRoot: Path): List<Path> = listOf(
            workspaceRoot.resolve("settings.gradle.kts"),
            workspaceRoot.resolve("settings.gradle"),
        )
    }
}

internal fun normalizeGradleProjectPath(projectPath: String): String = when {
    projectPath == ":" -> ":"
    projectPath.startsWith(":") -> projectPath
    projectPath.isBlank() -> ":"
    else -> ":$projectPath"
}
