package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.api.client.fields.GradleDiscoveryMode
import io.github.amichne.kast.standalone.cache.WorkspaceDiscoveryCache
import io.github.amichne.kast.standalone.workspace.GradleDependency
import io.github.amichne.kast.standalone.workspace.GradleDependencyScope
import io.github.amichne.kast.standalone.workspace.GradleModuleModel
import io.github.amichne.kast.standalone.workspace.GradleSettingsSnapshot
import io.github.amichne.kast.standalone.workspace.GradleWorkspaceDiscovery
import io.github.amichne.kast.standalone.workspace.GradleWorkspaceDiscoveryResult
import io.github.amichne.kast.standalone.workspace.WorkspaceDiscoveryDiagnostics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WorkspaceDiscoveryCacheTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `workspace discovery cache round-trips GradleModuleModel correctly`() {
        createGradleWorkspace()
        val result = workspaceDiscoveryResult()
        val cache = WorkspaceDiscoveryCache()

        cache.write(workspaceRoot, result)

        val loaded = requireNotNull(cache.read(workspaceRoot))
        assertEquals(result, loaded.discoveryResult)
        assertEquals(
            GradleWorkspaceDiscovery.buildStandaloneWorkspaceLayout(
                gradleModules = result.modules,
                extraClasspathRoots = emptyList(),
            ).dependentModuleNamesBySourceModuleName,
            loaded.dependentModuleNamesBySourceModuleName,
        )
    }

    @Test
    fun `workspace discovery cache invalidates when settings file changes`() {
        createGradleWorkspace()
        val cache = WorkspaceDiscoveryCache()
        cache.write(workspaceRoot, workspaceDiscoveryResult())

        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":lib", ":extra")
            """.trimIndent() + "\n",
        )

        assertNull(cache.read(workspaceRoot))
    }

    @Test
    fun `workspace discovery cache invalidates when build file changes`() {
        createGradleWorkspace()
        val cache = WorkspaceDiscoveryCache()
        cache.write(workspaceRoot, workspaceDiscoveryResult())

        writeFile(
            relativePath = "app/build.gradle.kts",
            content = """
                plugins { kotlin("jvm") version "2.2.20" }

                dependencies {
                    implementation(project(":lib"))
                    implementation("org.example:runtime:2.0")
                }
            """.trimIndent() + "\n",
        )

        assertNull(cache.read(workspaceRoot))
    }

    @Test
    fun `workspace discovery cache separates constrained and complete discovery modes`() {
        createGradleWorkspace()
        val constrainedResult = workspaceDiscoveryResult(":app")
        val completeResult = workspaceDiscoveryResult(":complete-app")
        val cache = WorkspaceDiscoveryCache()

        cache.write(workspaceRoot, constrainedResult, discoveryMode = GradleDiscoveryMode.CONSTRAINED)
        cache.write(workspaceRoot, completeResult, discoveryMode = GradleDiscoveryMode.COMPLETE)

        assertEquals(
            constrainedResult,
            cache.read(workspaceRoot, discoveryMode = GradleDiscoveryMode.CONSTRAINED)?.discoveryResult,
        )
        assertEquals(
            completeResult,
            cache.read(workspaceRoot, discoveryMode = GradleDiscoveryMode.COMPLETE)?.discoveryResult,
        )
    }

    @Test
    fun `workspace discovery cache ignores build files under skipped directories`() {
        createGradleWorkspace()
        val result = workspaceDiscoveryResult()
        val cache = WorkspaceDiscoveryCache()
        cache.write(workspaceRoot, result)

        listOf(
            ".gradle/caches/settings.gradle.kts",
            ".gradle/kast/cache/build.gradle.kts",
            ".git/hooks/build.gradle",
            ".idea/modules/settings.gradle",
            "app/build/generated/build.gradle.kts",
            "node_modules/example/build.gradle.kts",
            "out/generated/settings.gradle.kts",
        ).forEach { relativePath ->
            writeFile(relativePath = relativePath, content = "// ignored\n")
        }

        val loaded = requireNotNull(cache.read(workspaceRoot))
        assertEquals(result, loaded.discoveryResult)
    }

    @Test
    fun `workspace discovery cache is used when valid`() {
        createGradleWorkspace()
        WorkspaceDiscoveryCache().write(workspaceRoot, workspaceDiscoveryResult())
        var gradleLoaderCalls = 0

        val layout = GradleWorkspaceDiscovery.discover(
            workspaceRoot = workspaceRoot,
            extraClasspathRoots = emptyList(),
            settingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot),
            constrainedGradleLoader = { _, _ ->
                gradleLoaderCalls += 1
                error("tooling api should not run when cache is valid")
            },
        )

        assertEquals(0, gradleLoaderCalls)
        assertEquals(
            setOf(ModuleName(":app[main]"), ModuleName(":app[test]"), ModuleName(":lib[main]")),
            layout.sourceModules.map(StandaloneSourceModuleSpec::name).toSet(),
        )
    }

    @Test
    fun `workspace discovery cache avoids Gradle loader on restart`() {
        createGradleWorkspace()
        WorkspaceDiscoveryCache().write(workspaceRoot, workspaceDiscoveryResult())
        var gradleLoaderCalls = 0

        val layout = GradleWorkspaceDiscovery.discover(
            workspaceRoot = workspaceRoot,
            extraClasspathRoots = emptyList(),
            settingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot),
            constrainedGradleLoader = { _, _ ->
                gradleLoaderCalls += 1
                error("tooling api should not run when cache is valid")
            },
        )

        assertEquals(0, gradleLoaderCalls)
        assertEquals(
            setOf(ModuleName(":app[main]"), ModuleName(":app[test]"), ModuleName(":lib[main]")),
            layout.sourceModules.map(StandaloneSourceModuleSpec::name).toSet(),
        )
    }

    private fun createGradleWorkspace() {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":lib")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/build.gradle.kts",
            content = """
                plugins { kotlin("jvm") version "2.2.20" }

                dependencies {
                    implementation(project(":lib"))
                    runtimeOnly(files("libs/runtime.jar"))
                }
            """.trimIndent() + "\n",
        )
        writeFile(relativePath = "lib/build.gradle.kts", content = "")
    }

    private fun workspaceDiscoveryResult(
        appPath: String = ":app",
    ): GradleWorkspaceDiscoveryResult = GradleWorkspaceDiscoveryResult(
        modules = listOf(
            gradleModule(
                gradlePath = appPath,
                mainSourceRoots = listOf(workspaceRoot.resolve(appPath.removePrefix(":").replace(':', '/')).resolve("src/main/kotlin")),
                testSourceRoots = listOf(workspaceRoot.resolve(appPath.removePrefix(":").replace(':', '/')).resolve("src/test/kotlin")),
                mainOutputRoots = listOf(workspaceRoot.resolve(appPath.removePrefix(":").replace(':', '/')).resolve("build/classes/kotlin/main")),
                testOutputRoots = listOf(workspaceRoot.resolve(appPath.removePrefix(":").replace(':', '/')).resolve("build/classes/kotlin/test")),
                dependencies = listOf(
                    GradleDependency.ModuleDependency(
                        targetIdeaModuleName = ":lib",
                        scope = GradleDependencyScope.COMPILE,
                    ),
                    GradleDependency.LibraryDependency(
                        binaryRoot = normalizeStandalonePath(workspaceRoot.resolve("app/libs/runtime.jar")),
                        scope = GradleDependencyScope.RUNTIME,
                    ),
                ),
            ),
            gradleModule(
                gradlePath = ":lib",
                mainSourceRoots = listOf(workspaceRoot.resolve("lib/src/main/kotlin")),
                mainOutputRoots = listOf(workspaceRoot.resolve("lib/build/classes/kotlin/main")),
            ),
        ),
        diagnostics = WorkspaceDiscoveryDiagnostics(
            warnings = listOf("tooling api enrichment warning"),
        ),
    )

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
        mainSourceRoots = mainSourceRoots.map(::normalizeStandalonePath),
        testSourceRoots = testSourceRoots.map(::normalizeStandalonePath),
        testFixturesSourceRoots = testFixturesSourceRoots.map(::normalizeStandalonePath),
        mainOutputRoots = mainOutputRoots.map(::normalizeStandalonePath),
        testOutputRoots = testOutputRoots.map(::normalizeStandalonePath),
        testFixturesOutputRoots = testFixturesOutputRoots.map(::normalizeStandalonePath),
        dependencies = dependencies,
    )

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val file = workspaceRoot.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
        return file
    }
}
