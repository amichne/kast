package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ReadCapability
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.SearchScopeKind
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolVisibility
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

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }

    private fun normalizePath(path: Path): String {
        val absolutePath = path.toAbsolutePath().normalize()
        return runCatching { absolutePath.toRealPath().normalize().toString() }.getOrDefault(absolutePath.toString())
    }
}
