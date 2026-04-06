package io.github.amichne.kast.standalone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeoutException

class GradleWorkspaceDiscoveryTest {
    @Test
    fun `build standalone workspace layout preserves main and test source set semantics`() {
        val lib = GradleModuleModel(
            gradlePath = ":lib",
            ideaModuleName = "lib",
            mainSourceRoots = listOf(Path.of("/workspace/lib/src/main/kotlin")),
            testSourceRoots = emptyList(),
            mainOutputRoots = listOf(Path.of("/workspace/lib/build/classes/kotlin/main")),
            testOutputRoots = emptyList(),
            dependencies = emptyList(),
        )
        val generated = GradleModuleModel(
            gradlePath = ":generated",
            ideaModuleName = "generated",
            mainSourceRoots = emptyList(),
            testSourceRoots = emptyList(),
            mainOutputRoots = listOf(Path.of("/workspace/generated/build/classes/kotlin/main")),
            testOutputRoots = emptyList(),
            dependencies = emptyList(),
        )
        val app = GradleModuleModel(
            gradlePath = ":app",
            ideaModuleName = "app",
            mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
            testSourceRoots = listOf(Path.of("/workspace/app/src/test/kotlin")),
            mainOutputRoots = listOf(Path.of("/workspace/app/build/classes/kotlin/main")),
            testOutputRoots = listOf(Path.of("/workspace/app/build/classes/kotlin/test")),
            dependencies = listOf(
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = "lib",
                    scope = GradleDependencyScope.COMPILE,
                ),
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = "generated",
                    scope = GradleDependencyScope.COMPILE,
                ),
                GradleDependency.LibraryDependency(
                    binaryRoot = Path.of("/deps/runtime.jar"),
                    scope = GradleDependencyScope.RUNTIME,
                ),
                GradleDependency.LibraryDependency(
                    binaryRoot = Path.of("/deps/test-support.jar"),
                    scope = GradleDependencyScope.TEST,
                ),
            ),
        )

        val layout = GradleWorkspaceDiscovery.buildStandaloneWorkspaceLayout(
            gradleModules = listOf(app, lib, generated),
            extraClasspathRoots = listOf(Path.of("/deps/shared.jar"), Path.of("/deps/shared.jar")),
        )
        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)

        assertEquals(
            listOf(":lib[main]"),
            modulesByName.getValue(":app[main]").dependencyModuleNames,
        )
        assertEquals(
            listOf(":app[main]", ":lib[main]"),
            modulesByName.getValue(":app[test]").dependencyModuleNames,
        )
        assertEquals(
            listOf(
                Path.of("/deps/runtime.jar"),
                Path.of("/deps/shared.jar"),
                Path.of("/workspace/generated/build/classes/kotlin/main"),
            ),
            modulesByName.getValue(":app[main]").binaryRoots,
        )
        assertEquals(
            listOf(
                Path.of("/deps/runtime.jar"),
                Path.of("/deps/shared.jar"),
                Path.of("/deps/test-support.jar"),
                Path.of("/workspace/generated/build/classes/kotlin/main"),
            ),
            modulesByName.getValue(":app[test]").binaryRoots,
        )
    }

    @Test
    fun `tooling api path normalizer checks each normalized source root once`() {
        val checkedPaths = mutableListOf<Path>()
        val pathNormalizer = ToolingApiPathNormalizer { path ->
            checkedPaths.add(path)
            true
        }
        val rawPath = Path.of("module/../module/src/main/kotlin")
        val normalizedPath = rawPath.toAbsolutePath().normalize()

        assertEquals(
            listOf(normalizedPath),
            pathNormalizer.normalizeExistingSourceRoots(
                sequenceOf(rawPath, normalizedPath),
            ),
        )
        assertEquals(
            listOf(normalizedPath),
            pathNormalizer.normalizeExistingSourceRoots(sequenceOf(normalizedPath)),
        )
        assertEquals(listOf(normalizedPath), checkedPaths)
    }

    @Test
    fun `module dependency lookup resolves by gradle path when idea module name differs`() {
        val lib = GradleModuleModel(
            gradlePath = ":core:lib",
            ideaModuleName = "myproject.core.lib",
            mainSourceRoots = listOf(Path.of("/workspace/core/lib/src/main/kotlin")),
            testSourceRoots = emptyList(),
            mainOutputRoots = listOf(Path.of("/workspace/core/lib/build/classes/kotlin/main")),
            testOutputRoots = emptyList(),
            dependencies = emptyList(),
        )
        val app = GradleModuleModel(
            gradlePath = ":app",
            ideaModuleName = "myproject.app",
            mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
            testSourceRoots = emptyList(),
            mainOutputRoots = listOf(Path.of("/workspace/app/build/classes/kotlin/main")),
            testOutputRoots = emptyList(),
            dependencies = listOf(
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = ":core:lib",
                    scope = GradleDependencyScope.COMPILE,
                ),
            ),
        )

        val layout = GradleWorkspaceDiscovery.buildStandaloneWorkspaceLayout(
            gradleModules = listOf(app, lib),
            extraClasspathRoots = emptyList(),
        )
        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)

        assertEquals(
            listOf(":core:lib[main]"),
            modulesByName.getValue(":app[main]").dependencyModuleNames,
        )
    }

    @Test
    fun `detect incomplete classpath returns warnings for modules with zero library dependencies`() {
        val modules = listOf(
            GradleModuleModel(
                gradlePath = ":empty",
                ideaModuleName = "empty",
                mainSourceRoots = listOf(Path.of("/workspace/empty/src/main/kotlin")),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = emptyList(),
            ),
            GradleModuleModel(
                gradlePath = ":app",
                ideaModuleName = "app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = listOf(
                    GradleDependency.ModuleDependency(
                        targetIdeaModuleName = ":lib",
                        scope = GradleDependencyScope.COMPILE,
                    ),
                ),
            ),
        )

        val warnings = detectIncompleteClasspath(modules)

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains(":empty"))
        assertFalse(warnings.any { warning -> warning.contains(":app") })
    }

    @Test
    fun `enrich static modules with tooling api libraries preserves static modules when tooling api times out`() {
        val staticModules = listOf(
            GradleModuleModel(
                gradlePath = ":app",
                ideaModuleName = "app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = listOf(
                    GradleDependency.ModuleDependency(
                        targetIdeaModuleName = ":lib",
                        scope = GradleDependencyScope.COMPILE,
                    ),
                ),
            ),
        )
        val warningMessages = mutableListOf<String>()

        val result = GradleWorkspaceDiscovery.enrichStaticModulesWithToolingApiLibraries(
            workspaceRoot = Path.of("/workspace"),
            staticModules = staticModules,
            toolingApiLoader = {
                throw TimeoutException("tooling api timed out")
            },
            warningSink = { warning -> warningMessages.add(warning) },
        )

        assertEquals(staticModules, result.modules)
        assertEquals(1, result.diagnostics.warnings.size)
        assertTrue(result.diagnostics.warnings.single().contains("timed out"))
        assertEquals(result.diagnostics.warnings, warningMessages)
    }

    @Test
    fun `enrich static modules with tooling api libraries merges library deps from tooling api onto static modules`() {
        val moduleDependency = GradleDependency.ModuleDependency(
            targetIdeaModuleName = "lib",
            scope = GradleDependencyScope.COMPILE,
        )
        val libraryDependency = GradleDependency.LibraryDependency(
            binaryRoot = Path.of("/deps/runtime.jar"),
            scope = GradleDependencyScope.RUNTIME,
        )
        val staticModules = listOf(
            GradleModuleModel(
                gradlePath = ":app",
                ideaModuleName = "app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = listOf(moduleDependency),
            ),
        )

        val result = GradleWorkspaceDiscovery.enrichStaticModulesWithToolingApiLibraries(
            workspaceRoot = Path.of("/workspace"),
            staticModules = staticModules,
            toolingApiLoader = {
                listOf(
                    GradleModuleModel(
                        gradlePath = ":app",
                        ideaModuleName = "app",
                        mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
                        testSourceRoots = emptyList(),
                        mainOutputRoots = emptyList(),
                        testOutputRoots = emptyList(),
                        dependencies = listOf(libraryDependency),
                    ),
                )
            },
        )

        val mergedDependencies = result.modules.single().dependencies
        assertTrue(mergedDependencies.contains(moduleDependency))
        assertTrue(mergedDependencies.contains(libraryDependency))
    }
}
