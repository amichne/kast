package io.github.amichne.kast.standalone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class GradleWorkspaceDiscoveryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resolveToolingApiTimeoutMillis returns env var when set`() {
        val timeoutMillis = resolveToolingApiTimeoutMillis(
            moduleCount = 5,
            envReader = { key -> if (key == "KAST_GRADLE_TOOLING_TIMEOUT_MS") "120000" else null },
        )

        assertEquals(120_000L, timeoutMillis)
    }

    @Test
    fun `resolveToolingApiTimeoutMillis scales with module count`() {
        val envReader: (String) -> String? = { null }

        assertEquals(defaultToolingApiTimeoutMillis, resolveToolingApiTimeoutMillis(50, envReader))
        assertEquals(60_000L, resolveToolingApiTimeoutMillis(300, envReader))
        assertEquals(maxToolingApiTimeoutMillis, resolveToolingApiTimeoutMillis(2_000, envReader))
    }

    @Test
    fun `resolveToolingApiTimeoutMillis uses default for small projects`() {
        val envReader: (String) -> String? = { null }

        assertEquals(defaultToolingApiTimeoutMillis, resolveToolingApiTimeoutMillis(0, envReader))
        assertEquals(defaultToolingApiTimeoutMillis, resolveToolingApiTimeoutMillis(10, envReader))
    }

    @Test
    fun `cache key changes when settings file content changes`() {
        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeTempFile(relativePath = "app/build.gradle.kts", content = "")
        val cache = ToolingApiResultCache()

        val firstKey = cache.cacheKey(tempDir)

        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":lib")
            """.trimIndent() + "\n",
        )

        val secondKey = cache.cacheKey(tempDir)

        assertTrue(firstKey != secondKey)
    }

    @Test
    fun `cache key changes when build file content changes`() {
        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeTempFile(relativePath = "app/build.gradle.kts", content = "dependencies {}\n")
        val cache = ToolingApiResultCache()

        val firstKey = cache.cacheKey(tempDir)

        writeTempFile(
            relativePath = "app/build.gradle.kts",
            content = """
                dependencies {
                    implementation("com.example:demo:1.0")
                }
            """.trimIndent() + "\n",
        )

        val secondKey = cache.cacheKey(tempDir)

        assertTrue(firstKey != secondKey)
    }

    @Test
    fun `cache round-trips module models correctly`() {
        writeBasicCacheInputs()
        val cache = ToolingApiResultCache()
        val modules = listOf(
            GradleModuleModel(
                gradlePath = ":app",
                ideaModuleName = "app",
                mainSourceRoots = listOf(tempDir.resolve("app/src/main/kotlin").toAbsolutePath().normalize()),
                testSourceRoots = listOf(tempDir.resolve("app/src/test/kotlin").toAbsolutePath().normalize()),
                testFixturesSourceRoots = listOf(tempDir.resolve("app/src/testFixtures/kotlin").toAbsolutePath().normalize()),
                mainOutputRoots = listOf(tempDir.resolve("app/build/classes/kotlin/main").toAbsolutePath().normalize()),
                testOutputRoots = listOf(tempDir.resolve("app/build/classes/kotlin/test").toAbsolutePath().normalize()),
                testFixturesOutputRoots = listOf(tempDir.resolve("app/build/classes/kotlin/testFixtures").toAbsolutePath().normalize()),
                dependencies = listOf(
                    GradleDependency.ModuleDependency(
                        targetIdeaModuleName = ":lib",
                        scope = GradleDependencyScope.COMPILE,
                    ),
                    GradleDependency.LibraryDependency(
                        binaryRoot = tempDir.resolve("repo/runtime.jar").toAbsolutePath().normalize(),
                        scope = GradleDependencyScope.RUNTIME,
                    ),
                ),
            ),
        )

        cache.write(tempDir, modules)

        assertEquals(modules, cache.read(tempDir))
    }

    @Test
    fun `cache read returns null when cache file does not exist`() {
        writeBasicCacheInputs()

        assertNull(ToolingApiResultCache().read(tempDir))
    }

    @Test
    fun `cache read returns null when cache key is stale`() {
        writeBasicCacheInputs()
        val cache = ToolingApiResultCache()
        val modules = listOf(
            GradleModuleModel(
                gradlePath = ":app",
                ideaModuleName = "app",
                mainSourceRoots = listOf(tempDir.resolve("app/src/main/kotlin").toAbsolutePath().normalize()),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = emptyList(),
            ),
        )
        cache.write(tempDir, modules)

        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":lib")
            """.trimIndent() + "\n",
        )

        assertNull(cache.read(tempDir))
    }

    @Test
    fun `cache write and read survive Path serialization round-trip`() {
        writeBasicCacheInputs()
        val cache = ToolingApiResultCache()
        val quirkyJar = tempDir
            .resolve("repo/libs/space name/ümlaut-lib.jar")
            .toAbsolutePath()
            .normalize()
        val modules = listOf(
            GradleModuleModel(
                gradlePath = ":quirky",
                ideaModuleName = "quirky",
                mainSourceRoots = listOf(tempDir.resolve("module with spaces/src/main/kotlin").toAbsolutePath().normalize()),
                testSourceRoots = emptyList(),
                mainOutputRoots = listOf(tempDir.resolve("module with spaces/build/classes/kotlin/main").toAbsolutePath().normalize()),
                testOutputRoots = emptyList(),
                dependencies = listOf(
                    GradleDependency.LibraryDependency(
                        binaryRoot = quirkyJar,
                        scope = GradleDependencyScope.PROVIDED,
                    ),
                ),
            ),
        )

        cache.write(tempDir, modules)

        assertEquals(modules, cache.read(tempDir))
    }

    @Test
    fun `discover uses cached result when available`() {
        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeTempFile(relativePath = "app/build.gradle.kts", content = "")
        tempDir.resolve("app/src/main/kotlin").createDirectories()
        val cache = ToolingApiResultCache()
        val cachedModules = listOf(
            GradleModuleModel(
                gradlePath = ":app",
                ideaModuleName = "app",
                mainSourceRoots = listOf(normalizeStandalonePath(tempDir.resolve("app/src/main/kotlin"))),
                testSourceRoots = emptyList(),
                mainOutputRoots = emptyList(),
                testOutputRoots = emptyList(),
                dependencies = emptyList(),
            ),
        )
        cache.write(tempDir, cachedModules)
        var loaderCalled = false

        val layout = GradleWorkspaceDiscovery.discover(
            workspaceRoot = tempDir,
            extraClasspathRoots = emptyList(),
            toolingApiCache = cache,
            envReader = { null },
            toolingApiLoader = { _, _ ->
                loaderCalled = true
                emptyList()
            },
        )

        assertFalse(loaderCalled)
        assertEquals(listOf(":app[main]"), layout.sourceModules.map(StandaloneSourceModuleSpec::name))
        assertEquals(
            listOf(normalizeStandalonePath(tempDir.resolve("app/src/main/kotlin"))),
            layout.sourceModules.single().sourceRoots,
        )
    }

    @Test
    fun `GradleSettingsSnapshot parses includeBuild paths`() {
        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                includeBuild("build-logic")
                includeBuild("shared-conventions")
            """.trimIndent() + "\n",
        )

        val snapshot = GradleSettingsSnapshot.read(tempDir)

        assertEquals(listOf("build-logic", "shared-conventions"), snapshot.compositeBuilds)
        assertTrue(snapshot.hasCompositeBuilds)
    }

    @Test
    fun `GradleSettingsSnapshot parses mixed include and includeBuild`() {
        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":lib")
                includeBuild("build-logic")
            """.trimIndent() + "\n",
        )

        val snapshot = GradleSettingsSnapshot.read(tempDir)

        assertEquals(listOf(":app", ":lib"), snapshot.includedProjectPaths)
        assertEquals(listOf("build-logic"), snapshot.compositeBuilds)
    }

    @Test
    fun `loadCompositeBuildsInParallel merges results from multiple builds`() {
        tempDir.resolve("build-logic").createDirectories()
        tempDir.resolve("shared-conventions").createDirectories()

        val modules = loadCompositeBuildsInParallel(
            workspaceRoot = tempDir,
            compositeBuilds = listOf("build-logic", "shared-conventions"),
            timeoutMillis = 30_000L,
            toolingApiLoader = { root, _ ->
                listOf(
                    GradleModuleModel(
                        gradlePath = ":${root.fileName}",
                        ideaModuleName = root.fileName.toString(),
                        mainSourceRoots = emptyList(),
                        testSourceRoots = emptyList(),
                        mainOutputRoots = emptyList(),
                        testOutputRoots = emptyList(),
                        dependencies = emptyList(),
                    ),
                )
            },
        )

        assertEquals(setOf(":build-logic", ":shared-conventions"), modules.map(GradleModuleModel::gradlePath).toSet())
    }

    @Test
    fun `loadCompositeBuildsInParallel tolerates individual build failures`() {
        tempDir.resolve("build-logic").createDirectories()
        tempDir.resolve("shared-conventions").createDirectories()
        val warnings = mutableListOf<String>()

        val modules = loadCompositeBuildsInParallel(
            workspaceRoot = tempDir,
            compositeBuilds = listOf("build-logic", "shared-conventions"),
            timeoutMillis = 30_000L,
            toolingApiLoader = { root, _ ->
                if (root.fileName.toString() == "build-logic") {
                    throw TimeoutException("timed out")
                }
                listOf(
                    GradleModuleModel(
                        gradlePath = ":shared-conventions",
                        ideaModuleName = "shared-conventions",
                        mainSourceRoots = emptyList(),
                        testSourceRoots = emptyList(),
                        mainOutputRoots = emptyList(),
                        testOutputRoots = emptyList(),
                        dependencies = emptyList(),
                    ),
                )
            },
            warningSink = warnings::add,
        )

        assertEquals(listOf(":shared-conventions"), modules.map(GradleModuleModel::gradlePath))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("build-logic"))
        assertTrue(warnings.single().contains("timed out"))
    }

    @Test
    fun `static discovery picks up library dependencies from compileKotlin classpath file`() {
        writeStaticSettings(":app")
        writeTempFile(relativePath = "app/build.gradle.kts", content = "")
        val firstJar = createJar(tempDir.resolve("repo/first-lib.jar"))
        val secondJar = createJar(tempDir.resolve("repo/second-lib.jar"))
        writeTempFile(
            relativePath = "app/build/tmp/compileKotlin/classpath",
            content = "${firstJar}\n${secondJar}\n",
        )

        val modulesByPath = StaticGradleWorkspaceDiscovery.discoverModules(tempDir, GradleSettingsSnapshot.read(tempDir))
            .associateBy(GradleModuleModel::gradlePath)

        assertEquals(
            setOf(normalizeStandalonePath(firstJar), normalizeStandalonePath(secondJar)),
            modulesByPath.getValue(":app").dependencies
                .filterIsInstance<GradleDependency.LibraryDependency>()
                .map(GradleDependency.LibraryDependency::binaryRoot)
                .toSet(),
        )
    }

    @Test
    fun `static discovery ignores missing classpath files gracefully`() {
        writeStaticSettings(":app", ":lib")
        writeTempFile(
            relativePath = "app/build.gradle.kts",
            content = """
                dependencies {
                    implementation(project(":lib"))
                }
            """.trimIndent() + "\n",
        )
        writeTempFile(relativePath = "lib/build.gradle.kts", content = "")

        val modulesByPath = StaticGradleWorkspaceDiscovery.discoverModules(tempDir, GradleSettingsSnapshot.read(tempDir))
            .associateBy(GradleModuleModel::gradlePath)

        assertTrue(
            modulesByPath.getValue(":app").dependencies.contains(
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = ":lib",
                    scope = GradleDependencyScope.COMPILE,
                ),
            ),
        )
    }

    @Test
    fun `static discovery merges classpath file deps with regex-parsed deps`() {
        writeStaticSettings(":app", ":lib")
        writeTempFile(
            relativePath = "app/build.gradle.kts",
            content = """
                dependencies {
                    implementation(project(":lib"))
                }
            """.trimIndent() + "\n",
        )
        writeTempFile(relativePath = "lib/build.gradle.kts", content = "")
        val runtimeJar = createJar(tempDir.resolve("repo/runtime.jar"))
        writeTempFile(
            relativePath = "app/build/tmp/compileKotlin/classpath",
            content = "${runtimeJar}\n",
        )

        val appModule = StaticGradleWorkspaceDiscovery.discoverModules(tempDir, GradleSettingsSnapshot.read(tempDir))
            .associateBy(GradleModuleModel::gradlePath)
            .getValue(":app")

        assertTrue(
            appModule.dependencies.contains(
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = ":lib",
                    scope = GradleDependencyScope.COMPILE,
                ),
            ),
        )
        assertTrue(
            appModule.dependencies.contains(
                GradleDependency.LibraryDependency(
                    binaryRoot = normalizeStandalonePath(runtimeJar),
                    scope = GradleDependencyScope.COMPILE,
                ),
            ),
        )
    }

    @Test
    fun `static discovery deduplicates library dependencies from multiple classpath files`() {
        writeStaticSettings(":app")
        writeTempFile(relativePath = "app/build.gradle.kts", content = "")
        val sharedJar = createJar(tempDir.resolve("repo/shared.jar"))
        val kotlinOnlyJar = createJar(tempDir.resolve("repo/kotlin-only.jar"))
        writeTempFile(
            relativePath = "app/build/tmp/compileKotlin/classpath",
            content = "${sharedJar}\n${kotlinOnlyJar}\n",
        )
        writeTempFile(
            relativePath = "app/build/tmp/compileJava/classpath",
            content = "${sharedJar}\n",
        )

        val libraryDependencies = StaticGradleWorkspaceDiscovery.discoverModules(tempDir, GradleSettingsSnapshot.read(tempDir))
            .associateBy(GradleModuleModel::gradlePath)
            .getValue(":app")
            .dependencies
            .filterIsInstance<GradleDependency.LibraryDependency>()

        assertEquals(2, libraryDependencies.size)
        assertEquals(
            setOf(normalizeStandalonePath(sharedJar), normalizeStandalonePath(kotlinOnlyJar)),
            libraryDependencies.map(GradleDependency.LibraryDependency::binaryRoot).toSet(),
        )
    }

    @Test
    fun `detectIncompleteClasspath does not warn for modules with build-output dependencies`() {
        writeStaticSettings(":app")
        writeTempFile(relativePath = "app/build.gradle.kts", content = "")
        tempDir.resolve("app/src/main/kotlin").createDirectories()
        val runtimeJar = createJar(tempDir.resolve("repo/runtime.jar"))
        writeTempFile(
            relativePath = "app/build/tmp/compileKotlin/classpath",
            content = "${runtimeJar}\n",
        )

        val warnings = detectIncompleteClasspath(
            StaticGradleWorkspaceDiscovery.discoverModules(tempDir, GradleSettingsSnapshot.read(tempDir)),
        )

        assertFalse(warnings.any { warning -> warning.contains(":app") })
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

        assertEquals(setOf(":app[main]", ":app[testFixtures]", ":app[test]", ":lib[main]"), modulesByName.keys)
        assertEquals(
            listOf(":lib[main]"),
            modulesByName.getValue(":app[main]").dependencyModuleNames,
        )
        assertEquals(
            listOf(":app[main]", ":lib[main]"),
            modulesByName.getValue(":app[testFixtures]").dependencyModuleNames,
        )
        assertEquals(
            listOf(":app[main]", ":app[testFixtures]", ":lib[main]"),
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
                Path.of("/workspace/generated/build/classes/kotlin/main"),
            ),
            modulesByName.getValue(":app[testFixtures]").binaryRoots,
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

        assertEquals(emptyList<String>(), modulesByName.getValue(":app[main]").dependencyModuleNames)
        assertEquals(emptyList<Path>(), modulesByName.getValue(":app[main]").binaryRoots)
        assertEquals(
            listOf(":app[main]", ":lib[main]"),
            modulesByName.getValue(":app[testFixtures]").dependencyModuleNames,
        )
        assertEquals(
            listOf(Path.of("/deps/fixture-support.jar")),
            modulesByName.getValue(":app[testFixtures]").binaryRoots,
        )
        assertEquals(
            listOf(":app[main]", ":app[testFixtures]", ":lib[main]"),
            modulesByName.getValue(":app[test]").dependencyModuleNames,
        )
        assertEquals(
            listOf(Path.of("/deps/fixture-support.jar")),
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

    private fun writeBasicCacheInputs() {
        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeTempFile(relativePath = "app/build.gradle.kts", content = "")
    }

    private fun writeStaticSettings(vararg projectPaths: String) {
        writeTempFile(
            relativePath = "settings.gradle.kts",
            content = buildString {
                appendLine("rootProject.name = \"workspace\"")
                appendLine(
                    "include(${projectPaths.joinToString(separator = ", ") { projectPath -> "\"$projectPath\"" }})",
                )
            },
        )
    }

    private fun writeTempFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = tempDir.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeText(content)
        return path
    }

    private fun createJar(path: Path): Path {
        path.parent?.createDirectories()
        Files.newOutputStream(path).use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/"))
                jar.closeEntry()
            }
        }
        return path
    }

    // --- Phase 2.1: Test classpath harvesting for test source sets ---

    @Test
    fun `build output classpath discovers test-only dependencies with TEST scope`() {
        writeStaticSettings(":mylib")
        writeTempFile("mylib/build.gradle.kts", "")
        writeTempFile("mylib/src/main/kotlin/Lib.kt", "class Lib")
        writeTempFile("mylib/src/test/kotlin/LibTest.kt", "class LibTest")

        val mainJar = createJar(tempDir.resolve("mylib/build/libs/mylib.jar"))
        val compileJar = createJar(tempDir.resolve("external/compile-dep.jar"))
        val testJar = createJar(tempDir.resolve("external/test-dep.jar"))

        writeTempFile("mylib/build/tmp/compileKotlin/classpath", compileJar.toString())
        writeTempFile("mylib/build/tmp/compileTestKotlin/classpath", "${compileJar}\n${testJar}")

        val result = StaticGradleWorkspaceDiscovery.discoverModules(
            workspaceRoot = tempDir,
            settingsSnapshot = GradleSettingsSnapshot.read(tempDir),
        )

        val module = result.single { it.ideaModuleName.contains("mylib") }
        val libraryDeps = module.dependencies.filterIsInstance<GradleDependency.LibraryDependency>()
        val compileDeps = libraryDeps.filter { it.scope == GradleDependencyScope.COMPILE }
        val testDeps = libraryDeps.filter { it.scope == GradleDependencyScope.TEST }

        assertTrue(compileDeps.any { it.binaryRoot.toString().contains("compile-dep.jar") })
        assertTrue(compileDeps.any { it.binaryRoot.toString().contains("mylib.jar") })
        assertTrue(testDeps.any { it.binaryRoot.toString().contains("test-dep.jar") })
        assertFalse(testDeps.any { it.binaryRoot.toString().contains("compile-dep.jar") })
    }

    // --- Phase 2.3: KAST_PREFER_BUILD_OUTPUT ---

    @Test
    fun `discover with KAST_PREFER_BUILD_OUTPUT skips tooling api`() {
        writeBasicCacheInputs()
        writeTempFile("app/src/main/kotlin/App.kt", "class App")
        val jar = createJar(tempDir.resolve("app/build/libs/app.jar"))
        writeTempFile("app/build/tmp/compileKotlin/classpath", jar.toString())

        var toolingApiCalled = false
        val result = GradleWorkspaceDiscovery.discover(
            workspaceRoot = tempDir,
            extraClasspathRoots = emptyList(),
            envReader = { key ->
                when (key) {
                    "KAST_PREFER_BUILD_OUTPUT" -> "true"
                    else -> null
                }
            },
            toolingApiLoader = { _, _ ->
                toolingApiCalled = true
                emptyList()
            },
        )

        assertFalse(toolingApiCalled, "Tooling API should not be called when KAST_PREFER_BUILD_OUTPUT is set")
        assertTrue(result.sourceModules.isNotEmpty())
    }

    // --- Phase 3: Source index cache ---

    @Test
    fun `source index cache round trip`() {
        val sourceFile = writeTempFile("src/main/kotlin/Foo.kt", "class Foo\nfun bar() {}\n")
        val sourceRoot = tempDir.resolve("src/main/kotlin")

        val index = buildTestSourceIndex(listOf(sourceRoot))
        val cache = SourceIdentifierIndexCache()
        cache.write(tempDir, index, listOf(sourceRoot))

        assertTrue(Files.isRegularFile(cache.cacheFilePath(tempDir)))

        val loaded = cache.load(tempDir, listOf(sourceRoot))
        assertTrue(loaded != null)
        assertTrue(loaded!!.candidatePathsFor("Foo").isNotEmpty())
        assertTrue(loaded.candidatePathsFor("bar").isNotEmpty())
    }

    @Test
    fun `source index cache detects stale files by mtime`() {
        val sourceFile = writeTempFile("src/main/kotlin/Foo.kt", "class Foo\n")
        val sourceRoot = tempDir.resolve("src/main/kotlin")

        val index = buildTestSourceIndex(listOf(sourceRoot))
        val cache = SourceIdentifierIndexCache()
        cache.write(tempDir, index, listOf(sourceRoot))

        Thread.sleep(50)
        sourceFile.writeText("class Foo\nclass Bar\n")

        val loaded = cache.load(tempDir, listOf(sourceRoot))
        assertTrue(loaded != null)
        assertTrue(loaded!!.candidatePathsFor("Bar").isNotEmpty(), "Stale file should be re-indexed and include new identifier")
    }

    @Test
    fun `source index cache detects new files not in previous index`() {
        val sourceRoot = tempDir.resolve("src/main/kotlin")
        writeTempFile("src/main/kotlin/Foo.kt", "class Foo\n")

        val index = buildTestSourceIndex(listOf(sourceRoot))
        val cache = SourceIdentifierIndexCache()
        cache.write(tempDir, index, listOf(sourceRoot))

        writeTempFile("src/main/kotlin/Bar.kt", "class Bar\n")

        val loaded = cache.load(tempDir, listOf(sourceRoot))
        assertTrue(loaded != null)
        assertTrue(loaded!!.candidatePathsFor("Bar").isNotEmpty(), "New file should be discovered and indexed")
    }

    @Test
    fun `source index cache returns null when source roots changed`() {
        val sourceRoot = tempDir.resolve("src/main/kotlin")
        writeTempFile("src/main/kotlin/Foo.kt", "class Foo\n")

        val index = buildTestSourceIndex(listOf(sourceRoot))
        val cache = SourceIdentifierIndexCache()
        cache.write(tempDir, index, listOf(sourceRoot))

        val differentRoot = tempDir.resolve("other/src")
        val loaded = cache.load(tempDir, listOf(differentRoot))
        assertNull(loaded, "Cache should miss when source roots differ")
    }

    private fun buildTestSourceIndex(sourceRoots: List<Path>): MutableSourceIdentifierIndex {
        val pathsByIdentifier = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
        val identifiersByPath = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

        sourceRoots.forEach { sourceRoot ->
            if (!Files.isDirectory(sourceRoot)) return@forEach
            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
                    .forEach { file ->
                        val normalizedFilePath = normalizeStandalonePath(file).toString()
                        val identifiers = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
                            .findAll(Files.readString(file))
                            .map { match -> match.value }
                            .toSet()
                        identifiersByPath[normalizedFilePath] = identifiers
                        identifiers.forEach { identifier ->
                            pathsByIdentifier
                                .computeIfAbsent(identifier) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                                .add(normalizedFilePath)
                        }
                    }
            }
        }

        return MutableSourceIdentifierIndex(
            pathsByIdentifier = pathsByIdentifier,
            identifiersByPath = identifiersByPath,
        )
    }
}
