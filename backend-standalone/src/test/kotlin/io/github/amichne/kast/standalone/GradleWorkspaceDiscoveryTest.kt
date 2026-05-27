package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.client.fields.GradleToolingApiTimeoutMillis
import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.fields.GradleDiscoveryMode
import io.github.amichne.kast.api.client.fields.GradleDiscoveryModeField
import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.standalone.cache.WorkspaceDiscoveryCache
import io.github.amichne.kast.standalone.workspace.GradleDependency
import io.github.amichne.kast.standalone.workspace.GradleDependencyScope
import io.github.amichne.kast.standalone.workspace.GradleModuleModel
import io.github.amichne.kast.standalone.workspace.GradleSettingsSnapshot
import io.github.amichne.kast.standalone.workspace.GradleWorkspaceDiscovery
import io.github.amichne.kast.standalone.workspace.ToolingApiPathNormalizer
import io.github.amichne.kast.standalone.workspace.defaultToolingApiTimeoutMillis
import io.github.amichne.kast.standalone.workspace.detectIncompleteClasspath
import io.github.amichne.kast.standalone.workspace.resolveToolingApiTimeoutMillis
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeoutException

class GradleWorkspaceDiscoveryTest {
    @Test
    fun `resolve tooling api timeout millis scales with module count`() {
        assertEquals(defaultToolingApiTimeoutMillis, resolveToolingApiTimeoutMillis(moduleCount = 50))
        assertEquals(120_000L, resolveToolingApiTimeoutMillis(moduleCount = 300))
        assertEquals(300_000L, resolveToolingApiTimeoutMillis(moduleCount = 2_000))
    }

    @Test
    fun `resolve tooling api timeout millis uses config override`() {
        val timeoutMillis = resolveToolingApiTimeoutMillis(
            moduleCount = 950,
            config = KastConfig.defaults().copy(
                gradle = KastConfig.defaults().gradle.copy(toolingApiTimeoutMillis = GradleToolingApiTimeoutMillis(123_456L)),
            ),
        )

        assertEquals(123_456L, timeoutMillis)
    }

    @Test
    fun `build standalone workspace layout preserves main, testFixtures, and test source set semantics`() {
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
            testFixturesSourceRoots = listOf(Path.of("/workspace/app/src/testFixtures/kotlin")),
            testSourceRoots = listOf(Path.of("/workspace/app/src/test/kotlin")),
            mainOutputRoots = listOf(Path.of("/workspace/app/build/classes/kotlin/main")),
            testFixturesOutputRoots = listOf(Path.of("/workspace/app/build/classes/kotlin/testFixtures")),
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

        assertEquals(setOf(":app[main]", ":app[testFixtures]", ":app[test]", ":lib[main]").map(::ModuleName).toSet(), modulesByName.keys)
        assertEquals(
            listOf(ModuleName(":lib[main]")),
            modulesByName.getValue(ModuleName(":app[main]")).dependencyModuleNames,
        )
        assertEquals(
            listOf(ModuleName(":app[main]"), ModuleName(":lib[main]")),
            modulesByName.getValue(ModuleName(":app[testFixtures]")).dependencyModuleNames,
        )
        assertEquals(
            listOf(ModuleName(":app[main]"), ModuleName(":app[testFixtures]"), ModuleName(":lib[main]")),
            modulesByName.getValue(ModuleName(":app[test]")).dependencyModuleNames,
        )
        assertEquals(
            listOf(
                Path.of("/deps/runtime.jar"),
                Path.of("/deps/shared.jar"),
                Path.of("/workspace/generated/build/classes/kotlin/main"),
            ),
            modulesByName.getValue(ModuleName(":app[main]")).binaryRoots,
        )
        assertEquals(
            listOf(
                Path.of("/deps/runtime.jar"),
                Path.of("/deps/shared.jar"),
                Path.of("/workspace/generated/build/classes/kotlin/main"),
            ),
            modulesByName.getValue(ModuleName(":app[testFixtures]")).binaryRoots,
        )
        assertEquals(
            listOf(
                Path.of("/deps/runtime.jar"),
                Path.of("/deps/shared.jar"),
                Path.of("/deps/test-support.jar"),
                Path.of("/workspace/generated/build/classes/kotlin/main"),
            ),
            modulesByName.getValue(ModuleName(":app[test]")).binaryRoots,
        )
    }

    @Test
    fun `build standalone workspace layout keeps testFixtures scoped dependencies out of main`() {
        val lib = GradleModuleModel(
            gradlePath = ":lib",
            ideaModuleName = "lib",
            mainSourceRoots = listOf(Path.of("/workspace/lib/src/main/kotlin")),
            testSourceRoots = emptyList(),
            mainOutputRoots = listOf(Path.of("/workspace/lib/build/classes/kotlin/main")),
            testOutputRoots = emptyList(),
            dependencies = emptyList(),
        )
        val app = GradleModuleModel(
            gradlePath = ":app",
            ideaModuleName = "app",
            mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
            testFixturesSourceRoots = listOf(Path.of("/workspace/app/src/testFixtures/kotlin")),
            testSourceRoots = listOf(Path.of("/workspace/app/src/test/kotlin")),
            mainOutputRoots = listOf(Path.of("/workspace/app/build/classes/kotlin/main")),
            testFixturesOutputRoots = listOf(Path.of("/workspace/app/build/classes/kotlin/testFixtures")),
            testOutputRoots = listOf(Path.of("/workspace/app/build/classes/kotlin/test")),
            dependencies = listOf(
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = "lib",
                    scope = GradleDependencyScope.TEST_FIXTURES,
                ),
                GradleDependency.LibraryDependency(
                    binaryRoot = Path.of("/deps/fixture-support.jar"),
                    scope = GradleDependencyScope.TEST_FIXTURES,
                ),
            ),
        )

        val layout = GradleWorkspaceDiscovery.buildStandaloneWorkspaceLayout(
            gradleModules = listOf(app, lib),
            extraClasspathRoots = emptyList(),
        )
        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)

        assertEquals(emptyList<ModuleName>(), modulesByName.getValue(ModuleName(":app[main]")).dependencyModuleNames)
        assertEquals(emptyList<Path>(), modulesByName.getValue(ModuleName(":app[main]")).binaryRoots)
        assertEquals(
            listOf(ModuleName(":app[main]"), ModuleName(":lib[main]")),
            modulesByName.getValue(ModuleName(":app[testFixtures]")).dependencyModuleNames,
        )
        assertEquals(
            listOf(Path.of("/deps/fixture-support.jar")),
            modulesByName.getValue(ModuleName(":app[testFixtures]")).binaryRoots,
        )
        assertEquals(
            listOf(ModuleName(":app[main]"), ModuleName(":app[testFixtures]"), ModuleName(":lib[main]")),
            modulesByName.getValue(ModuleName(":app[test]")).dependencyModuleNames,
        )
        assertEquals(
            listOf(Path.of("/deps/fixture-support.jar")),
            modulesByName.getValue(ModuleName(":app[test]")).binaryRoots,
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
            listOf(ModuleName(":core:lib[main]")),
            modulesByName.getValue(ModuleName(":app[main]")).dependencyModuleNames,
        )
    }

    @Test
    fun `build standalone workspace layout trims cyclic module dependency edges`() {
        val first = GradleModuleModel(
            gradlePath = ":first",
            ideaModuleName = "first",
            mainSourceRoots = listOf(Path.of("/workspace/first/src/main/kotlin")),
            testSourceRoots = emptyList(),
            mainOutputRoots = emptyList(),
            testOutputRoots = emptyList(),
            dependencies = listOf(
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = ":second",
                    scope = GradleDependencyScope.COMPILE,
                ),
            ),
        )
        val second = GradleModuleModel(
            gradlePath = ":second",
            ideaModuleName = "second",
            mainSourceRoots = listOf(Path.of("/workspace/second/src/main/kotlin")),
            testSourceRoots = emptyList(),
            mainOutputRoots = emptyList(),
            testOutputRoots = emptyList(),
            dependencies = listOf(
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = ":first",
                    scope = GradleDependencyScope.COMPILE,
                ),
            ),
        )

        val layout = GradleWorkspaceDiscovery.buildStandaloneWorkspaceLayout(
            gradleModules = listOf(first, second),
            extraClasspathRoots = emptyList(),
        )

        topologicallySortSourceModules(layout.sourceModules)
        assertEquals(
            1,
            layout.sourceModules.sumOf { module -> module.dependencyModuleNames.size },
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
    fun `detect incomplete classpath ignores root modules with no source roots`() {
        val modules = listOf(
            GradleModuleModel(
                gradlePath = ":",
                ideaModuleName = "workspace",
                mainSourceRoots = emptyList(),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = emptyList(),
            ),
            GradleModuleModel(
                gradlePath = ":lib",
                ideaModuleName = "lib",
                mainSourceRoots = listOf(Path.of("/workspace/lib/src/main/kotlin")),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = emptyList(),
            ),
        )

        val warnings = detectIncompleteClasspath(modules)

        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains(":lib"))
        assertFalse(warnings.any { warning -> warning.contains("workspace") && !warning.contains(":lib") })
    }

    @Test
    fun `discover fails observably when Gradle-owned discovery fails`() {
        val warningMessages = mutableListOf<String>()

        val failure = assertThrows(IllegalStateException::class.java) {
            GradleWorkspaceDiscovery.discover(
                workspaceRoot = Path.of("/workspace"),
                extraClasspathRoots = emptyList(),
                settingsSnapshot = largeSettingsSnapshot(moduleCount = 250),
                constrainedGradleLoader = { _, _ -> throw TimeoutException("tooling api timed out") },
                warningSink = warningMessages::add,
                cache = WorkspaceDiscoveryCache(enabled = false),
            )
        }

        assertTrue(failure.message!!.contains("Gradle-owned workspace discovery failed"))
        assertTrue(warningMessages.single().contains("timed out"))
    }

    @Test
    fun `discover builds layout from Gradle-owned modules without static provider`() {
        val toolingModules = listOf(
            gradleModule(
                gradlePath = ":app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
                dependencies = listOf(
                    GradleDependency.ModuleDependency(
                        targetIdeaModuleName = ":lib",
                        scope = GradleDependencyScope.COMPILE,
                    ),
                    GradleDependency.LibraryDependency(
                        binaryRoot = Path.of("/deps/runtime.jar"),
                        scope = GradleDependencyScope.RUNTIME,
                    ),
                ),
            ),
            gradleModule(
                gradlePath = ":lib",
                mainSourceRoots = listOf(Path.of("/workspace/lib/src/main/kotlin")),
            ),
        )

        val layout = GradleWorkspaceDiscovery.discover(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = largeSettingsSnapshot(moduleCount = 250),
            constrainedGradleLoader = { _, _ -> toolingModules },
            cache = WorkspaceDiscoveryCache(enabled = false),
        )
        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)

        assertEquals(listOf(ModuleName(":lib[main]")), modulesByName.getValue(ModuleName(":app[main]")).dependencyModuleNames)
        assertTrue(modulesByName.getValue(ModuleName(":app[main]")).binaryRoots.contains(Path.of("/deps/runtime.jar")))
    }

    @Test
    fun `constrained Gradle discovery is the default loader`() {
        val loadedBy = mutableListOf<GradleDiscoveryMode>()

        GradleWorkspaceDiscovery.discover(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = largeSettingsSnapshot(moduleCount = 2),
            constrainedGradleLoader = { _, _ ->
                loadedBy += GradleDiscoveryMode.CONSTRAINED
                listOf(gradleModule(":app", mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin"))))
            },
            completeIdeaProjectLoader = { _, _ ->
                loadedBy += GradleDiscoveryMode.COMPLETE
                listOf(gradleModule(":app", mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin"))))
            },
            cache = WorkspaceDiscoveryCache(enabled = false),
        )

        assertEquals(listOf(GradleDiscoveryMode.CONSTRAINED), loadedBy)
    }

    @Test
    fun `complete Gradle discovery mode is explicit opt in`() {
        val loadedBy = mutableListOf<GradleDiscoveryMode>()
        val config = KastConfig.defaults().copy(
            gradle = KastConfig.defaults().gradle.copy(discoveryMode = GradleDiscoveryModeField(GradleDiscoveryMode.COMPLETE)),
        )

        GradleWorkspaceDiscovery.discover(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = largeSettingsSnapshot(moduleCount = 2),
            constrainedGradleLoader = { _, _ ->
                loadedBy += GradleDiscoveryMode.CONSTRAINED
                listOf(gradleModule(":app", mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin"))))
            },
            completeIdeaProjectLoader = { _, _ ->
                loadedBy += GradleDiscoveryMode.COMPLETE
                listOf(gradleModule(":app", mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin"))))
            },
            config = config,
            cache = WorkspaceDiscoveryCache(enabled = false),
        )

        assertEquals(listOf(GradleDiscoveryMode.COMPLETE), loadedBy)
    }

    private fun gradleModule(
        gradlePath: String,
        mainSourceRoots: List<Path>,
        testSourceRoots: List<Path> = emptyList(),
        testFixturesSourceRoots: List<Path> = emptyList(),
        mainOutputRoots: List<Path> = emptyList(),
        testOutputRoots: List<Path> = emptyList(),
        testFixturesOutputRoots: List<Path> = emptyList(),
        dependencies: List<GradleDependency> = emptyList(),
    ): GradleModuleModel = GradleModuleModel(
        gradlePath = gradlePath,
        ideaModuleName = gradlePath,
        mainSourceRoots = mainSourceRoots,
        testSourceRoots = testSourceRoots,
        testFixturesSourceRoots = testFixturesSourceRoots,
        mainOutputRoots = mainOutputRoots,
        testOutputRoots = testOutputRoots,
        testFixturesOutputRoots = testFixturesOutputRoots,
        dependencies = dependencies,
    )

    private fun largeSettingsSnapshot(moduleCount: Int): GradleSettingsSnapshot = GradleSettingsSnapshot(
        includedProjectPaths = (1..moduleCount).map { index -> ":module$index" },
        hasCompositeBuilds = false,
    )
}
