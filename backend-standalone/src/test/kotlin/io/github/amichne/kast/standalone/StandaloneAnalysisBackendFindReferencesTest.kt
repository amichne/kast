package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ModuleName
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.standalone.workspace.PhasedDiscoveryResult
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class StandaloneAnalysisBackendFindReferencesTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `find references returns cross-file usages and declaration metadata`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val firstUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        val secondUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(firstUsageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = firstUsageFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = true,
                ),
            )

            assertEquals("sample.greet", result.declaration?.fqName)
            assertEquals(normalizePath(declarationFile), result.declaration?.location?.filePath)
            assertEquals(
                listOf(normalizePath(secondUsageFile), normalizePath(firstUsageFile)),
                result.references.map { reference -> reference.filePath },
            )
            assertEquals(
                listOf("fun useAgain(): String = greet(\"again\")", "fun use(): String = greet(\"kast\")"),
                result.references.map { reference -> reference.preview },
            )
        }
    }

    @Test
    fun `capabilities advertise find references after implementation`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val capabilities = backend.capabilities()

            assertTrue(ReadCapability.FIND_REFERENCES in capabilities.readCapabilities)
        }
    }

    @Test
    fun `find references uses indexed candidate files without initializing full Kotlin file map`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        repeat(20) { index ->
            writeFile(
                relativePath = "src/main/kotlin/sample/unrelated/Unrelated$index.kt",
                content = """
                    package sample.unrelated

                    fun unrelated$index(): String = "value$index"
                """.trimIndent() + "\n",
            )
        }
        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            assertFalse(session.isFullKtFileMapLoaded())

            backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = true,
                ),
            )

            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `find references keeps cross-module public callers when dependent map is incomplete`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "lib/src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val callerFile = writeFile(
            relativePath = "app/src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("greet")
        val libModuleName = ModuleName(":lib[main]")
        val appModuleName = ModuleName(":app[main]")
        val phasedDiscoveryResult = PhasedDiscoveryResult(
            initialLayout = StandaloneWorkspaceLayout(
                sourceModules = listOf(
                    StandaloneSourceModuleSpec(
                        name = libModuleName,
                        sourceRoots = listOf(normalizeStandalonePath(workspaceRoot.resolve("lib/src/main/kotlin"))),
                        binaryRoots = emptyList(),
                        dependencyModuleNames = emptyList(),
                    ),
                    StandaloneSourceModuleSpec(
                        name = appModuleName,
                        sourceRoots = listOf(normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin"))),
                        binaryRoots = emptyList(),
                        dependencyModuleNames = listOf(libModuleName),
                    ),
                ),
                dependentModuleNamesBySourceModuleName = mapOf(
                    appModuleName to setOf(appModuleName),
                ),
            ),
            enrichmentFuture = null,
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
            phasedDiscoveryResult = phasedDiscoveryResult,
        )
        session.use { session ->
            session.awaitInitialSourceIndex()
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            assertEquals(listOf(normalizePath(callerFile)), result.references.map { reference -> reference.filePath }.distinct())
            assertEquals(SearchScopeKind.DEPENDENT_MODULES, result.searchScope?.scope)
        }
    }

    @Test
    fun `find references for private function returns only same-file references`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                private fun greet(name: String): String = "hi $name"

                fun useGreet(): String = greet("world")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/Other.kt",
            content = """
                package sample

                fun other(): String = "other"
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referenceFiles = result.references.map { it.filePath }.distinct()
            assertEquals(listOf(normalizePath(declarationFile)), referenceFiles)
            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `enriched index filters by import and excludes non-importing files`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/lib/Util.kt",
            content = """
                package lib

                fun doWork(): String = "work"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/consumer/Caller.kt",
            content = """
                package consumer

                import lib.doWork

                fun call(): String = doWork()
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/bystander/Bystander.kt",
            content = """
                package bystander

                fun doWork(): String = "local shadow"
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("doWork")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referenceFiles = result.references.map { it.filePath }.distinct()
            assertTrue(referenceFiles.any { it.contains("Caller.kt") })
            assertFalse(referenceFiles.any { it.contains("Bystander.kt") })
        }
    }

    @Test
    fun `enriched index includes same-package files without explicit import`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Util.kt",
            content = """
                package sample

                fun helper(): String = "help"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/Caller.kt",
            content = """
                package sample

                fun call(): String = helper()
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/other/Other.kt",
            content = """
                package other

                fun other(): String = "other"
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("helper")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referenceFiles = result.references.map { it.filePath }.distinct()
            assertTrue(referenceFiles.any { it.contains("Caller.kt") })
            assertFalse(referenceFiles.any { it.contains("Other.kt") })
        }
    }

    @Test
    fun `enriched index includes wildcard import files`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/lib/Util.kt",
            content = """
                package lib

                fun compute(): Int = 42
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/consumer/WildcardCaller.kt",
            content = """
                package consumer

                import lib.*

                fun use(): Int = compute()
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/bystander/NoCaller.kt",
            content = """
                package bystander

                fun compute(): Int = 0
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("compute")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referenceFiles = result.references.map { it.filePath }.distinct()
            assertTrue(referenceFiles.any { it.contains("WildcardCaller.kt") })
            assertFalse(referenceFiles.any { it.contains("NoCaller.kt") })
        }
    }

    @Test
    fun `operator function references include explicit and operator-syntax call sites`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Vector.kt",
            content = """
                package sample

                data class Vector(val x: Int, val y: Int) {
                    operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)
                }
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/ExplicitUsage.kt",
            content = """
                package sample

                fun addExplicit(a: Vector, b: Vector): Vector = a.plus(b)
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/OperatorUsage.kt",
            content = """
                package sample

                fun addOperator(a: Vector, b: Vector): Vector = a + b
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("plus")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referenceFiles = result.references.map { it.filePath }.distinct()
            assertTrue(referenceFiles.any { it.contains("ExplicitUsage.kt") })
            assertTrue(referenceFiles.any { it.contains("OperatorUsage.kt") })
        }
    }

    @Test
    fun `references result includes searchScope for private function`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Scoped.kt",
            content = $$"""
                package sample

                private fun secret(name: String): String = "secret $name"

                fun useSecret(): String = secret("world")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("secret")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val scope = result.searchScope
            assertNotNull(scope)
            assertEquals(SymbolVisibility.PRIVATE, scope!!.visibility)
            assertEquals(SearchScopeKind.FILE, scope.scope)
            assertTrue(scope.exhaustive)
        }
    }

    @Test
    fun `references result includes searchScope for public function`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Public.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val scope = result.searchScope
            assertNotNull(scope)
            assertEquals(SymbolVisibility.PUBLIC, scope!!.visibility)
            assertEquals(SearchScopeKind.DEPENDENT_MODULES, scope.scope)
            assertTrue(scope.exhaustive)
            assertTrue(scope.candidateFileCount > 0)
            assertEquals(scope.candidateFileCount, scope.searchedFileCount)
        }
    }

    @Test
    fun `internal function references include test source set via friend modules`(): TestResult = runTest {
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
                appendLine("""    configure<org.gradle.api.tasks.SourceSetContainer> {""")
                appendLine("""        named("main") { java.srcDir("src/main/kotlin") }""")
                appendLine("""        named("test") { java.srcDir("src/test/kotlin") }""")
                appendLine("""    }""")
                appendLine("""}""")
            },
        )
        writeFile(relativePath = "lib/build.gradle.kts", content = "")
        val declarationFile = writeFile(
            relativePath = "lib/src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                internal fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "lib/src/main/kotlin/sample/MainUse.kt",
            content = """
                package sample

                fun mainUse(): String = greet("main")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "lib/src/test/kotlin/sample/GreeterTest.kt",
            content = """
                package sample

                fun testUse(): String = greet("test")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("greet")
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referenceFiles = result.references.map { it.filePath }.distinct()
            assertTrue(referenceFiles.any { it.contains("MainUse.kt") }, "expected main source set reference")
            assertTrue(referenceFiles.any { it.contains("GreeterTest.kt") }, "expected test source set reference (friend module)")
            assertNotNull(result.searchScope)
            assertEquals(SymbolVisibility.INTERNAL, result.searchScope!!.visibility)
            assertEquals(SearchScopeKind.MODULE, result.searchScope!!.scope)
            assertTrue(result.searchScope!!.exhaustive)
        }
    }

    @Test
    fun `references result includes searchScope MODULE for internal function`(): TestResult = runTest {
        writeFile(
            relativePath = "settings.gradle.kts",
            content = """
                rootProject.name = "workspace"
                include(":app", ":unrelated")
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
        writeFile(relativePath = "app/build.gradle.kts", content = "")
        writeFile(relativePath = "unrelated/build.gradle.kts", content = "")
        val declarationFile = writeFile(
            relativePath = "app/src/main/kotlin/sample/Internal.kt",
            content = """
                package sample

                internal fun internalHelper(): String = "internal"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "app/src/main/kotlin/sample/AppUse.kt",
            content = """
                package sample

                fun appUse(): String = internalHelper()
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "unrelated/src/main/kotlin/sample/Unrelated.kt",
            content = """
                package sample

                fun unrelated(): String = "unrelated"
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("internalHelper")
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

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referenceFiles = result.references.map { it.filePath }.distinct()
            assertTrue(referenceFiles.any { it.contains("AppUse.kt") })
            assertFalse(referenceFiles.any { it.contains("Unrelated.kt") })
            assertNotNull(result.searchScope)
            assertEquals(SymbolVisibility.INTERNAL, result.searchScope!!.visibility)
            assertEquals(SearchScopeKind.MODULE, result.searchScope!!.scope)
            assertTrue(result.searchScope!!.exhaustive)
        }
    }

    @Test
    fun `find references for companion object member via class import across modules`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "lib/src/main/kotlin/sample/IdFactory.kt",
            content = """
                package sample

                class IdFactory {
                    companion object {
                        fun create(value: String): String = "id:${'$'}value"
                    }
                }
            """.trimIndent() + "\n",
        )
        val callerFile = writeFile(
            relativePath = "app/src/main/kotlin/consumer/Caller.kt",
            content = """
                package consumer

                import sample.IdFactory

                fun makerId(): String = IdFactory.create("test")
            """.trimIndent() + "\n",
        )
        val queryOffset = Files.readString(declarationFile).indexOf("create")
        val libModuleName = ModuleName(":lib[main]")
        val appModuleName = ModuleName(":app[main]")
        val phasedDiscoveryResult = PhasedDiscoveryResult(
            initialLayout = StandaloneWorkspaceLayout(
                sourceModules = listOf(
                    StandaloneSourceModuleSpec(
                        name = libModuleName,
                        sourceRoots = listOf(normalizeStandalonePath(workspaceRoot.resolve("lib/src/main/kotlin"))),
                        binaryRoots = emptyList(),
                        dependencyModuleNames = emptyList(),
                    ),
                    StandaloneSourceModuleSpec(
                        name = appModuleName,
                        sourceRoots = listOf(normalizeStandalonePath(workspaceRoot.resolve("app/src/main/kotlin"))),
                        binaryRoots = emptyList(),
                        dependencyModuleNames = listOf(libModuleName),
                    ),
                ),
                dependentModuleNamesBySourceModuleName = mapOf(
                    appModuleName to setOf(appModuleName),
                ),
            ),
            enrichmentFuture = null,
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "ignored",
            phasedDiscoveryResult = phasedDiscoveryResult,
        )
        session.use { session ->
            session.awaitInitialSourceIndex()
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
            )

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referenceFiles = result.references.map { it.filePath }.distinct()
            assertTrue(
                referenceFiles.any { it.contains("Caller.kt") },
                "expected cross-module caller with class import to be found, got: $referenceFiles",
            )
        }
    }

    // ── Symbol reference cache-hit / fallback tests ───────────────────

    @Test
    fun `findReferences uses symbol_references cache when phase 2 is complete`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/Unrelated.kt",
            content = """
                package sample

                fun unrelated(): Int = 42
            """.trimIndent() + "\n",
        )

        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )
        session.use { s ->
            s.awaitInitialSourceIndex()

            // Insert a symbol_reference mapping Use.kt -> sample.greet
            s.sqliteStore.ensureSchema()
            s.sqliteStore.upsertSymbolReference(
                sourcePath = normalizePath(usageFile),
                sourceOffset = queryOffset,
                targetFqName = "sample.greet",
                targetPath = normalizePath(declarationFile),
                targetOffset = Files.readString(declarationFile).indexOf("greet"),
            )

            // Complete the referenceIndexReady future via reflection
            val indexerField = StandaloneAnalysisSession::class.java.getDeclaredField("backgroundIndexer")
            indexerField.isAccessible = true
            val indexer = indexerField.get(s) as BackgroundIndexer
            indexer.referenceIndexReady.complete(Unit)
            assertTrue(s.isReferenceIndexReady())

            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = s,
            )

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            val referencePaths = result.references.map { it.filePath }
            assertTrue(
                referencePaths.any { it.contains("Use.kt") },
                "Expected Use.kt in results, got: $referencePaths",
            )
            assertTrue(
                referencePaths.none { it.contains("Unrelated.kt") },
                "Unrelated.kt should not appear in cache-based results, got: $referencePaths",
            )
        }
    }

    @Test
    fun `findReferences falls back to CandidateFileResolver when phase 2 is not ready`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )

        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )
        session.use { s ->
            s.awaitInitialSourceIndex()

            // Phase 2 is disabled; the backgroundIndexer's referenceIndexReady
            // is not completed, which is exactly the state we want to test.
            assertFalse(s.isReferenceIndexReady(), "Phase 2 disabled; indexer should not be ready")

            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = s,
            )

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            assertTrue(
                result.references.any { it.filePath.contains("Use.kt") },
                "Fallback path should still find references in Use.kt, got: ${result.references.map { it.filePath }}",
            )
            assertFalse(s.isReferenceIndexReady())
        }
    }

    @Test
    fun `findReferences falls back when symbol_references has no rows for the target`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                fun greet(name: String): String = "hi $name"
            """.trimIndent() + "\n",
        )
        val usageFile = writeFile(
            relativePath = "src/main/kotlin/sample/Use.kt",
            content = """
                package sample

                fun use(): String = greet("kast")
            """.trimIndent() + "\n",
        )

        val queryOffset = Files.readString(usageFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )
        session.use { s ->
            s.awaitInitialSourceIndex()

            // Complete Phase 2 future without inserting any symbol_reference rows
            s.sqliteStore.ensureSchema()
            val indexerField = StandaloneAnalysisSession::class.java.getDeclaredField("backgroundIndexer")
            indexerField.isAccessible = true
            val indexer = indexerField.get(s) as BackgroundIndexer
            indexer.referenceIndexReady.complete(Unit)
            assertTrue(s.isReferenceIndexReady())

            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = 100,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = s,
            )

            val result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    includeDeclaration = false,
                ),
            )

            assertTrue(
                result.references.any { it.filePath.contains("Use.kt") },
                "Should fall back and still find references when cache has no rows, got: ${result.references.map { it.filePath }}",
            )
        }
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }

    private fun normalizePath(path: Path): String = NormalizedPath.of(path).value
}
