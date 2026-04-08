package io.github.amichne.kast.standalone.workspace

import io.github.amichne.kast.api.ModuleName
import io.github.amichne.kast.standalone.analysis.PathListAsStringSerializer
import io.github.amichne.kast.standalone.StandaloneSourceModuleSpec
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
internal data class GradleModuleModel(
    val gradlePath: String,
    val ideaModuleName: String,
    @Serializable(with = PathListAsStringSerializer::class)
    val mainSourceRoots: List<Path>,
    @Serializable(with = PathListAsStringSerializer::class)
    val testSourceRoots: List<Path>,
    @Serializable(with = PathListAsStringSerializer::class)
    val testFixturesSourceRoots: List<Path> = emptyList(),
    @Serializable(with = PathListAsStringSerializer::class)
    val mainOutputRoots: List<Path>,
    @Serializable(with = PathListAsStringSerializer::class)
    val testOutputRoots: List<Path>,
    @Serializable(with = PathListAsStringSerializer::class)
    val testFixturesOutputRoots: List<Path> = emptyList(),
    val dependencies: List<GradleDependency>,
) {
    private fun analysisModuleName(sourceSet: GradleSourceSet): ModuleName = ModuleName("$gradlePath[${sourceSet.id}]")

    fun toStandaloneSourceModuleSpecs(
        moduleModelsByIdeaName: Map<String, GradleModuleModel>,
        availableMainSourceModuleNames: Set<ModuleName>,
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

    fun mainDependencyModuleName(): ModuleName? = mainSourceRoots
        .takeIf(List<Path>::isNotEmpty)
        ?.let { analysisModuleName(GradleSourceSet.MAIN) }

    private fun resolveSourceSetDependencies(
        moduleModelsByIdeaName: Map<String, GradleModuleModel>,
        availableMainSourceModuleNames: Set<ModuleName>,
    ): ResolvedSourceSetDependencies {
        val mainBinaryRoots = linkedSetOf<Path>()
        val testFixturesBinaryRoots = linkedSetOf<Path>()
        val testBinaryRoots = linkedSetOf<Path>()
        val mainDependencyNames = linkedSetOf<ModuleName>()
        val testFixturesDependencyNames = linkedSetOf<ModuleName>()
        val testDependencyNames = linkedSetOf<ModuleName>()

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
        dependencyNames: MutableSet<ModuleName>,
        availableMainSourceModuleNames: Set<ModuleName>,
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
    val mainDependencyNames: List<ModuleName>,
    val testFixturesDependencyNames: List<ModuleName>,
    val testDependencyNames: List<ModuleName>,
)
