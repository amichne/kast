package io.github.amichne.kast.standalone.workspace

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.standalone.StandaloneSourceModuleSpec
import io.github.amichne.kast.standalone.StandaloneWorkspaceLayout
import io.github.amichne.kast.standalone.buildDependentModuleNamesBySourceModuleName
import io.github.amichne.kast.standalone.cache.WorkspaceDiscoveryCache
import io.github.amichne.kast.standalone.normalizeStandaloneModelPath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val maxIncludedProjectsForToolingApi = 200
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
        settingsSnapshot: GradleSettingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot),
        toolingApiLoader: (Path, Long) -> List<GradleModuleModel> = { root, timeoutMillis ->
            loadModulesWithToolingApi(root, timeoutMillis)
        },
        warningSink: (String) -> Unit = ::logWorkspaceDiscoveryWarning,
        config: KastConfig = KastConfig.load(workspaceRoot),
        cache: WorkspaceDiscoveryCache = WorkspaceDiscoveryCache(enabled = config.cache.enabled.value),
    ): StandaloneWorkspaceLayout {
        cachedWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            extraClasspathRoots = extraClasspathRoots,
            cache = cache,
        )?.let { cachedLayout ->
            return cachedLayout
        }

        val toolingApiTimeoutMillis = resolveToolingApiTimeoutMillis(settingsSnapshot.includedProjectPaths.size, config)
            .let { timeoutMillis ->
                if (settingsSnapshot.hasCompositeBuilds) {
                    timeoutMillis.coerceAtLeast(180_000L)
                } else {
                    timeoutMillis
                }
            }
        val discoveryResult = discoverGradleOwnedModules(
            workspaceRoot = workspaceRoot,
            timeoutMillis = toolingApiTimeoutMillis,
            toolingApiLoader = toolingApiLoader,
            warningSink = warningSink,
        )

        return buildStandaloneWorkspaceLayout(
            gradleModules = discoveryResult.modules,
            extraClasspathRoots = extraClasspathRoots,
            diagnostics = workspaceDiscoveryDiagnostics(
                modules = discoveryResult.modules,
                warnings = discoveryResult.diagnostics.warnings,
            ),
        ).also {
            if (discoveryResult.toolingApiSucceeded) {
                persistWorkspaceDiscoveryCache(
                    workspaceRoot = workspaceRoot,
                    result = discoveryResult,
                    cache = cache,
                )
            }
        }
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
                        val ideaModules = connection.model(IdeaProject::class.java)
                            .withCancellationToken(cancellationTokenSource.token())
                            .get()
                            .modules
                            .map { module -> toGradleModuleModel(module, pathNormalizer) }
                            .sortedBy(GradleModuleModel::gradlePath)
                        if (ideaModules.any(GradleModuleModel::hasSourceRoots)) {
                            return@use supplementConventionalTestFixtures(
                                workspaceRoot = workspaceRoot,
                                modules = ideaModules,
                            )
                        }

                        val sourceSetModules = runCatching {
                            loadModulesWithGradleSourceSetTask(
                                connection = connection,
                                pathNormalizer = pathNormalizer,
                                cancellationToken = cancellationTokenSource.token(),
                            )
                        }.onFailure { error ->
                            logWorkspaceDiscoveryWarning(
                                "Gradle source-set model extraction failed; using IDEA model discovery: " +
                                    (error.message ?: error::class.java.simpleName),
                            )
                        }.getOrDefault(emptyList())
                        if (sourceSetModules.isNotEmpty()) {
                            val sourceRootCount = sourceSetModules.sumOf { module ->
                                module.mainSourceRoots.size +
                                    module.testSourceRoots.size +
                                    module.testFixturesSourceRoots.size
                            }
                            logWorkspaceDiscoveryWarning(
                                "Gradle source-set model extraction returned ${sourceSetModules.size} modules and " +
                                    "$sourceRootCount source roots.",
                            )
                            return@use mergeGradleOwnedAndToolingModules(
                                gradleOwnedModules = sourceSetModules,
                                toolingModules = ideaModules,
                            )
                        }
                        ideaModules
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
        }.mergeDuplicateSourceModules()

        return StandaloneWorkspaceLayout(
            sourceModules = sourceModules,
            diagnostics = diagnostics,
            dependentModuleNamesBySourceModuleName = dependentModuleNamesBySourceModuleName
                ?: buildDependentModuleNamesBySourceModuleName(sourceModules),
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
                normalizedTestSourceRoots.filter { sourceRoot -> sourceRoot.matchesGradleSourceSet(GradleSourceSet.TEST_FIXTURES) }
            ).distinct().sorted()
        val dependencies = module.dependencies.mapNotNull(::toGradleDependency)
        val compilerOutput = module.compilerOutput
        val normalizedOutputDir = compilerOutput.outputDir?.toPath()?.let(::normalizeStandaloneModelPath)
        val normalizedTestOutputDir = compilerOutput.testOutputDir?.toPath()?.let(::normalizeStandaloneModelPath)
        val testFixturesOutputRoots = (
            listOfNotNull(normalizedOutputDir, normalizedTestOutputDir)
                .filter { outputRoot -> outputRoot.matchesGradleOutputRoot(GradleSourceSet.TEST_FIXTURES) }
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

    private fun loadModulesWithGradleSourceSetTask(
        connection: ProjectConnection,
        pathNormalizer: ToolingApiPathNormalizer,
        cancellationToken: CancellationToken,
    ): List<GradleModuleModel> {
        val tempDir = Files.createTempDirectory("kast-gradle-source-set-model")
        val initScript = tempDir.resolve("kast-source-set-model.gradle")
        val outputFile = tempDir.resolve("workspace-model.json")
        return try {
            Files.writeString(initScript, gradleSourceSetModelInitScript)
            connection.newBuild()
                .forTasks(":$gradleSourceSetModelTaskName")
                .withCancellationToken(cancellationToken)
                .withArguments(
                    "--init-script",
                    initScript.toString(),
                    "--no-configuration-cache",
                    "-PkastWorkspaceModelOutput=$outputFile",
                )
                .run()

            if (!outputFile.isRegularFile()) {
                return emptyList()
            }

            gradleSourceSetModelJson
                .decodeFromString<GradleSourceSetModelPayload>(Files.readString(outputFile))
                .modules
                .map { module -> module.toGradleModuleModel(pathNormalizer) }
                .sortedBy(GradleModuleModel::gradlePath)
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
    try {
        def srcDirs = value.srcDirs
        if (srcDirs != null && !srcDirs.is(value)) files.addAll(kastCollectFiles(srcDirs))
    } catch (Throwable ignored) {
    }
    try {
        def sourceDirectories = value.sourceDirectories
        if (sourceDirectories != null && !sourceDirectories.is(value)) files.addAll(kastCollectFiles(sourceDirectories))
    } catch (Throwable ignored) {
    }
    try {
        def filesProperty = value.files
        if (filesProperty != null && !filesProperty.is(value)) files.addAll(kastCollectFiles(filesProperty))
    } catch (Throwable ignored) {
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
                            dependencies.add([
                                type: "module",
                                targetIdeaModuleName: dependency.dependencyProject.path,
                                scope: scope,
                            ])
                        } else if (dependency instanceof FileCollectionDependency) {
                            kastNormalizeExistingClasspathRoots(dependency.files.files).each { file ->
                                dependencies.add([
                                    type: "library",
                                    binaryRoot: file,
                                    scope: scope,
                                ])
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
): StandaloneWorkspaceLayout? {
    val cachedDiscovery = runCatching {
        cache.read(workspaceRoot)
    }.getOrNull() ?: return null

    return GradleWorkspaceDiscovery.buildStandaloneWorkspaceLayout(
        gradleModules = cachedDiscovery.discoveryResult.modules,
        extraClasspathRoots = extraClasspathRoots,
        diagnostics = workspaceDiscoveryDiagnostics(
            modules = cachedDiscovery.discoveryResult.modules,
            warnings = cachedDiscovery.discoveryResult.diagnostics.warnings,
        ),
        dependentModuleNamesBySourceModuleName = cachedDiscovery.dependentModuleNamesBySourceModuleName,
    )
}

private fun persistWorkspaceDiscoveryCache(
    workspaceRoot: Path,
    result: GradleWorkspaceDiscoveryResult,
    cache: WorkspaceDiscoveryCache,
) {
    runCatching {
        cache.write(workspaceRoot, result)
    }
}

private fun discoverGradleOwnedModules(
    workspaceRoot: Path,
    timeoutMillis: Long,
    toolingApiLoader: (Path, Long) -> List<GradleModuleModel>,
    warningSink: (String) -> Unit,
): GradleWorkspaceDiscoveryResult {
    val warnings = mutableListOf<String>()
    val toolingModules = runCatching {
        toolingApiLoader(workspaceRoot, timeoutMillis)
    }.onFailure { error ->
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
        val warning = "Gradle-owned workspace discovery returned no modules for $workspaceRoot"
        warningSink(warning)
        throw IllegalStateException(warning)
    }

    return GradleWorkspaceDiscoveryResult(
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

private fun supplementConventionalTestFixtures(
    workspaceRoot: Path,
    modules: List<GradleModuleModel>,
): List<GradleModuleModel> = modules.map { module ->
    val projectDirectory = gradleProjectDirectory(workspaceRoot, module.gradlePath)
    val testFixturesSourceRoots = listOf(
        projectDirectory.resolve("src/testFixtures/kotlin"),
        projectDirectory.resolve("src/testFixtures/java"),
    )
        .filter(Files::isDirectory)
        .map(::normalizeStandaloneModelPath)
    val testFixturesOutputRoots = listOf(
        projectDirectory.resolve("build/classes/kotlin/testFixtures"),
        projectDirectory.resolve("build/classes/java/testFixtures"),
        projectDirectory.resolve("build/resources/testFixtures"),
    )
        .filter(Files::exists)
        .map(::normalizeStandaloneModelPath)

    if (testFixturesSourceRoots.isEmpty() && testFixturesOutputRoots.isEmpty()) {
        module
    } else {
        module.copy(
            testFixturesSourceRoots = (module.testFixturesSourceRoots + testFixturesSourceRoots).distinct().sorted(),
            testFixturesOutputRoots = (module.testFixturesOutputRoots + testFixturesOutputRoots).distinct().sorted(),
        )
    }
}

private fun gradleProjectDirectory(
    workspaceRoot: Path,
    gradlePath: String,
): Path {
    if (gradlePath == ":") {
        return workspaceRoot
    }
    return normalizeStandaloneModelPath(workspaceRoot.resolve(gradlePath.removePrefix(":").replace(':', '/')))
}

private fun mergeGradleOwnedAndToolingModules(
    gradleOwnedModules: List<GradleModuleModel>,
    toolingModules: List<GradleModuleModel>,
): List<GradleModuleModel> {
    val gradleOwnedModulesByPath = gradleOwnedModules.associateBy(GradleModuleModel::gradlePath)
    val toolingModulesByPath = toolingModules.associateBy(GradleModuleModel::gradlePath)
    return (gradleOwnedModulesByPath.keys + toolingModulesByPath.keys)
        .sorted()
        .map { gradlePath ->
            val gradleOwnedModule = gradleOwnedModulesByPath[gradlePath]
            val toolingModule = toolingModulesByPath[gradlePath]
            when {
                gradleOwnedModule != null && toolingModule != null -> gradleOwnedModule.mergeWithToolingModule(toolingModule)
                gradleOwnedModule != null -> gradleOwnedModule
                toolingModule != null -> toolingModule
                else -> error("No Gradle module model was available for $gradlePath")
            }
        }
}

private fun GradleModuleModel.mergeWithToolingModule(toolingModule: GradleModuleModel): GradleModuleModel = copy(
    mainSourceRoots = (mainSourceRoots + toolingModule.mainSourceRoots).distinct().sorted(),
    testSourceRoots = (testSourceRoots + toolingModule.testSourceRoots).distinct().sorted(),
    testFixturesSourceRoots = (testFixturesSourceRoots + toolingModule.testFixturesSourceRoots).distinct().sorted(),
    dependencies = (dependencies + toolingModule.dependencies).distinct(),
    mainOutputRoots = (mainOutputRoots + toolingModule.mainOutputRoots).distinct().sorted(),
    testOutputRoots = (testOutputRoots + toolingModule.testOutputRoots).distinct().sorted(),
    testFixturesOutputRoots = (testFixturesOutputRoots + toolingModule.testFixturesOutputRoots).distinct().sorted(),
)

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
