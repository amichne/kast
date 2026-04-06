package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.SymbolQuery
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

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
        assertEquals(setOf(":app[main]", ":app[test]", ":lib[main]"), modulesByName.keys)

        assertEquals(listOf(":lib[main]"), modulesByName.getValue(":app[main]").dependencyModuleNames)
        assertEquals(
            listOf(":app[main]", ":lib[main]"),
            modulesByName.getValue(":app[test]").dependencyModuleNames,
        )
        assertEquals(emptyList<String>(), modulesByName.getValue(":lib[main]").dependencyModuleNames)

        assertFalse(
            modulesByName.getValue(":app[main]").binaryRoots.any { path -> path.fileName.toString() == "test-support.jar" },
        )
        assertTrue(
            modulesByName.getValue(":app[test]").binaryRoots.any { path -> path.fileName.toString() == "test-support.jar" },
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
        assertEquals("manual", layout.sourceModules.single().name)
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
    fun `gradle workspace discovery discovers testFixtures source module with correct dependencies`() {
        createGradleWorkspaceWithTestFixtures()

        val layout = discoverStandaloneWorkspaceLayout(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
        )

        val modulesByName = layout.sourceModules.associateBy(StandaloneSourceModuleSpec::name)

        assertEquals(setOf(":lib[main]", ":lib[testFixtures]", ":lib[test]"), modulesByName.keys)
        assertEquals(
            listOf(normalizeStandalonePath(workspaceRoot.resolve("lib/src/testFixtures/kotlin"))),
            modulesByName.getValue(":lib[testFixtures]").sourceRoots,
        )
        assertEquals(
            listOf(":lib[main]"),
            modulesByName.getValue(":lib[testFixtures]").dependencyModuleNames,
        )
        assertEquals(
            listOf(":lib[main]", ":lib[testFixtures]"),
            modulesByName.getValue(":lib[test]").dependencyModuleNames,
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
        assertEquals(setOf(":app[main]", ":app[test]", ":lib[main]"), modulesByName.keys)

        assertEquals(listOf(":lib[main]"), modulesByName.getValue(":app[main]").dependencyModuleNames)
        assertEquals(
            listOf(":app[main]", ":lib[main]"),
            modulesByName.getValue(":app[test]").dependencyModuleNames,
        )
        assertTrue(
            modulesByName.getValue(":app[test]").binaryRoots.any { path -> path.fileName.toString() == "test-support.jar" },
        )
        assertFalse(
            modulesByName.getValue(":app[main]").sourceRoots.any { path -> path == normalizeStandalonePath(workspaceRoot.resolve("app/src/main/java")) },
        )
        assertTrue(
            modulesByName.getValue(":app[main]").sourceRoots.any { path -> path == normalizeStandalonePath(workspaceRoot.resolve("app/src/customMain/java")) },
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

    private fun normalizePath(path: Path): String {
        val absolutePath = path.toAbsolutePath().normalize()
        return runCatching { absolutePath.toRealPath().normalize().toString() }.getOrDefault(absolutePath.toString())
    }
}
