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
        val gradleModules = if (settingsSnapshot.shouldPreferStaticDiscovery()) {
            StaticGradleWorkspaceDiscovery.discoverModules(workspaceRoot, settingsSnapshot)
        } else {
            val staticModules = {
                StaticGradleWorkspaceDiscovery.discoverModules(workspaceRoot, settingsSnapshot)
            }
            runCatching {
                loadModulesWithToolingApi(workspaceRoot)
            }.mapCatching { toolingModules ->
                if (toolingModules.shouldFallbackToStaticModules(settingsSnapshot)) {
                    staticModules()
                } else {
                    toolingModules
                }
            }.getOrElse {
                staticModules()
            }
        }

        return buildStandaloneWorkspaceLayout(gradleModules, extraClasspathRoots)
    }

    private fun loadModulesWithToolingApi(workspaceRoot: Path): List<GradleModuleModel> =
        GradleConnector.newConnector()
            .forProjectDirectory(workspaceRoot.toFile())
            .connect()
            .use { connection ->
                connection.getModel(IdeaProject::class.java)
                    .modules
                    .map(::toGradleModuleModel)
                    .sortedBy(GradleModuleModel::gradlePath)
            }

    private fun buildStandaloneWorkspaceLayout(
        gradleModules: List<GradleModuleModel>,
        extraClasspathRoots: List<Path>,
    ): StandaloneWorkspaceLayout {
        val moduleModelsByIdeaName = gradleModules.associateBy(GradleModuleModel::ideaModuleName)
        val sourceModuleNames = gradleModules.flatMap { module ->
            buildList {
                if (module.mainSourceRoots.isNotEmpty()) {
                    add(module.analysisModuleName(GradleSourceSet.MAIN))
                }
                if (module.testSourceRoots.isNotEmpty()) {
                    add(module.analysisModuleName(GradleSourceSet.TEST))
                }
            }
        }.toSet()

        val sourceModules = buildList {
            for (module in gradleModules) {
                if (module.mainSourceRoots.isNotEmpty()) {
                    add(
                        StandaloneSourceModuleSpec(
                            name = module.analysisModuleName(GradleSourceSet.MAIN),
                            sourceRoots = module.mainSourceRoots,
                            binaryRoots = (
                                module.binaryRootsFor(GradleSourceSet.MAIN) +
                                    module.fallbackOutputRootsFor(
                                        sourceSet = GradleSourceSet.MAIN,
                                        moduleModelsByIdeaName = moduleModelsByIdeaName,
                                        availableSourceModuleNames = sourceModuleNames,
                                    ) +
                                    extraClasspathRoots
                            ).distinct().sorted(),
                            dependencyModuleNames = module.moduleDependencyNamesFor(
                                sourceSet = GradleSourceSet.MAIN,
                                moduleModelsByIdeaName = moduleModelsByIdeaName,
                                availableSourceModuleNames = sourceModuleNames,
                            ),
                        ),
                    )
                }
                if (module.testSourceRoots.isNotEmpty()) {
                    add(
                        StandaloneSourceModuleSpec(
                            name = module.analysisModuleName(GradleSourceSet.TEST),
                            sourceRoots = module.testSourceRoots,
                            binaryRoots = (
                                module.binaryRootsFor(GradleSourceSet.TEST) +
                                    module.fallbackOutputRootsFor(
                                        sourceSet = GradleSourceSet.TEST,
                                        moduleModelsByIdeaName = moduleModelsByIdeaName,
                                        availableSourceModuleNames = sourceModuleNames,
                                    ) +
                                    extraClasspathRoots
                            ).distinct().sorted(),
                            dependencyModuleNames = module.moduleDependencyNamesFor(
                                sourceSet = GradleSourceSet.TEST,
                                moduleModelsByIdeaName = moduleModelsByIdeaName,
                                availableSourceModuleNames = sourceModuleNames,
                            ),
                        ),
                    )
                }
            }
        }

        return StandaloneWorkspaceLayout(sourceModules = sourceModules)
    }

    private fun toGradleModuleModel(module: IdeaModule): GradleModuleModel {
        val contentRoots = module.contentRoots
        val mainSourceRoots = contentRoots
            .flatMap { contentRoot -> contentRoot.sourceDirectories.map { directory -> directory.directory.toPath() } }
            .filter { path -> Files.exists(path) }
            .map(::normalizeStandalonePath)
            .filter(::isSupportedStandaloneSourceRoot)
            .distinct()
            .sorted()
        val testSourceRoots = contentRoots
            .flatMap { contentRoot -> contentRoot.testDirectories.map { directory -> directory.directory.toPath() } }
            .filter { path -> Files.exists(path) }
            .map(::normalizeStandalonePath)
            .filter(::isSupportedStandaloneSourceRoot)
            .distinct()
            .sorted()
        val dependencies = module.dependencies.mapNotNull(::toGradleDependency)
        val compilerOutput = module.compilerOutput
        return GradleModuleModel(
            gradlePath = module.gradleProject.path,
            ideaModuleName = module.name,
            mainSourceRoots = mainSourceRoots,
            testSourceRoots = testSourceRoots,
            mainOutputRoots = listOfNotNull(compilerOutput.outputDir?.toPath()).map(::normalizeStandalonePath),
            testOutputRoots = listOfNotNull(compilerOutput.testOutputDir?.toPath()).map(::normalizeStandalonePath),
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
                ?.let(::normalizeStandalonePath)
                ?.let { file -> GradleDependency.LibraryDependency(binaryRoot = file, scope = scope) }
            else -> null
        }
    }
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
internal data class GradleModuleModel(
    val gradlePath: String,
    val ideaModuleName: String,
    val mainSourceRoots: List<Path>,
    val testSourceRoots: List<Path>,
    val mainOutputRoots: List<Path>,
    val testOutputRoots: List<Path>,
    val dependencies: List<GradleDependency>,
) {
    fun analysisModuleName(sourceSet: GradleSourceSet): String = "$gradlePath[${sourceSet.id}]"

    fun binaryRootsFor(sourceSet: GradleSourceSet): List<Path> {
        val supportedScopes = sourceSet.supportedDependencyScopes
        return dependencies
            .filterIsInstance<GradleDependency.LibraryDependency>()
            .filter { dependency -> dependency.scope in supportedScopes }
            .map(GradleDependency.LibraryDependency::binaryRoot)
            .distinct()
            .sorted()
    }

    fun moduleDependencyNamesFor(
        sourceSet: GradleSourceSet,
        moduleModelsByIdeaName: Map<String, GradleModuleModel>,
        availableSourceModuleNames: Set<String>,
    ): List<String> {
        val dependencyNames = linkedSetOf<String>()
        if (sourceSet == GradleSourceSet.TEST && mainSourceRoots.isNotEmpty()) {
            dependencyNames += analysisModuleName(GradleSourceSet.MAIN)
        }
        dependencies
            .filterIsInstance<GradleDependency.ModuleDependency>()
            .filter { dependency -> dependency.scope in sourceSet.supportedDependencyScopes }
            .mapNotNull { dependency -> moduleModelsByIdeaName[dependency.targetIdeaModuleName] }
            .map(GradleModuleModel::mainDependencyModuleName)
            .filterNotNull()
            .filter(availableSourceModuleNames::contains)
            .forEach(dependencyNames::add)
        return dependencyNames.toList()
    }

    fun fallbackOutputRootsFor(
        sourceSet: GradleSourceSet,
        moduleModelsByIdeaName: Map<String, GradleModuleModel>,
        availableSourceModuleNames: Set<String>,
    ): List<Path> {
        val fallbackRoots = linkedSetOf<Path>()
        if (sourceSet == GradleSourceSet.TEST && mainSourceRoots.isEmpty()) {
            fallbackRoots += mainOutputRoots
        }
        dependencies
            .filterIsInstance<GradleDependency.ModuleDependency>()
            .filter { dependency -> dependency.scope in sourceSet.supportedDependencyScopes }
            .mapNotNull { dependency -> moduleModelsByIdeaName[dependency.targetIdeaModuleName] }
            .filter { dependency -> dependency.mainDependencyModuleName() !in availableSourceModuleNames }
            .flatMap(GradleModuleModel::mainOutputRoots)
            .forEach(fallbackRoots::add)
        return fallbackRoots.toList().sorted()
    }

    private fun mainDependencyModuleName(): String? = mainSourceRoots
        .takeIf(List<Path>::isNotEmpty)
        ?.let { analysisModuleName(GradleSourceSet.MAIN) }
}

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
    fun shouldPreferStaticDiscovery(): Boolean = hasCompositeBuilds || includedProjectPaths.size > maxIncludedProjectsForToolingApi

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

internal fun isSupportedStandaloneSourceRoot(path: Path): Boolean = path.fileName?.toString() != "java"
