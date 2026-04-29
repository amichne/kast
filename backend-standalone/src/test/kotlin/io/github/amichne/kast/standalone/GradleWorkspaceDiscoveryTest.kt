package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.client.KastConfig
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GradleWorkspaceDiscoveryTest {
    @Test
    fun `resolve tooling api timeout millis scales with module count`() {
        assertEquals(defaultToolingApiTimeoutMillis, resolveToolingApiTimeoutMillis(moduleCount = 50))
        assertEquals(60_000L, resolveToolingApiTimeoutMillis(moduleCount = 300))
        assertEquals(300_000L, resolveToolingApiTimeoutMillis(moduleCount = 2_000))
    }

    @Test
    fun `resolve tooling api timeout millis uses config override`() {
        val timeoutMillis = resolveToolingApiTimeoutMillis(
            moduleCount = 950,
            config = KastConfig.defaults().copy(
                gradle = KastConfig.defaults().gradle.copy(toolingApiTimeoutMillis = 123_456L),
            ),
        )

        assertEquals(123_456L, timeoutMillis)
    }

    @Test
    fun `discover uses configured max included projects threshold`() {
        val staticModules = listOf(
            gradleModule(
                gradlePath = ":app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
            ),
        )
        var toolingApiCalled = false

        GradleWorkspaceDiscovery.discover(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = GradleSettingsSnapshot(
                includedProjectPaths = listOf(":app", ":lib"),
                hasCompositeBuilds = false,
            ),
            staticModulesProvider = { staticModules },
            toolingApiLoader = { _, _ ->
                toolingApiCalled = true
                staticModules
            },
            cache = WorkspaceDiscoveryCache(enabled = false),
            config = KastConfig.defaults().copy(
                gradle = KastConfig.defaults().gradle.copy(maxIncludedProjects = 1),
            ),
        )

        assertTrue(toolingApiCalled)
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
            toolingApiLoader = { _, _ ->
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
            toolingApiLoader = { _, _ ->
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

    @Test
    fun `discoverPhased returns static layout immediately when tooling api is slow`() {
        val staticModules = listOf(
            gradleModule(
                gradlePath = ":app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
            ),
        )
        val settingsSnapshot = largeSettingsSnapshot(moduleCount = 250)
        val unblockToolingApi = CountDownLatch(1)

        val startNanos = System.nanoTime()
        val result = GradleWorkspaceDiscovery.discoverPhased(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = settingsSnapshot,
            staticModulesProvider = { staticModules },
            toolingApiLoader = { _, _ ->
                unblockToolingApi.await()
                staticModules
            },
            cache = WorkspaceDiscoveryCache(enabled = false),
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        try {
            assertTrue(elapsedMillis < 100, "expected phased discovery to return immediately, took ${elapsedMillis}ms")
            assertEquals(
                GradleWorkspaceDiscovery.buildStandaloneWorkspaceLayout(staticModules, extraClasspathRoots = emptyList()).sourceModules,
                result.initialLayout.sourceModules,
            )
            assertFalse(checkNotNull(result.enrichmentFuture).isDone)
            unblockToolingApi.countDown()
            assertEquals(
                result.initialLayout.sourceModules,
                result.enrichmentFuture.get(1, TimeUnit.SECONDS).sourceModules,
            )
        } finally {
            unblockToolingApi.countDown()
        }
    }

    @Test
    fun `discoverPhased enrichment future merges tooling api results`() {
        val staticModules = listOf(
            gradleModule(
                gradlePath = ":app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
                dependencies = listOf(
                    GradleDependency.ModuleDependency(
                        targetIdeaModuleName = ":lib",
                        scope = GradleDependencyScope.COMPILE,
                    ),
                ),
            ),
            gradleModule(
                gradlePath = ":lib",
                mainSourceRoots = listOf(Path.of("/workspace/lib/src/main/kotlin")),
            ),
        )
        val libraryDependency = GradleDependency.LibraryDependency(
            binaryRoot = Path.of("/deps/runtime.jar"),
            scope = GradleDependencyScope.RUNTIME,
        )

        val result = GradleWorkspaceDiscovery.discoverPhased(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = largeSettingsSnapshot(moduleCount = 250),
            staticModulesProvider = { staticModules },
            toolingApiLoader = { _, _ ->
                listOf(
                    gradleModule(
                        gradlePath = ":app",
                        mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
                        dependencies = listOf(libraryDependency),
                    ),
                    gradleModule(
                        gradlePath = ":lib",
                        mainSourceRoots = listOf(Path.of("/workspace/lib/src/main/kotlin")),
                    ),
                )
            },
            cache = WorkspaceDiscoveryCache(enabled = false),
        )

        val enrichedLayout = checkNotNull(result.enrichmentFuture).get(1, TimeUnit.SECONDS)
        val modulesByName = enrichedLayout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)

        assertEquals(listOf(ModuleName(":lib[main]")), modulesByName.getValue(ModuleName(":app[main]")).dependencyModuleNames)
        assertTrue(modulesByName.getValue(ModuleName(":app[main]")).binaryRoots.contains(Path.of("/deps/runtime.jar")))
    }

    @Test
    fun `discoverPhased enrichment future tolerates tooling api failure`() {
        val staticModules = listOf(
            gradleModule(
                gradlePath = ":app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
            ),
        )

        val result = GradleWorkspaceDiscovery.discoverPhased(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = largeSettingsSnapshot(moduleCount = 250),
            staticModulesProvider = { staticModules },
            toolingApiLoader = { _, _ ->
                throw TimeoutException("tooling api timed out")
            },
            cache = WorkspaceDiscoveryCache(enabled = false),
        )

        val enrichedLayout = checkNotNull(result.enrichmentFuture).get(1, TimeUnit.SECONDS)

        assertEquals(result.initialLayout.sourceModules, enrichedLayout.sourceModules)
        assertTrue(enrichedLayout.diagnostics.warnings.any { warning -> warning.contains("timed out") })
    }

    @Test
    fun `discoverPhased with small project skips phased approach`() {
        val staticModules = listOf(
            gradleModule(
                gradlePath = ":app",
                mainSourceRoots = listOf(Path.of("/workspace/app/src/main/kotlin")),
                dependencies = listOf(
                    GradleDependency.ModuleDependency(
                        targetIdeaModuleName = ":lib",
                        scope = GradleDependencyScope.COMPILE,
                    ),
                ),
            ),
            gradleModule(
                gradlePath = ":lib",
                mainSourceRoots = listOf(Path.of("/workspace/lib/src/main/kotlin")),
            ),
        )
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
        val settingsSnapshot = GradleSettingsSnapshot(
            includedProjectPaths = listOf(":app", ":lib"),
            hasCompositeBuilds = false,
        )

        val noCache = WorkspaceDiscoveryCache(enabled = false)
        val phased = GradleWorkspaceDiscovery.discoverPhased(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = settingsSnapshot,
            staticModulesProvider = { staticModules },
            toolingApiLoader = { _, _ -> toolingModules },
            cache = noCache,
        )
        val direct = GradleWorkspaceDiscovery.discover(
            workspaceRoot = Path.of("/workspace"),
            extraClasspathRoots = emptyList(),
            settingsSnapshot = settingsSnapshot,
            staticModulesProvider = { staticModules },
            toolingApiLoader = { _, _ -> toolingModules },
            cache = noCache,
        )

        assertNull(phased.enrichmentFuture)
        assertEquals(direct, phased.initialLayout)
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
