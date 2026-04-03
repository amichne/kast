package io.github.amichne.kast.standalone

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

internal const val maxIncludedProjectsForToolingApi = 200

internal object GradleWorkspaceDiscovery {
    fun discover(
        workspaceRoot: Path,
        extraClasspathRoots: List<Path>,
    ): StandaloneWorkspaceLayout {
        val settingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot)
        val staticModules = {
            StaticGradleWorkspaceDiscovery.discoverModules(workspaceRoot, settingsSnapshot)
        }
        val gradleModules = if (settingsSnapshot.shouldPreferStaticDiscovery()) {
            staticModules()
        } else {
            runCatching {
                loadModulesWithToolingApi(workspaceRoot)
            }.map { toolingModules ->
                when {
                    toolingModules.isEmpty() -> staticModules()
                    toolingModules.shouldFallbackToStaticModules(settingsSnapshot) -> mergeToolingAndStaticModules(
                        toolingModules = toolingModules,
                        staticModules = staticModules(),
                    )
                    else -> toolingModules
                }
            }.getOrElse {
                staticModules()
            }
        }

        return buildStandaloneWorkspaceLayout(gradleModules, extraClasspathRoots)
    }

    private fun loadModulesWithToolingApi(workspaceRoot: Path): List<GradleModuleModel> =
        ToolingApiPathNormalizer().let { pathNormalizer ->
            GradleConnector.newConnector()
                .forProjectDirectory(workspaceRoot.toFile())
                .connect()
                .use { connection ->
                    connection.getModel(IdeaProject::class.java)
                        .modules
                        .map { module -> toGradleModuleModel(module, pathNormalizer) }
                        .sortedBy(GradleModuleModel::gradlePath)
                }
        }

    internal fun buildStandaloneWorkspaceLayout(
        gradleModules: List<GradleModuleModel>,
        extraClasspathRoots: List<Path>,
    ): StandaloneWorkspaceLayout {
        val moduleModelsByIdeaName = gradleModules.associateBy(GradleModuleModel::ideaModuleName)
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
        }

        return StandaloneWorkspaceLayout(sourceModules = sourceModules)
    }

    private fun toGradleModuleModel(
        module: IdeaModule,
        pathNormalizer: ToolingApiPathNormalizer,
    ): GradleModuleModel {
        val contentRoots = module.contentRoots
        val mainSourceRoots = pathNormalizer.normalizeExistingSourceRoots(
            contentRoots
            .asSequence()
            .flatMap { contentRoot -> contentRoot.sourceDirectories.map { directory -> directory.directory.toPath() } }
        )
        val testSourceRoots = pathNormalizer.normalizeExistingSourceRoots(
            contentRoots
            .asSequence()
            .flatMap { contentRoot -> contentRoot.testDirectories.map { directory -> directory.directory.toPath() } }
        )
        val dependencies = module.dependencies.mapNotNull(::toGradleDependency)
        val compilerOutput = module.compilerOutput
        return GradleModuleModel(
            gradlePath = module.gradleProject.path,
            ideaModuleName = module.name,
            mainSourceRoots = mainSourceRoots,
            testSourceRoots = testSourceRoots,
            mainOutputRoots = listOfNotNull(compilerOutput.outputDir?.toPath()).map(::normalizeStandaloneModelPath),
            testOutputRoots = listOfNotNull(compilerOutput.testOutputDir?.toPath()).map(::normalizeStandaloneModelPath),
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
    dependencies = (dependencies + staticModule.dependencies).distinct(),
    mainOutputRoots = mainOutputRoots.ifEmpty { staticModule.mainOutputRoots },
    testOutputRoots = testOutputRoots.ifEmpty { staticModule.testOutputRoots },
)


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
internal data class GradleModuleModel(
    val gradlePath: String,
    val ideaModuleName: String,
    val mainSourceRoots: List<Path>,
    val testSourceRoots: List<Path>,
    val mainOutputRoots: List<Path>,
    val testOutputRoots: List<Path>,
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
        val testBinaryRoots = linkedSetOf<Path>()
        val mainDependencyNames = linkedSetOf<String>()
        val testDependencyNames = linkedSetOf<String>()

        if (mainSourceRoots.isEmpty()) {
            testBinaryRoots.addAll(mainOutputRoots)
        } else if (testSourceRoots.isNotEmpty()) {
            testDependencyNames.add(analysisModuleName(GradleSourceSet.MAIN))
        }

        dependencies.forEach { dependency ->
            when (dependency) {
                is GradleDependency.LibraryDependency -> {
                    if (dependency.scope in GradleSourceSet.MAIN.supportedDependencyScopes) {
                        mainBinaryRoots.add(dependency.binaryRoot)
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
            testBinaryRoots = testBinaryRoots.toList(),
            mainDependencyNames = mainDependencyNames.toList(),
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
    val testBinaryRoots: List<Path>,
    val mainDependencyNames: List<String>,
    val testDependencyNames: List<String>,
)

internal sealed interface GradleDependency {
    val scope: GradleDependencyScope

    data class ModuleDependency(
        val targetIdeaModuleName: String,
        override val scope: GradleDependencyScope,
    ) : GradleDependency

    data class LibraryDependency(
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
    TEST(
        id = "test",
        supportedDependencyScopes = setOf(
            GradleDependencyScope.COMPILE,
            GradleDependencyScope.PROVIDED,
            GradleDependencyScope.TEST,
            GradleDependencyScope.RUNTIME,
            GradleDependencyScope.UNKNOWN,
        ),
    ),
}

internal enum class GradleDependencyScope {
    COMPILE,
    PROVIDED,
    TEST,
    RUNTIME,
    UNKNOWN,
    ;

    companion object {
        fun from(dependency: IdeaDependency): GradleDependencyScope = when (dependency.scope?.scope?.uppercase()) {
            "COMPILE" -> COMPILE
            "PROVIDED" -> PROVIDED
            "TEST" -> TEST
            "RUNTIME" -> RUNTIME
            else -> UNKNOWN
        }
    }
}

internal data class GradleSettingsSnapshot(
    val includedProjectPaths: List<String>,
    val hasCompositeBuilds: Boolean,
) {
    fun shouldPreferStaticDiscovery(): Boolean = includedProjectPaths.size > maxIncludedProjectsForToolingApi

    fun projectPathsForStaticDiscovery(): List<String> = buildList {
        add(":")
        addAll(includedProjectPaths)
    }.distinct()

    companion object {
        private val includeBlockPattern = Regex("""(?s)\binclude\s*\((.*?)\)""")
        private val stringLiteralPattern = Regex("""[\"']([^\"']+)[\"']""")
        private val compositeBuildPattern = Regex("""\bincludeBuild\s*\(""")

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

            return GradleSettingsSnapshot(
                includedProjectPaths = includedProjectPaths,
                hasCompositeBuilds = compositeBuildPattern.containsMatchIn(settingsText),
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
