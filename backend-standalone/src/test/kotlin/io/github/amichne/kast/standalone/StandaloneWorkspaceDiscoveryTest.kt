package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.standalone.workspace.GradleDependency
import io.github.amichne.kast.standalone.workspace.GradleDependencyScope
import io.github.amichne.kast.standalone.workspace.GradleModuleModel
import io.github.amichne.kast.standalone.workspace.GradleSettingsSnapshot
import io.github.amichne.kast.standalone.workspace.GradleWorkspaceDiscovery
import io.github.amichne.kast.standalone.workspace.PhasedDiscoveryResult
import io.github.amichne.kast.standalone.workspace.StaticGradleWorkspaceDiscovery
import io.github.amichne.kast.standalone.workspace.defaultToolingApiTimeoutMillis
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Validates workspace discovery, K2 Analysis API session rebuild, and multi-module configuration semantics.
 * Requires real filesystem: tests validate StandaloneAnalysisSession workspace refresh behavior with real K2 semantics.
 */
class StandaloneWorkspaceDiscoveryTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `gradle workspace discovery derives source sets, classpath roots, and module graph`() {
        createGradleWorkspace(includeLocalTestJar = true)

        val layout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )

        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)
        assertEquals(setOf(ModuleName(":app[main]"), ModuleName(":app[test]"), ModuleName(":lib[main]")), modulesByName.keys)

        assertEquals(listOf(ModuleName(":lib[main]")), modulesByName.getValue(ModuleName(":app[main]")).dependencyModuleNames)
        assertEquals(
            listOf(ModuleName(":app[main]"), ModuleName(":lib[main]")),
            modulesByName.getValue(ModuleName(":app[test]")).dependencyModuleNames,
        )
        assertEquals(emptyList<ModuleName>(), modulesByName.getValue(ModuleName(":lib[main]")).dependencyModuleNames)

        assertFalse(
            modulesByName.getValue(ModuleName(":app[main]")).binaryRoots.any { path -> path.fileName.toString() == "test-support.jar" },
        )
        assertTrue(
            modulesByName.getValue(ModuleName(":app[test]")).binaryRoots.any { path -> path.fileName.toString() == "test-support.jar" },
        )
    }

    @Test
    fun `explicit source roots keep manual session construction even in a gradle workspace`() {
        createGradleWorkspace(includeLocalTestJar = false)

        val layout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(workspaceRoot.resolve("app/src/main/kotlin")),
            classpathRoots = emptyList(),
            moduleName = "manual",
        )

        assertEquals(1, layout.sourceModules.size)
        assertEquals(ModuleName("manual"), layout.sourceModules.single().name)
        assertEquals(
            listOf(normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin"))),
            layout.sourceModules.single().sourceRoots,
        )
    }

    @Test
    fun `static gradle discovery defaults to Kotlin and Java source roots`() {
        createStaticGradleWorkspace(includeJavaSource = true)

        val settingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot)
        val modulesByPath = StaticGradleWorkspaceDiscovery.discoverModules(workspaceRoot, settingsSnapshot)
            .associateBy(GradleModuleModel::gradlePath)

        assertEquals(
            setOf(
                normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin")),
                normalizeStandalonePath(workspaceRoot.resolve("app/src/main/java")),
            ),
            modulesByPath.getValue(":app").mainSourceRoots.toSet(),
        )
    }

    @Test
    fun `static gradle discovery probes testFixtures roots outputs and dependencies`() {
        createStaticGradleWorkspaceWithTestFixtures()

        val settingsSnapshot = GradleSettingsSnapshot.read(workspaceRoot)
        val modulesByPath = StaticGradleWorkspaceDiscovery.discoverModules(workspaceRoot, settingsSnapshot)
            .associateBy(GradleModuleModel::gradlePath)

        assertEquals(
            setOf(
                normalizeStandalonePath(workspaceRoot.resolve("app/src/testFixtures/java")),
                normalizeStandalonePath(workspaceRoot.resolve("app/src/testFixtures/kotlin")),
            ),
            modulesByPath.getValue(":app").testFixturesSourceRoots.toSet(),
        )
        assertEquals(
            listOf(normalizeStandalonePath(workspaceRoot.resolve("app/build/classes/kotlin/testFixtures"))),
            modulesByPath.getValue(":app").testFixturesOutputRoots,
        )
        assertTrue(
            modulesByPath.getValue(":app").dependencies.contains(
                GradleDependency.ModuleDependency(
                    targetIdeaModuleName = ":lib",
                    scope = GradleDependencyScope.TEST_FIXTURES,
                ),
            ),
        )
    }

    @Test
    fun `static discovery finds gatling source roots`() {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeFile(relativePath = "app/build.gradle.kts", content = "")
        writeFile(
            relativePath = "app/src/gatling/kotlin/sample/Simulation.kt",
            content = """
                package sample

                class Simulation
            """.trimIndent() + "\n",
        )

        val modulesByPath = StaticGradleWorkspaceDiscovery.discoverModules(
            workspaceRoot,
            GradleSettingsSnapshot.read(workspaceRoot),
        ).associateBy(GradleModuleModel::gradlePath)

        assertTrue(
            modulesByPath.getValue(":app").mainSourceRoots.contains(
                normalizeStandalonePath(workspaceRoot.resolve("app/src/gatling/kotlin")),
            ),
        )
    }

    @Test
    fun `static discovery finds custom source sets`() {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeFile(relativePath = "app/build.gradle.kts", content = "")
        writeFile(
            relativePath = "app/src/integrationTest/kotlin/sample/IntegrationTest.kt",
            content = """
                package sample

                class IntegrationTest
            """.trimIndent() + "\n",
        )

        val modulesByPath = StaticGradleWorkspaceDiscovery.discoverModules(
            workspaceRoot,
            GradleSettingsSnapshot.read(workspaceRoot),
        ).associateBy(GradleModuleModel::gradlePath)

        assertTrue(
            modulesByPath.getValue(":app").testSourceRoots.contains(
                normalizeStandalonePath(workspaceRoot.resolve("app/src/integrationTest/kotlin")),
            ),
        )
    }

    @Test
    fun `gradle workspace discovery discovers testFixtures source module with correct dependencies`() {
        createGradleWorkspaceWithTestFixtures()

        val layout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )

        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)

        assertEquals(setOf(ModuleName(":lib[main]"), ModuleName(":lib[testFixtures]"), ModuleName(":lib[test]")), modulesByName.keys)
        assertEquals(
            listOf(normalizeStandalonePath(workspaceRoot.resolve("lib/src/testFixtures/kotlin"))),
            modulesByName.getValue(ModuleName(":lib[testFixtures]")).sourceRoots,
        )
        assertEquals(
            listOf(ModuleName(":lib[main]")),
            modulesByName.getValue(ModuleName(":lib[testFixtures]")).dependencyModuleNames,
        )
        assertEquals(
            listOf(ModuleName(":lib[main]"), ModuleName(":lib[testFixtures]")),
            modulesByName.getValue(ModuleName(":lib[test]")).dependencyModuleNames,
        )
    }

    @Test
    fun `composite gradle workspace respects configured source roots`() {
        createCompositeGradleWorkspace(includeJavaSource = true)

        val layout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )

        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)
        assertEquals(setOf(ModuleName(":app[main]"), ModuleName(":app[test]"), ModuleName(":lib[main]")), modulesByName.keys)

        assertEquals(listOf(ModuleName(":lib[main]")), modulesByName.getValue(ModuleName(":app[main]")).dependencyModuleNames)
        assertEquals(
            listOf(ModuleName(":app[main]"), ModuleName(":lib[main]")),
            modulesByName.getValue(ModuleName(":app[test]")).dependencyModuleNames,
        )
        assertTrue(
            modulesByName.getValue(ModuleName(":app[test]")).binaryRoots.any { path -> path.fileName.toString() == "test-support.jar" },
        )
        assertFalse(
            modulesByName.getValue(ModuleName(":app[main]")).sourceRoots.any { path -> path == normalizeStandalonePath(workspaceRoot.resolve("app/src/main/java")) },
        )
        assertTrue(
            modulesByName.getValue(ModuleName(":app[main]")).sourceRoots.any { path -> path == normalizeStandalonePath(workspaceRoot.resolve("app/src/customMain/java")) },
        )
    }

    @Test
    fun `standalone session includes configured Java roots in composite gradle workspaces`(): TestResult = runTest {
        createCompositeGradleWorkspace(includeJavaSource = true)

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            assertTrue(
                session.resolvedSourceRoots.contains(normalizeStandalonePath(workspaceRoot.resolve("app/src/customMain/java"))),
            )
            assertTrue(session.sourceModules.any { module -> module.name == ":app[main]" })
            assertTrue(session.sourceModules.any { module -> module.name == ":lib[main]" })
        }
    }

    @Test
    fun `standalone session defers Kotlin file indexing until first file lookup`() {
        createGradleWorkspace(includeLocalTestJar = false)
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            assertFalse(session.isFullKtFileMapLoaded())

            session.findKtFile(workspaceRoot.resolve("app/src/main/kotlin/sample/Use.kt").toString())

            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `standalone session eagerly builds lexical candidate index without initializing full Kotlin file map`() {
        createGradleWorkspace(includeLocalTestJar = false)
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            assertFalse(session.isFullKtFileMapLoaded())

            session.awaitInitialSourceIndex()

            assertEquals(
                setOf(
                    normalizePath(workspaceRoot.resolve("app/src/main/kotlin/sample/Use.kt")),
                    normalizePath(workspaceRoot.resolve("lib/src/main/kotlin/sample/Greeter.kt")),
                ),
                session.candidateKotlinFilePaths("greet").toSet(),
            )
            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `candidate lookup falls back to targeted scan while eager index is still building`() {
        createGradleWorkspace(includeLocalTestJar = false)
        val unblockIndexBuild = CountDownLatch(1)
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
            initialSourceIndexBuilder = {
                unblockIndexBuild.await()
                emptyMap()
            },
        )
        session.use { session ->
            assertFalse(session.isInitialSourceIndexReady())

            assertEquals(
                setOf(
                    normalizePath(workspaceRoot.resolve("app/src/main/kotlin/sample/Use.kt")),
                    normalizePath(workspaceRoot.resolve("lib/src/main/kotlin/sample/Greeter.kt")),
                ),
                session.candidateKotlinFilePaths("greet").toSet(),
            )
            assertFalse(session.isFullKtFileMapLoaded())
            assertFalse(session.isInitialSourceIndexReady())
            unblockIndexBuild.countDown()
            session.awaitInitialSourceIndex()
        }
    }

    @Test
    fun `content-only refresh does not rebuild K2 session`() {
        val appFile = writeFile(
            relativePath = "src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun greet(): String = "ready"
            """.trimIndent() + "\n",
        )
        val sourceRoot = workspaceRoot.resolve("src/main/kotlin")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(sourceRoot),
            classpathRoots = emptyList(),
            moduleName = "main",
        )

        session.use { standaloneSession ->
            val initialAnalysisStateGeneration = standaloneSession.currentAnalysisStateGeneration()

            assertTrue(standaloneSession.findKtFile(appFile.toString()).text.contains("greet"))

            appFile.writeText(
                """
                    package sample

                    fun welcome(): String = "updated"
                """.trimIndent() + "\n",
            )

            standaloneSession.refreshFileContents(setOf(appFile.toString()))

            assertEquals(initialAnalysisStateGeneration, standaloneSession.currentAnalysisStateGeneration())
            assertTrue(standaloneSession.findKtFile(appFile.toString()).text.contains("welcome"))
            assertFalse(standaloneSession.findKtFile(appFile.toString()).text.contains("greet"))
        }
    }

    @Test
    fun `content-only refresh updates source identifier index`() {
        val appFile = writeFile(
            relativePath = "src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun greet(): String = "ready"
            """.trimIndent() + "\n",
        )
        val sourceRoot = workspaceRoot.resolve("src/main/kotlin")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(sourceRoot),
            classpathRoots = emptyList(),
            moduleName = "main",
        )

        session.use { standaloneSession ->
            standaloneSession.awaitInitialSourceIndex()
            assertTrue(standaloneSession.candidateKotlinFilePaths("welcome").isEmpty())

            appFile.writeText(
                """
                    package sample

                    fun welcome(): String = "updated"
                """.trimIndent() + "\n",
            )

            standaloneSession.refreshFileContents(setOf(appFile.toString()))

            assertEquals(
                setOf(normalizePath(appFile)),
                standaloneSession.candidateKotlinFilePaths("welcome").toSet(),
            )
            assertTrue(standaloneSession.candidateKotlinFilePaths("greet").isEmpty())
        }
    }

    @Test
    fun `content-only refresh keeps shared KtFile instance for partially loaded maps`() {
        val changedFile = writeFile(
            relativePath = "src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun greet(): String = "ready"
            """.trimIndent() + "\n",
        )
        val sourceRoot = workspaceRoot.resolve("src/main/kotlin")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(sourceRoot),
            classpathRoots = emptyList(),
            moduleName = "main",
        )

        session.use { standaloneSession ->
            val normalizedPath = NormalizedPath.of(changedFile)
            standaloneSession.findKtFile(changedFile.toString())
            assertFalse(standaloneSession.isFullKtFileMapLoaded())

            changedFile.writeText(
                """
                    package sample

                    fun welcome(): String = "updated"
                """.trimIndent() + "\n",
            )

            standaloneSession.refreshFileContents(setOf(changedFile.toString()))

            val refreshedKtFilesByPath = ktFileCache(standaloneSession, "ktFilesByPath")
            val refreshedTargetedKtFilesByPath = ktFileCache(standaloneSession, "targetedKtFilesByPath")
            assertSame(
                refreshedKtFilesByPath.getValue(normalizedPath),
                refreshedTargetedKtFilesByPath.getValue(normalizedPath),
            )
            assertTrue(refreshedKtFilesByPath.getValue(normalizedPath).text.contains("welcome"))
        }
    }

    @Test
    fun `content-only refresh preserves full KtFile map`() {
        val changedFile = writeFile(
            relativePath = "src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun app(): String = "ready"
            """.trimIndent() + "\n",
        )
        val unchangedFile = writeFile(
            relativePath = "src/main/kotlin/sample/Other.kt",
            content = """
                package sample

                fun other(): String = "stable"
            """.trimIndent() + "\n",
        )
        val sourceRoot = workspaceRoot.resolve("src/main/kotlin")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(sourceRoot),
            classpathRoots = emptyList(),
            moduleName = "main",
        )

        session.use { standaloneSession ->
            val normalizedChangedPath = NormalizedPath.of(changedFile)
            standaloneSession.allKtFiles()
            assertTrue(standaloneSession.isFullKtFileMapLoaded())
            val unchangedKtFile = standaloneSession.findKtFile(unchangedFile.toString())

            changedFile.writeText(
                """
                    package sample

                    fun app(): String = other()
                """.trimIndent() + "\n",
            )

            standaloneSession.refreshFileContents(setOf(changedFile.toString()))

            assertTrue(standaloneSession.isFullKtFileMapLoaded())
            assertEquals(
                setOf(normalizePath(changedFile), normalizePath(unchangedFile)),
                standaloneSession.allKtFiles().map { file -> file.virtualFilePath }.toSet(),
            )
            assertSame(unchangedKtFile, standaloneSession.findKtFile(unchangedFile.toString()))
            val refreshedKtFilesByPath = ktFileCache(standaloneSession, "ktFilesByPath")
            val refreshedTargetedKtFilesByPath = ktFileCache(standaloneSession, "targetedKtFilesByPath")
            assertSame(
                refreshedKtFilesByPath.getValue(normalizedChangedPath),
                refreshedTargetedKtFilesByPath.getValue(normalizedChangedPath),
            )
            assertTrue(standaloneSession.findKtFile(changedFile.toString()).text.contains("other()"))
        }
    }

    @Test
    fun `structural refresh rebuilds K2 session`() {
        val appFile = writeFile(
            relativePath = "src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun greet(): String = "ready"
            """.trimIndent() + "\n",
        )
        val sourceRoot = workspaceRoot.resolve("src/main/kotlin")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(sourceRoot),
            classpathRoots = emptyList(),
            moduleName = "main",
        )

        session.use { standaloneSession ->
            val initialAnalysisStateGeneration = standaloneSession.currentAnalysisStateGeneration()

            Files.delete(appFile)
            standaloneSession.refreshFiles(setOf(appFile.toString()))

            assertTrue(standaloneSession.currentAnalysisStateGeneration() > initialAnalysisStateGeneration)
        }
    }

    @Test
    fun `phased session serves requests before enrichment completes`() {
        val appFile = writeFile(
            relativePath = "app/src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun app(): String = "ready"
            """.trimIndent() + "\n",
        )
        val libFile = writeFile(
            relativePath = "lib/src/main/kotlin/sample/Lib.kt",
            content = """
                package sample

                fun lib(): String = "later"
            """.trimIndent() + "\n",
        )
        val appRoot = normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin"))
        val libRoot = normalizeStandalonePath(workspaceRoot.resolve("lib/src/main/kotlin"))
        val enrichmentFuture = CompletableFuture<StandaloneWorkspaceLayout>()
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
            phasedDiscoveryResult = PhasedDiscoveryResult(
                initialLayout = manualWorkspaceLayout(
                    sourceModule(name = ":app[main]", sourceRoots = listOf(appRoot)),
                ),
                enrichmentFuture = enrichmentFuture,
            ),
        )

        session.use { phasedSession ->
            assertEquals(normalizePath(appFile), phasedSession.findKtFile(appFile.toString()).virtualFilePath)
            assertFalse(phasedSession.isEnrichmentComplete())

            enrichmentFuture.complete(
                manualWorkspaceLayout(
                    sourceModule(
                        name = ":app[main]",
                        sourceRoots = listOf(appRoot),
                        dependencyModuleNames = listOf(":lib[main]"),
                    ),
                    sourceModule(name = ":lib[main]", sourceRoots = listOf(libRoot)),
                ),
            )

            phasedSession.awaitEnrichment()

            assertTrue(phasedSession.isEnrichmentComplete())
            assertEquals(normalizePath(libFile), phasedSession.findKtFile(libFile.toString()).virtualFilePath)
        }
    }

    @Test
    fun `phased session rebuilds K2 session after enrichment`() {
        writeFile(
            relativePath = "app/src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun app(): String = lib()
            """.trimIndent() + "\n",
        )
        val libFile = writeFile(
            relativePath = "lib/src/main/kotlin/sample/Lib.kt",
            content = """
                package sample

                fun lib(): String = "later"
            """.trimIndent() + "\n",
        )
        val appRoot = normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin"))
        val libRoot = normalizeStandalonePath(workspaceRoot.resolve("lib/src/main/kotlin"))
        val enrichmentFuture = CompletableFuture<StandaloneWorkspaceLayout>()
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
            phasedDiscoveryResult = PhasedDiscoveryResult(
                initialLayout = manualWorkspaceLayout(
                    sourceModule(name = ":app[main]", sourceRoots = listOf(appRoot)),
                ),
                enrichmentFuture = enrichmentFuture,
            ),
        )

        session.use { phasedSession ->
            enrichmentFuture.complete(
                manualWorkspaceLayout(
                    sourceModule(
                        name = ":app[main]",
                        sourceRoots = listOf(appRoot),
                        dependencyModuleNames = listOf(":lib[main]"),
                    ),
                    sourceModule(name = ":lib[main]", sourceRoots = listOf(libRoot)),
                ),
            )

            phasedSession.awaitEnrichment()
            phasedSession.awaitInitialSourceIndex()

            assertTrue(phasedSession.sourceModules.any { module -> module.name == ":lib[main]" })
            assertEquals(normalizePath(libFile), phasedSession.findKtFile(libFile.toString()).virtualFilePath)
        }
    }

    @Test
    fun `phased session source index works during enrichment`() {
        val appFile = writeFile(
            relativePath = "app/src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun greetDuringIndexing(): String = "ready"
            """.trimIndent() + "\n",
        )
        val appRoot = normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin"))
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
            phasedDiscoveryResult = PhasedDiscoveryResult(
                initialLayout = manualWorkspaceLayout(
                    sourceModule(name = ":app[main]", sourceRoots = listOf(appRoot)),
                ),
                enrichmentFuture = CompletableFuture<StandaloneWorkspaceLayout>(),
            ),
        )

        session.use { phasedSession ->
            phasedSession.awaitInitialSourceIndex()

            assertEquals(
                setOf(normalizePath(appFile)),
                phasedSession.candidateKotlinFilePaths("greetDuringIndexing").toSet(),
            )
            assertFalse(phasedSession.isEnrichmentComplete())
        }
    }

    @Test
    fun `friendModuleNames returns all source sets sharing the same Gradle project prefix`() {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build.gradle.kts",
            content = buildString {
                appendLine("""plugins { idea }""")
                appendLine("""subprojects {""")
                appendLine("""    apply(plugin = "java-library")""")
                appendLine("""    repositories { mavenCentral() }""")
                appendLine("""    configure<org.gradle.api.tasks.SourceSetContainer> {""")
                appendLine("""        named("main") { java.srcDir("src/main/kotlin") }""")
                appendLine("""        named("test") { java.srcDir("src/test/kotlin") }""")
                appendLine("""    }""")
                appendLine("""}""")
            },
        )
        writeFile(relativePath = "app/build.gradle.kts", content = "")
        writeFile(
            relativePath = "app/src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun app(): String = "ready"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/test/kotlin/sample/AppTest.kt",
            content = """
                package sample

                class AppTest
            """.trimIndent() + "\n",
        )

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            val friendNames = session.friendModuleNames(ModuleName(":app[main]"))

            assertEquals(setOf(ModuleName(":app[main]"), ModuleName(":app[test]")), friendNames)
        }
    }

    @Test
    fun `friendModuleNames returns singleton for non-Gradle module names`() {
        writeFile(
            relativePath = "src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun app(): String = "ready"
            """.trimIndent() + "\n",
        )

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = listOf(workspaceRoot.resolve("src/main/kotlin")),
            classpathRoots = emptyList(),
            moduleName = "manual",
        )
        session.use { session ->
            val friendNames = session.friendModuleNames(ModuleName("manual"))

            assertEquals(setOf(ModuleName("manual")), friendNames)
        }
    }

    @Test
    fun `friendModuleNames includes testFixtures source set`() {
        createGradleWorkspaceWithTestFixtures()

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            val friendNames = session.friendModuleNames(ModuleName(":lib[main]"))

            assertEquals(setOf(ModuleName(":lib[main]"), ModuleName(":lib[testFixtures]"), ModuleName(":lib[test]")), friendNames)
        }
    }

    @Test
    fun `candidate lookup scopes search to declaring module and dependents`() {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":lib", ":unrelated")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build.gradle.kts",
            content = buildString {
                appendLine("""plugins { idea }""")
                appendLine("""subprojects {""")
                appendLine("""    apply(plugin = "java-library")""")
                appendLine("""    repositories { mavenCentral() }""")
                appendLine("""    configure<org.gradle.api.tasks.SourceSetContainer> {""")
                appendLine("""        named("main") { java.srcDir("src/main/kotlin") }""")
                appendLine("""    }""")
                appendLine("""}""")
            },
        )
        writeFile(
            relativePath = "app/build.gradle.kts",
            content = """
                dependencies {
                    implementation(project(":lib"))
                }
            """.trimIndent() + "\n",
        )
        writeFile(relativePath = "lib/build.gradle.kts", content = "")
        writeFile(relativePath = "unrelated/build.gradle.kts", content = "")
        val declarationFile = writeFile(
            relativePath = "lib/src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "unrelated/src/main/kotlin/sample/Unrelated.kt",
            content = """
                package sample

                fun unrelated(): String = greet("not-a-real-reference")
            """.trimIndent() + "\n",
        )

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            session.awaitInitialSourceIndex()

            assertEquals(
                setOf(
                    normalizePath(declarationFile),
                    normalizePath(workspaceRoot.resolve("app/src/main/kotlin/sample/Use.kt")),
                ),
                session.candidateKotlinFilePaths(
                    identifier = "greet",
                    anchorFilePath = declarationFile.toString(),
                ).toSet(),
            )
        }
    }

    @Test
    fun `standalone session resolves Kotlin references to Java declarations in configured gradle source roots`(): TestResult = runTest {
        createCompositeGradleWorkspace(includeJavaSource = true)
        val usageFile = workspaceRoot.resolve("app/src/main/kotlin/sample/UseJava.kt")
        val queryOffset = Files.readString(usageFile).indexOf("legacyGreeting")
        val declarationFile = workspaceRoot.resolve("app/src/customMain/java/sample/LegacyHelper.java")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val result = backend.resolveSymbol(
                SymbolQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                ),
            )

            assertEquals("sample.LegacyHelper#legacyGreeting", result.symbol.fqName)
            assertEquals(SymbolKind.FUNCTION, result.symbol.kind)
            assertEquals(normalizePath(declarationFile), result.symbol.location.filePath)
        }
    }

    @Test
    fun `standalone session resolves symbols across discovered gradle modules`(): TestResult = runTest {
        createGradleWorkspace(includeLocalTestJar = false)
        val usageFile = workspaceRoot.resolve("app/src/main/kotlin/sample/Use.kt")
        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val declarationFile = workspaceRoot.resolve("lib/src/main/kotlin/sample/Greeter.kt")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val result = backend.resolveSymbol(
                SymbolQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                ),
            )

            assertEquals(normalizePath(declarationFile), result.symbol.location.filePath)
            assertTrue(session.sourceModules.map { module -> module.name }.contains(":lib[main]"))
            assertTrue(session.sourceModules.map { module -> module.name }.contains(":app[main]"))
        }
    }

    @Test
    fun `tooling api resolves compileOnly libraries from included-build convention plugins`() {
        createCompositeConventionPluginWorkspace()

        // Cold CI can spend noticeably longer compiling the included build's convention plugin
        // before the Tooling API model is ready.
        val modulesByPath = GradleWorkspaceDiscovery.loadModulesWithToolingApi(
            workspaceRoot,
            timeoutMillis = defaultToolingApiTimeoutMillis * 4,
        )
            .associateBy(GradleModuleModel::gradlePath)

        assertTrue(
            modulesByPath.getValue(":detekt-rules").dependencies
                .filterIsInstance<GradleDependency.LibraryDependency>()
                .any { dependency ->
                    dependency.scope == GradleDependencyScope.PROVIDED &&
                    dependency.binaryRoot.fileName.toString() == "detekt-api-1.23.7.jar"
                },
        )
    }

    @Test
    fun `runtime status includes workspace diagnostics when classpath is incomplete`(): TestResult = runTest {
        createGradleWorkspace(includeLocalTestJar = false)

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val status = backend.runtimeStatus()

            assertFalse(status.warnings.isEmpty())
            assertTrue(status.warnings.any { warning -> warning.contains(":lib") })
            assertTrue(checkNotNull(status.message).contains("warnings"))
        }
    }

    @Test
    fun `runtime status reports INDEXING during enrichment`(): TestResult = runTest {
        writeFile(
            relativePath = "app/src/main/kotlin/sample/App.kt",
            content = """
                package sample

                fun app(): String = "ready"
            """.trimIndent() + "\n",
        )
        val appRoot = normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin"))
        val enrichmentFuture = CompletableFuture<StandaloneWorkspaceLayout>()
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
            phasedDiscoveryResult = PhasedDiscoveryResult(
                initialLayout = manualWorkspaceLayout(
                    sourceModule(name = ":app[main]", sourceRoots = listOf(appRoot)),
                ),
                enrichmentFuture = enrichmentFuture,
            ),
        )
        session.use { phasedSession ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = phasedSession,
            )

            assertEquals(RuntimeState.INDEXING, backend.runtimeStatus().state)

            enrichmentFuture.complete(
                manualWorkspaceLayout(
                    sourceModule(name = ":app[main]", sourceRoots = listOf(appRoot)),
                ),
            )
            phasedSession.awaitEnrichment()
            phasedSession.awaitInitialSourceIndex()

            assertEquals(RuntimeState.READY, backend.runtimeStatus().state)
        }
    }

    private fun createGradleWorkspace(includeLocalTestJar: Boolean) {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":lib")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build.gradle.kts",
            content = buildString {
                appendLine("""plugins { idea }""")
                appendLine("""subprojects {""")
                appendLine("""    apply(plugin = "java-library")""")
                appendLine("""    repositories { mavenCentral() }""")
                appendLine("""    configure<org.gradle.api.tasks.SourceSetContainer> {""")
                appendLine("""        named("main") { java.srcDir("src/main/kotlin") }""")
                appendLine("""        named("test") { java.srcDir("src/test/kotlin") }""")
                appendLine("""    }""")
                appendLine("""}""")
            },
        )
        writeFile(
            relativePath = "app/build.gradle.kts",
            content = buildString {
                appendLine("""dependencies {""")
                appendLine("""    implementation(project(":lib"))""")
                if (includeLocalTestJar) {
                    appendLine("""    testImplementation(files(rootProject.layout.projectDirectory.file("support/test-support.jar")))""")
                }
                appendLine("""}""")
            },
        )
        writeFile(
            relativePath = "lib/build.gradle.kts",
            content = "",
        )
        writeFile(
            relativePath = "lib/src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/test/kotlin/sample/UseTest.kt",
            content = """
                package sample

                class UseTest
            """.trimIndent() + "\n",
        )
        if (includeLocalTestJar) {
            createJar(workspaceRoot.resolve("support/test-support.jar"))
        }
    }

    private fun createGradleWorkspaceWithTestFixtures() {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":lib")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build.gradle.kts",
            content = buildString {
                appendLine("""plugins { idea }""")
                appendLine("""subprojects {""")
                appendLine("""    apply(plugin = "java-library")""")
                appendLine("""    repositories { mavenCentral() }""")
                appendLine("""}""")
                appendLine("""project(":lib") {""")
                appendLine("""    apply(plugin = "java-test-fixtures")""")
                appendLine("""    configure<org.gradle.api.tasks.SourceSetContainer> {""")
                appendLine("""        named("main") { java.srcDir("src/main/kotlin") }""")
                appendLine("""        named("test") { java.srcDir("src/test/kotlin") }""")
                appendLine("""        named("testFixtures") { java.srcDir("src/testFixtures/kotlin") }""")
                appendLine("""    }""")
                appendLine("""}""")
            },
        )
        writeFile(relativePath = "lib/build.gradle.kts", content = "")
        writeFile(
            relativePath = "lib/src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "lib/src/testFixtures/kotlin/sample/Fixture.kt",
            content = """
                package sample

                fun fixtureGreeting(): String = greet("fixture")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "lib/src/test/kotlin/sample/GreeterTest.kt",
            content = """
                package sample

                class GreeterTest
            """.trimIndent() + "\n",
        )
    }

    private fun createStaticGradleWorkspace(includeJavaSource: Boolean) {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/build.gradle.kts",
            content = "",
        )
        writeFile(
            relativePath = "app/src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = "kast"
            """.trimIndent() + "\n",
        )
        if (includeJavaSource) {
            writeFile(
                relativePath = "app/src/main/java/sample/LegacyHelper.java",
                content = """
                    package sample;

                    public final class LegacyHelper {
                        private LegacyHelper() {}
                    }
                """.trimIndent() + "\n",
            )
        }
    }

    private fun createStaticGradleWorkspaceWithTestFixtures() {
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
                dependencies {
                    testFixturesImplementation(project(":lib"))
                }
            """.trimIndent() + "\n",
        )
        writeFile(relativePath = "lib/build.gradle.kts", content = "")
        writeFile(
            relativePath = "lib/src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(name: String): String = "kast"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/testFixtures/kotlin/sample/Fixture.kt",
            content = """
                package sample

                fun fixtureGreeting(): String = "fixture"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/testFixtures/java/sample/LegacyFixture.java",
            content = """
                package sample;

                public final class LegacyFixture {
                    private LegacyFixture() {}
                }
            """.trimIndent() + "\n",
        )
        workspaceRoot.resolve("app/build/classes/kotlin/testFixtures").createDirectories()
    }

    private fun createCompositeGradleWorkspace(includeJavaSource: Boolean) {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                includeBuild("build-logic")
                include("app", "lib")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build.gradle.kts",
            content = buildString {
                appendLine("""plugins { idea }""")
                appendLine("""subprojects {""")
                appendLine("""    apply(plugin = "java-library")""")
                appendLine("""    repositories { mavenCentral() }""")
                appendLine("""    configure<org.gradle.api.tasks.SourceSetContainer> {""")
                appendLine("""        named("main") {""")
                appendLine("""            java.srcDir("src/main/kotlin")""")
                appendLine("""            java.srcDir("src/customMain/java")""")
                appendLine("""        }""")
                appendLine("""        named("test") { java.srcDir("src/test/kotlin") }""")
                appendLine("""    }""")
                appendLine("""}""")
            },
        )
        writeFile(
            relativePath = "app/build.gradle.kts",
            content = """
                dependencies {
                    implementation(project(":lib"))
                    testImplementation(files(rootProject.layout.projectDirectory.file("support/test-support.jar")))
                }
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "lib/build.gradle.kts",
            content = "",
        )
        writeFile(
            relativePath = "lib/src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/main/kotlin/sample/UseJava.kt",
            content = """
                package sample

                fun useJava(): String = LegacyHelper.legacyGreeting()
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/test/kotlin/sample/UseTest.kt",
            content = """
                package sample

                class UseTest
            """.trimIndent() + "\n",
        )
        if (includeJavaSource) {
            writeFile(
                relativePath = "app/src/customMain/java/sample/LegacyHelper.java",
                content = """
                    package sample;

                    public final class LegacyHelper {
                        private LegacyHelper() {}

                        public static String legacyGreeting() {
                            return "legacy";
                        }
                    }
                """.trimIndent() + "\n",
            )
        }
        createJar(workspaceRoot.resolve("support/test-support.jar"))
        writeFile(
            relativePath = "build-logic/settings.gradle.kts",
            content = """
                rootProject.name = "build-logic"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build-logic/build.gradle.kts",
            content = "",
        )
    }

    private fun createCompositeConventionPluginWorkspace() {
        publishMavenArtifact(
            repositoryRoot = workspaceRoot.resolve("repo"),
            groupId = "io.gitlab.arturbosch.detekt",
            artifactId = "detekt-api",
            version = "1.23.7",
        )
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                pluginManagement {
                    includeBuild("build-logic")
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositories {
                        maven { url = uri("repo") }
                    }
                }

                rootProject.name = "workspace"
                include(":detekt-rules")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build.gradle.kts",
            content = """
                plugins { idea }
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "gradle/libs.versions.toml",
            content = """
                [versions]
                detekt = "1.23.7"

                [libraries]
                detekt-api = { module = "io.gitlab.arturbosch.detekt:detekt-api", version.ref = "detekt" }
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build-logic/settings.gradle.kts",
            content = """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                rootProject.name = "build-logic"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build-logic/build.gradle.kts",
            content = """
                plugins {
                    `kotlin-dsl`
                }

                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "build-logic/src/main/kotlin/sample.java-library.gradle.kts",
            content = """
                plugins {
                    `java-library`
                }

                repositories {
                    mavenCentral()
                }
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "detekt-rules/build.gradle.kts",
            content = """
                plugins {
                    id("sample.java-library")
                }

                dependencies {
                    compileOnly(libs.detekt.api)
                }
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "detekt-rules/src/main/java/sample/Rule.java",
            content = """
                package sample;

                public final class Rule {}
            """.trimIndent() + "\n",
        )
    }

    private fun createJar(path: Path) {
        path.parent.createDirectories()
        Files.newOutputStream(path).use { output ->
            JarOutputStream(output).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/"))
                jar.closeEntry()
            }
        }
    }

    private fun publishMavenArtifact(
        repositoryRoot: Path,
        groupId: String,
        artifactId: String,
        version: String,
    ) {
        val moduleDirectory = repositoryRoot
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
        createJar(moduleDirectory.resolve("$artifactId-$version.jar"))
        val pomPath = moduleDirectory.resolve("$artifactId-$version.pom")
        pomPath.parent.createDirectories()
        pomPath.writeText(
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>$groupId</groupId>
                  <artifactId>$artifactId</artifactId>
                  <version>$version</version>
                </project>
            """.trimIndent() + "\n",
        )
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeText(content)
        return path
    }

    private fun normalizePath(path: Path): String = NormalizedPath.of(path).value

    @Suppress("UNCHECKED_CAST")
    private fun ktFileCache(
        session: StandaloneAnalysisSession,
        fieldName: String,
    ): Map<NormalizedPath, KtFile> {
        val field = StandaloneAnalysisSession::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(session) as Map<NormalizedPath, KtFile>
    }

    private fun manualWorkspaceLayout(vararg sourceModules: StandaloneSourceModuleSpec): StandaloneWorkspaceLayout =
        StandaloneWorkspaceLayout(sourceModules = sourceModules.toList())

    private fun sourceModule(
        name: String,
        sourceRoots: List<Path>,
        binaryRoots: List<Path> = emptyList(),
        dependencyModuleNames: List<String> = emptyList(),
    ): StandaloneSourceModuleSpec = StandaloneSourceModuleSpec(
        name = ModuleName(name),
        sourceRoots = sourceRoots,
        binaryRoots = binaryRoots,
        dependencyModuleNames = dependencyModuleNames.map(::ModuleName),
    )
}
