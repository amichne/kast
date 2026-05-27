package io.github.amichne.kast.standalone.workspace

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.standalone.StandaloneSourceModuleSpec
import io.github.amichne.kast.standalone.StandaloneWorkspaceLayout
import io.github.amichne.kast.standalone.buildDependentModuleNamesBySourceModuleName
import io.github.amichne.kast.standalone.cache.WorkspaceDiscoveryCache
import io.github.amichne.kast.standalone.normalizeStandaloneModelPath
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val defaultToolingApiTimeoutMillis = 120_000L

internal fun resolveToolingApiTimeoutMillis(
    moduleCount: Int,
    config: KastConfig = KastConfig.defaults(),
): Long {
    if (config.gradle.toolingApiTimeoutMillis.value != defaultToolingApiTimeoutMillis) {
        return config.gradle.toolingApiTimeoutMillis.value
    }
    return (moduleCount * 200L).coerceIn(config.gradle.toolingApiTimeoutMillis.value, 300_000L)
}

internal object GradleWorkspaceDiscovery {
    fun discover(
        workspaceRoot: Path,
        extraClasspathRoots: List<Path>,
        telemetry: StandaloneTelemetry = StandaloneTelemetry.disabled(),
        settingsSnapshot: GradleSettingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot),
        toolingApiLoader: (Path, Long) -> List<GradleModuleModel> = { root, timeoutMillis ->
            loadModulesWithGradleOwnedModel(root, timeoutMillis, telemetry)
        },
        warningSink: (String) -> Unit = ::logWorkspaceDiscoveryWarning,
        config: KastConfig = KastConfig.load(workspaceRoot),
        cache: WorkspaceDiscoveryCache = WorkspaceDiscoveryCache(enabled = config.cache.enabled.value),
    ): StandaloneWorkspaceLayout = telemetry.inSpan(
        scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
        name = "kast.workspaceDiscovery.gradle",
        attributes = mapOf(
            "kast.workspaceDiscovery.workspaceRoot" to workspaceRoot.toString(),
            "kast.workspaceDiscovery.mode" to "GRADLE_OWNED",
            "kast.workspaceDiscovery.includedProjectCount" to settingsSnapshot.includedProjectPaths.size,
            "kast.workspaceDiscovery.hasCompositeBuilds" to settingsSnapshot.hasCompositeBuilds,
        ),
    ) { span ->
        cachedWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            extraClasspathRoots = extraClasspathRoots,
            cache = cache,
            telemetry = telemetry,
        )?.let { cachedLayout ->
            span.setAttribute("kast.workspaceDiscovery.cacheHit", true)
            span.setAttribute("kast.workspaceDiscovery.sourceModuleCount", cachedLayout.sourceModules.size)
            return@inSpan cachedLayout
        }
        span.setAttribute("kast.workspaceDiscovery.cacheHit", false)

        val toolingApiTimeoutMillis = resolveToolingApiTimeoutMillis(settingsSnapshot.includedProjectPaths.size, config)
            .let { timeoutMillis ->
                if (settingsSnapshot.hasCompositeBuilds) {
                    timeoutMillis.coerceAtLeast(180_000L)
                } else {
                    timeoutMillis
                }
            }
        span.setAttribute("kast.workspaceDiscovery.toolingApiTimeoutMillis", toolingApiTimeoutMillis)
        val discoveryResult = discoverGradleOwnedModules(
            workspaceRoot = workspaceRoot,
            timeoutMillis = toolingApiTimeoutMillis,
            loader = toolingApiLoader,
            warningSink = warningSink,
            telemetry = telemetry,
        )

        val workspaceLayout = telemetry.inSpan(
            scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
            name = "kast.workspaceDiscovery.buildLayout",
            attributes = mapOf(
                "kast.workspaceDiscovery.mode" to "GRADLE_OWNED",
                "kast.workspaceDiscovery.gradleModuleCount" to discoveryResult.modules.size,
            ),
        ) { layoutSpan ->
            buildStandaloneWorkspaceLayout(
                gradleModules = discoveryResult.modules,
                extraClasspathRoots = extraClasspathRoots,
                diagnostics = workspaceDiscoveryDiagnostics(
                    modules = discoveryResult.modules,
                    warnings = discoveryResult.diagnostics.warnings,
                ),
            ).also { layout ->
                layoutSpan.setAttribute("kast.workspaceDiscovery.sourceModuleCount", layout.sourceModules.size)
            }
        }.also {
            if (discoveryResult.toolingApiSucceeded) {
                persistWorkspaceDiscoveryCache(
                    workspaceRoot = workspaceRoot,
                    result = discoveryResult,
                    cache = cache,
                    telemetry = telemetry,
                )
            }
        }
        span.setAttribute("kast.workspaceDiscovery.gradleModuleCount", discoveryResult.modules.size)
        span.setAttribute("kast.workspaceDiscovery.sourceModuleCount", workspaceLayout.sourceModules.size)
        span.setAttribute("kast.workspaceDiscovery.toolingApiSucceeded", discoveryResult.toolingApiSucceeded)
        workspaceLayout
    }

    internal fun loadModulesWithGradleOwnedModel(
        workspaceRoot: Path,
        timeoutMillis: Long = defaultToolingApiTimeoutMillis,
        telemetry: StandaloneTelemetry = StandaloneTelemetry.disabled(),
    ): List<GradleModuleModel> = loadModulesWithGradleConnection(
        workspaceRoot = workspaceRoot,
        timeoutMillis = timeoutMillis,
        timeoutDescription = "Gradle-owned workspace model",
        telemetry = telemetry,
    ) { connection, pathNormalizer, cancellationToken ->
        loadModulesWithGradleSourceSetTask(
            connection = connection,
            pathNormalizer = pathNormalizer,
            cancellationToken = cancellationToken,
            telemetry = telemetry,
        )
    }

    private fun loadModulesWithGradleConnection(
        workspaceRoot: Path,
        timeoutMillis: Long,
        timeoutDescription: String,
        telemetry: StandaloneTelemetry,
        load: (ProjectConnection, ToolingApiPathNormalizer, CancellationToken) -> List<GradleModuleModel>,
    ): List<GradleModuleModel> {
        val executor = Executors.newSingleThreadExecutor()
        val cancellationTokenSource = GradleConnector.newCancellationTokenSource()
        val future = executor.submit<List<GradleModuleModel>> {
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
                name = "kast.workspaceDiscovery.toolingApiSession",
                attributes = mapOf(
                    "kast.workspaceDiscovery.workspaceRoot" to workspaceRoot.toString(),
                    "kast.workspaceDiscovery.toolingApiTimeoutMillis" to timeoutMillis,
                    "kast.workspaceDiscovery.toolingApiDescription" to timeoutDescription,
                ),
            ) { sessionSpan ->
                val pathNormalizer = ToolingApiPathNormalizer()
                val connection = telemetry.inSpan(
                    scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
                    name = "kast.workspaceDiscovery.toolingApiConnect",
                    attributes = mapOf("kast.workspaceDiscovery.workspaceRoot" to workspaceRoot.toString()),
                ) {
                    GradleConnector.newConnector()
                        .forProjectDirectory(workspaceRoot.toFile())
                        .connect()
                }
                connection.use { projectConnection ->
                    telemetry.inSpan(
                        scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
                        name = "kast.workspaceDiscovery.toolingApiModel",
                        attributes = mapOf("kast.workspaceDiscovery.toolingApiDescription" to timeoutDescription),
                    ) { modelSpan ->
                        load(projectConnection, pathNormalizer, cancellationTokenSource.token())
                            .also { modules ->
                                sessionSpan.setAttribute("kast.workspaceDiscovery.gradleModuleCount", modules.size)
                                modelSpan.setAttribute("kast.workspaceDiscovery.gradleModuleCount", modules.size)
                            }
                    }
                }
            }
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            cancellationTokenSource.cancel()
            throw TimeoutException(
                "Timed out after ${timeoutMillis}ms while loading the $timeoutDescription for $workspaceRoot",
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

    internal fun buildStandaloneWorkspaceLayout(
        gradleModules: List<GradleModuleModel>,
        extraClasspathRoots: List<Path>,
        diagnostics: WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(),
        dependentModuleNamesBySourceModuleName: Map<ModuleName, Set<ModuleName>>? = null,
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
        }.mergeDuplicateSourceModules().withoutCyclicDependencyEdges()

        return StandaloneWorkspaceLayout(
            sourceModules = sourceModules,
            diagnostics = diagnostics,
            dependentModuleNamesBySourceModuleName = dependentModuleNamesBySourceModuleName
                ?: buildDependentModuleNamesBySourceModuleName(sourceModules),
        )
    }

    private fun loadModulesWithGradleSourceSetTask(
        connection: ProjectConnection,
        pathNormalizer: ToolingApiPathNormalizer,
        cancellationToken: CancellationToken,
        telemetry: StandaloneTelemetry = StandaloneTelemetry.disabled(),
    ): List<GradleModuleModel> {
        val tempDir = Files.createTempDirectory("kast-gradle-source-set-model")
        val initScript = tempDir.resolve("kast-source-set-model.gradle")
        val outputFile = tempDir.resolve("workspace-model.json")
        return try {
            Files.writeString(initScript, gradleSourceSetModelInitScript)
            telemetry.inSpan(
                scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
                name = "kast.workspaceDiscovery.sourceSetTask",
                attributes = mapOf("kast.workspaceDiscovery.taskName" to gradleSourceSetModelTaskName),
            ) {
                connection.newBuild()
                    .forTasks(":$gradleSourceSetModelTaskName")
                    .withCancellationToken(cancellationToken)
                    .withArguments(
                        "--init-script",
                        initScript.toString(),
                        "--no-configuration-cache",
                        "--no-configure-on-demand",
                        "-PkastWorkspaceModelOutput=$outputFile",
                    )
                    .run()
            }

            if (!outputFile.isRegularFile()) {
                return emptyList()
            }

            telemetry.inSpan(
                scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
                name = "kast.workspaceDiscovery.sourceSetModelDecode",
            ) { decodeSpan ->
                gradleSourceSetModelJson
                    .decodeFromString<GradleSourceSetModelPayload>(Files.readString(outputFile))
                    .modules
                    .map { module -> module.toGradleModuleModel(pathNormalizer) }
                    .sortedBy(GradleModuleModel::gradlePath)
                    .also { modules ->
                        decodeSpan.setAttribute("kast.workspaceDiscovery.gradleModuleCount", modules.size)
                    }
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}

private const val gradleSourceSetModelTaskName = "kastWriteWorkspaceModel"

private val gradleSourceSetModelJson = Json {
    ignoreUnknownKeys = true
}

private val gradleSourceSetModelInitScript = """
import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.file.SourceDirectorySet

def kastDependencyScope = { String rawName ->
    def name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT)
    if (name.contains("testfixtures")) return "TEST_FIXTURES"
    if (name.contains("test")) return "TEST"
    if (name.contains("compileonly")) return "PROVIDED"
    if (name.contains("runtimeonly")) return "RUNTIME"
    if (name.contains("implementation") || name.contains("api") || name.contains("compile")) return "COMPILE"
    return "UNKNOWN"
}

def kastSourceSetBucket = { String rawName ->
    def name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT)
    if (name.contains("fixture")) return "testFixtures"
    if (name.contains("test")) return "test"
    return "main"
}

def kastCollectFiles
kastCollectFiles = { Object value ->
    def files = []
    if (value == null) return files
    if (value instanceof File) {
        files.add(value)
        return files
    }
    if (value instanceof SourceDirectorySet) {
        files.addAll(value.srcDirs.findAll { item -> item instanceof File })
        return files
    }
    try {
        def srcDirs = value.srcDirs
        if (srcDirs instanceof Iterable) {
            files.addAll(srcDirs.findAll { item -> item instanceof File })
            return files
        }
    } catch (Throwable ignored) {
    }
    if (value instanceof FileCollection) {
        files.addAll(value.files.findAll { item -> item instanceof File })
        return files
    }
    if (value instanceof Iterable) {
        value.each { item -> files.addAll(kastCollectFiles(item)) }
    }
    return files
}

def kastNormalizeExistingDirectories = { roots ->
    roots
        .findAll { file -> file instanceof File && file.exists() && file.isDirectory() }
        .collect { file -> file.toPath().toAbsolutePath().normalize().toString() }
        .unique()
        .sort()
}

def kastNormalizeExistingClasspathRoots = { roots ->
    roots
        .findAll { file -> file instanceof File && file.exists() && (file.isFile() || file.isDirectory()) }
        .collect { file -> file.toPath().toAbsolutePath().normalize().toString() }
        .unique()
        .sort()
}

gradle.rootProject { root ->
    root.tasks.register("kastWriteWorkspaceModel") {
        outputs.upToDateWhen { false }
        doLast {
            def outputPath = root.findProperty("kastWorkspaceModelOutput")
            if (outputPath == null) {
                throw new GradleException("Missing -PkastWorkspaceModelOutput for kast source-set model extraction")
            }

            def modules = root.allprojects.collect { project ->
                def mainSourceRoots = [] as LinkedHashSet
                def testSourceRoots = [] as LinkedHashSet
                def testFixturesSourceRoots = [] as LinkedHashSet
                def mainOutputRoots = [] as LinkedHashSet
                def testOutputRoots = [] as LinkedHashSet
                def testFixturesOutputRoots = [] as LinkedHashSet
                def dependencies = [] as LinkedHashSet

                def addRoots = { bucket, roots ->
                    if (bucket == "testFixtures") {
                        testFixturesSourceRoots.addAll(kastNormalizeExistingDirectories(roots))
                    } else if (bucket == "test") {
                        testSourceRoots.addAll(kastNormalizeExistingDirectories(roots))
                    } else {
                        mainSourceRoots.addAll(kastNormalizeExistingDirectories(roots))
                    }
                }
                def addOutputs = { bucket, roots ->
                    if (bucket == "testFixtures") {
                        testFixturesOutputRoots.addAll(kastNormalizeExistingDirectories(roots))
                    } else if (bucket == "test") {
                        testOutputRoots.addAll(kastNormalizeExistingDirectories(roots))
                    } else {
                        mainOutputRoots.addAll(kastNormalizeExistingDirectories(roots))
                    }
                }
                def addLibraryDependency = { scope, file ->
                    dependencies.add([
                        type: "library",
                        binaryRoot: file,
                        scope: scope,
                    ])
                }

                def sourceSets = project.extensions.findByName("sourceSets")
                if (sourceSets != null) {
                    sourceSets.each { sourceSet ->
                        def bucket = kastSourceSetBucket(sourceSet.name?.toString())
                        def roots = []
                        try { roots.addAll(kastCollectFiles(sourceSet.java)) } catch (Throwable ignored) {}
                        try { roots.addAll(kastCollectFiles(sourceSet.kotlin)) } catch (Throwable ignored) {}
                        if (roots.isEmpty()) {
                            try { roots.addAll(kastCollectFiles(sourceSet.allSource)) } catch (Throwable ignored) {}
                        }
                        addRoots(bucket, roots)

                        def outputs = []
                        try { outputs.addAll(kastCollectFiles(sourceSet.output.classesDirs)) } catch (Throwable ignored) {}
                        try {
                            if (sourceSet.output.resourcesDir != null) outputs.add(sourceSet.output.resourcesDir)
                        } catch (Throwable ignored) {}
                        addOutputs(bucket, outputs)
                    }
                }

                def kotlinExtension = project.extensions.findByName("kotlin")
                if (kotlinExtension != null) {
                    try {
                        kotlinExtension.sourceSets.each { sourceSet ->
                            def roots = []
                            try { roots.addAll(kastCollectFiles(sourceSet.kotlin)) } catch (Throwable ignored) {}
                            try { roots.addAll(kastCollectFiles(sourceSet)) } catch (Throwable ignored) {}
                            addRoots(kastSourceSetBucket(sourceSet.name?.toString()), roots)
                        }
                    } catch (Throwable ignored) {
                    }
                }

                def androidExtension = project.extensions.findByName("android")
                if (androidExtension != null) {
                    try {
                        androidExtension.sourceSets.each { sourceSet ->
                            def roots = []
                            try { roots.addAll(kastCollectFiles(sourceSet.java)) } catch (Throwable ignored) {}
                            try { roots.addAll(kastCollectFiles(sourceSet.kotlin)) } catch (Throwable ignored) {}
                            addRoots(kastSourceSetBucket(sourceSet.name?.toString()), roots)
                        }
                    } catch (Throwable ignored) {
                    }
                }

                project.configurations.each { configuration ->
                    def scope = kastDependencyScope(configuration.name)
                    configuration.dependencies.each { dependency ->
                        if (dependency instanceof ProjectDependency) {
                            def targetPath = null
                            try { targetPath = dependency.path } catch (Throwable ignored) {}
                            if (targetPath == null) {
                                try { targetPath = dependency.dependencyProject.path } catch (Throwable ignored) {}
                            }
                            if (targetPath != null) {
                                dependencies.add([
                                    type: "module",
                                    targetIdeaModuleName: targetPath,
                                    scope: scope,
                                ])
                            }
                        } else if (dependency instanceof FileCollectionDependency) {
                            try {
                                kastNormalizeExistingClasspathRoots(dependency.files.files).each { file ->
                                    dependencies.add([
                                        type: "library",
                                        binaryRoot: file,
                                        scope: scope,
                                    ])
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }

                return [
                    gradlePath: project.path,
                    ideaModuleName: project.name,
                    mainSourceRoots: mainSourceRoots.toList().sort(),
                    testSourceRoots: testSourceRoots.toList().sort(),
                    testFixturesSourceRoots: testFixturesSourceRoots.toList().sort(),
                    mainOutputRoots: mainOutputRoots.toList().sort(),
                    testOutputRoots: testOutputRoots.toList().sort(),
                    testFixturesOutputRoots: testFixturesOutputRoots.toList().sort(),
                    dependencies: dependencies.toList(),
                ]
            }

            def outputFile = new File(outputPath.toString())
            outputFile.parentFile.mkdirs()
            outputFile.text = JsonOutput.toJson([modules: modules])
        }
    }
}
""".trimIndent()

@Serializable
private data class GradleSourceSetModelPayload(
    val modules: List<GradleSourceSetModelModule> = emptyList(),
)

@Serializable
private data class GradleSourceSetModelModule(
    val gradlePath: String,
    val ideaModuleName: String,
    val mainSourceRoots: List<String> = emptyList(),
    val testSourceRoots: List<String> = emptyList(),
    val testFixturesSourceRoots: List<String> = emptyList(),
    val mainOutputRoots: List<String> = emptyList(),
    val testOutputRoots: List<String> = emptyList(),
    val testFixturesOutputRoots: List<String> = emptyList(),
    val dependencies: List<GradleSourceSetModelDependency> = emptyList(),
) {
    fun toGradleModuleModel(pathNormalizer: ToolingApiPathNormalizer): GradleModuleModel = GradleModuleModel(
        gradlePath = gradlePath,
        ideaModuleName = ideaModuleName,
        mainSourceRoots = pathNormalizer.normalizeExistingSourceRoots(mainSourceRoots.asPathSequence()),
        testSourceRoots = pathNormalizer.normalizeExistingSourceRoots(testSourceRoots.asPathSequence()),
        testFixturesSourceRoots = pathNormalizer.normalizeExistingSourceRoots(testFixturesSourceRoots.asPathSequence()),
        mainOutputRoots = mainOutputRoots.asExistingNormalizedPaths(),
        testOutputRoots = testOutputRoots.asExistingNormalizedPaths(),
        testFixturesOutputRoots = testFixturesOutputRoots.asExistingNormalizedPaths(),
        dependencies = dependencies.mapNotNull(GradleSourceSetModelDependency::toGradleDependency).distinct(),
    )
}

@Serializable
private data class GradleSourceSetModelDependency(
    val type: String,
    val targetIdeaModuleName: String? = null,
    val binaryRoot: String? = null,
    val scope: String = GradleDependencyScope.UNKNOWN.name,
) {
    fun toGradleDependency(): GradleDependency? {
        val dependencyScope = runCatching { GradleDependencyScope.valueOf(scope) }
            .getOrDefault(GradleDependencyScope.UNKNOWN)
        return when (type) {
            "module" -> targetIdeaModuleName?.let { target ->
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = target,
                    scope = dependencyScope,
                )
            }
            "library" -> binaryRoot
                ?.let(Path::of)
                ?.let(::normalizeStandaloneModelPath)
                ?.takeIf(Files::exists)
                ?.let { root ->
                    GradleDependency.LibraryDependency(
                        binaryRoot = root,
                        scope = dependencyScope,
                    )
                }
            else -> null
        }
    }
}

private fun List<String>.asPathSequence(): Sequence<Path> = asSequence().map(Path::of)

private fun List<String>.asExistingNormalizedPaths(): List<Path> = asSequence()
    .map(Path::of)
    .map(::normalizeStandaloneModelPath)
    .distinct()
    .filter(Files::exists)
    .toList()
    .sorted()

private fun cachedWorkspaceLayout(
    workspaceRoot: Path,
    extraClasspathRoots: List<Path>,
    cache: WorkspaceDiscoveryCache,
    telemetry: StandaloneTelemetry,
): StandaloneWorkspaceLayout? = telemetry.inSpan(
    scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
    name = "kast.workspaceDiscovery.cacheRead",
    attributes = mapOf("kast.workspaceDiscovery.mode" to "GRADLE_OWNED"),
) { span ->
    val cachedDiscovery = runCatching {
        cache.read(workspaceRoot)
    }.getOrNull()
    span.setAttribute("kast.workspaceDiscovery.cacheHit", cachedDiscovery != null)
    cachedDiscovery ?: return@inSpan null

    GradleWorkspaceDiscovery.buildStandaloneWorkspaceLayout(
        gradleModules = cachedDiscovery.discoveryResult.modules,
        extraClasspathRoots = extraClasspathRoots,
        diagnostics = workspaceDiscoveryDiagnostics(
            modules = cachedDiscovery.discoveryResult.modules,
            warnings = cachedDiscovery.discoveryResult.diagnostics.warnings,
        ),
        dependentModuleNamesBySourceModuleName = cachedDiscovery.dependentModuleNamesBySourceModuleName,
    ).also { layout ->
        span.setAttribute("kast.workspaceDiscovery.gradleModuleCount", cachedDiscovery.discoveryResult.modules.size)
        span.setAttribute("kast.workspaceDiscovery.sourceModuleCount", layout.sourceModules.size)
    }
}

private fun persistWorkspaceDiscoveryCache(
    workspaceRoot: Path,
    result: GradleWorkspaceDiscoveryResult,
    cache: WorkspaceDiscoveryCache,
    telemetry: StandaloneTelemetry,
) {
    telemetry.inSpan(
        scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
        name = "kast.workspaceDiscovery.cacheWrite",
        attributes = mapOf(
            "kast.workspaceDiscovery.mode" to "GRADLE_OWNED",
            "kast.workspaceDiscovery.gradleModuleCount" to result.modules.size,
        ),
    ) {
        runCatching {
            cache.write(workspaceRoot, result)
        }
    }
}

private fun discoverGradleOwnedModules(
    workspaceRoot: Path,
    timeoutMillis: Long,
    loader: (Path, Long) -> List<GradleModuleModel>,
    warningSink: (String) -> Unit,
    telemetry: StandaloneTelemetry,
): GradleWorkspaceDiscoveryResult = telemetry.inSpan(
    scope = StandaloneTelemetryScope.WORKSPACE_DISCOVERY,
    name = "kast.workspaceDiscovery.toolingApiLoad",
    attributes = mapOf(
        "kast.workspaceDiscovery.mode" to "GRADLE_OWNED",
        "kast.workspaceDiscovery.toolingApiTimeoutMillis" to timeoutMillis,
    ),
) { span ->
    val warnings = mutableListOf<String>()
    val toolingModules = runCatching {
        loader(workspaceRoot, timeoutMillis)
            .also { modules ->
                span.setAttribute("kast.workspaceDiscovery.gradleModuleCount", modules.size)
            }
    }.onFailure { error ->
        span.setAttribute("kast.workspaceDiscovery.toolingApiSucceeded", false)
        val warning = toolingApiFailureWarning(
            prefix = "Gradle-owned workspace discovery failed",
            error = error,
        )
        warnings += warning
        warningSink(warning)
    }.getOrElse { error ->
        throw IllegalStateException(
            "Gradle-owned workspace discovery failed for $workspaceRoot",
            error,
        )
    }

    if (toolingModules.isEmpty()) {
        span.setAttribute("kast.workspaceDiscovery.toolingApiSucceeded", false)
        val warning = "Gradle-owned workspace discovery returned no modules for $workspaceRoot"
        warningSink(warning)
        throw IllegalStateException(warning)
    }
    span.setAttribute("kast.workspaceDiscovery.toolingApiSucceeded", true)

    GradleWorkspaceDiscoveryResult(
        modules = toolingModules,
        diagnostics = WorkspaceDiscoveryDiagnostics(warnings = warnings),
    )
}

private fun workspaceDiscoveryDiagnostics(
    modules: List<GradleModuleModel>,
    warnings: List<String> = emptyList(),
): WorkspaceDiscoveryDiagnostics = WorkspaceDiscoveryDiagnostics(
    warnings = (warnings + detectIncompleteClasspath(modules)).distinct(),
)

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


private fun List<StandaloneSourceModuleSpec>.mergeDuplicateSourceModules(): List<StandaloneSourceModuleSpec> {
    val mergedModules = linkedMapOf<ModuleName, StandaloneSourceModuleSpec>()
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

private fun List<StandaloneSourceModuleSpec>.withoutCyclicDependencyEdges(): List<StandaloneSourceModuleSpec> {
    val knownModuleNames = map(StandaloneSourceModuleSpec::name).toSet()
    val acceptedDependencies = linkedMapOf<ModuleName, MutableList<ModuleName>>()
    return map { module ->
        val dependencies = mutableListOf<ModuleName>()
        acceptedDependencies[module.name] = dependencies
        module.dependencyModuleNames.forEach { dependencyName ->
            if (dependencyName !in knownModuleNames) return@forEach
            if (dependencyName == module.name) return@forEach
            if (acceptedDependencies.reaches(dependencyName, module.name)) return@forEach
            dependencies.add(dependencyName)
        }
        module.copy(dependencyModuleNames = dependencies)
    }
}

private fun Map<ModuleName, List<ModuleName>>.reaches(
    start: ModuleName,
    target: ModuleName,
    seen: MutableSet<ModuleName> = linkedSetOf(),
): Boolean {
    if (!seen.add(start)) return false
    return get(start).orEmpty().any { dependencyName ->
        dependencyName == target || reaches(dependencyName, target, seen)
    }
}
