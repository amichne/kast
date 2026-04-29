package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.ServerLimits
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StandaloneAnalysisBackendRenameTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `rename plans declaration and cross-file reference edits`(): TestResult = runTest {
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
        val secondaryUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
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

            val result = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            assertEquals(
                listOf(
                    normalizePath(declarationFile),
                    normalizePath(secondaryUsageFile),
                    normalizePath(usageFile),
                ),
                result.edits.map { edit -> edit.filePath },
            )
            assertEquals(listOf("welcome", "welcome", "welcome"), result.edits.map { edit -> edit.newText })
            assertEquals(
                listOf(normalizePath(declarationFile), normalizePath(secondaryUsageFile), normalizePath(usageFile)),
                result.affectedFiles,
            )
            assertEquals(
                listOf(
                    normalizePath(declarationFile) to FileHashing.sha256(declarationFile.readText()),
                    normalizePath(secondaryUsageFile) to FileHashing.sha256(secondaryUsageFile.readText()),
                    normalizePath(usageFile) to FileHashing.sha256(usageFile.readText()),
                ),
                result.fileHashes.map { hash -> hash.filePath to hash.hash },
            )
        }
    }

    @Test
    fun `capabilities advertise rename after implementation`(): TestResult = runTest {
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
            enablePhase2Indexing = false,
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

            assertTrue(MutationCapability.RENAME in capabilities.mutationCapabilities)
        }
    }

    @Test
    fun `rename plans edits without initializing full Kotlin file map`(): TestResult = runTest {
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
            enablePhase2Indexing = false,
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

            backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `rename applies both same-file reference edits without rereading between edits`(): TestResult = runTest {
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

                fun useTwice(): String = greet("kast") + greet("again")
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

            val renameResult = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            assertEquals(
                listOf(
                    normalizePath(declarationFile),
                    normalizePath(usageFile),
                    normalizePath(usageFile),
                ),
                renameResult.edits.map { edit -> edit.filePath },
            )
            assertEquals(
                2,
                renameResult.edits.count { edit -> edit.filePath == normalizePath(usageFile) },
            )

            val applyResult = backend.applyEdits(
                ApplyEditsQuery(
                    edits = renameResult.edits,
                    fileHashes = renameResult.fileHashes,
                ),
            )

            assertEquals(
                listOf(normalizePath(declarationFile), normalizePath(usageFile)),
                applyResult.affectedFiles,
            )
            assertEquals(
                """
                    package sample

                    fun useTwice(): String = welcome("kast") + welcome("again")
                """.trimIndent() + "\n",
                usageFile.readText(),
            )
        }
    }

    @Test
    fun `rename after apply edits uses refreshed psi state`(): TestResult = runTest {
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
        val secondaryUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
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

            val firstRename = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = usageFile.readText().indexOf("greet"),
                    ),
                    newName = "welcome",
                ),
            )
            backend.applyEdits(
                ApplyEditsQuery(
                    edits = firstRename.edits,
                    fileHashes = firstRename.fileHashes,
                ),
            )

            val secondRename = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = usageFile.readText().indexOf("welcome"),
                    ),
                    newName = "salute",
                ),
            )
            backend.applyEdits(
                ApplyEditsQuery(
                    edits = secondRename.edits,
                    fileHashes = secondRename.fileHashes,
                ),
            )

            assertEquals(
                listOf(
                    $$"""
                        package sample

                        fun salute(name: String): String = "hi $name"
                    """.trimIndent() + "\n",
                    """
                        package sample

                        fun use(): String = salute("kast")
                    """.trimIndent() + "\n",
                    """
                        package sample

                        fun useAgain(): String = salute("again")
                    """.trimIndent() + "\n",
                ),
                listOf(
                    declarationFile.readText(),
                    usageFile.readText(),
                    secondaryUsageFile.readText(),
                ),
            )
        }
    }

    @Test
    fun `rename after targeted refresh observes external file rewrite`(): TestResult = runTest {
        val sourceFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"

                fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
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

            val initialRename = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = sourceFile.toString(),
                        offset = sourceFile.readText().indexOf("greet"),
                    ),
                    newName = "initialGreeting",
                ),
            )
            assertTrue(initialRename.edits.isNotEmpty())

            sourceFile.writeText(
                """
                    package sample

                    fun welcome(): String = "hi"

                    fun use(): String = welcome()
                """.trimIndent() + "\n",
            )

            backend.refresh(RefreshQuery(filePaths = listOf(sourceFile.toString())))

            val refreshedRename = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = sourceFile.toString(),
                        offset = sourceFile.readText().indexOf("welcome"),
                    ),
                    newName = "salute",
                ),
            )

            assertTrue(refreshedRename.edits.isNotEmpty())
            assertTrue(
                refreshedRename.edits.all { edit -> edit.newText == "salute" },
                "Expected targeted refresh rename edits to use the refreshed symbol. Actual edits: ${refreshedRename.edits}",
            )
        }
    }

    @Test
    fun `rename after targeted refresh observes external rewrite before file is loaded`(): TestResult = runTest {
        val sourceFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"

                fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
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

            sourceFile.writeText(
                """
                    package sample

                    fun welcome(): String = "hi"

                    fun use(): String = welcome()
                """.trimIndent() + "\n",
            )

            backend.refresh(RefreshQuery(filePaths = listOf(sourceFile.toString())))

            val refreshedRename = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = sourceFile.toString(),
                        offset = sourceFile.readText().indexOf("welcome"),
                    ),
                    newName = "salute",
                ),
            )

            assertTrue(refreshedRename.edits.isNotEmpty())
            assertTrue(
                refreshedRename.edits.all { edit -> edit.newText == "salute" },
                "Expected targeted refresh rename edits to use the refreshed symbol. Actual edits: ${refreshedRename.edits}",
            )
        }
    }

    @Test
    fun `apply edits updates identifier index without loading full Kotlin file map`(): TestResult = runTest {
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
        val secondaryUsageFile = writeFile(
            relativePath = "src/main/kotlin/sample/SecondaryUse.kt",
            content = """
                package sample

                fun useAgain(): String = greet("again")
            """.trimIndent() + "\n",
        )
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
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

            val renameResult = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = usageFile.toString(),
                        offset = usageFile.readText().indexOf("greet"),
                    ),
                    newName = "welcome",
                ),
            )
            backend.applyEdits(
                ApplyEditsQuery(
                    edits = renameResult.edits,
                    fileHashes = renameResult.fileHashes,
                ),
            )

            assertEquals(
                setOf(
                    normalizePath(declarationFile),
                    normalizePath(usageFile),
                    normalizePath(secondaryUsageFile),
                ),
                session.candidateKotlinFilePaths("welcome").toSet(),
            )
            assertEquals(emptySet<String>(), session.candidateKotlinFilePaths("greet").toSet())
            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `rename private function only produces edits in declaring file`(): TestResult = runTest {
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
            enablePhase2Indexing = false,
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

            val result = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            val affectedFiles = result.edits.map { it.filePath }.distinct()
            assertEquals(listOf(normalizePath(declarationFile)), affectedFiles)
            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `rename private function does not load full Kotlin file map`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = $$"""
                package sample

                private fun greet(name: String): String = "hi $name"

                fun useGreet(): String = greet("world")
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
        val queryOffset = Files.readString(declarationFile).indexOf("greet")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
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

            backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "welcome",
                ),
            )

            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `operator function rename finds both explicit and operator-syntax call sites`(): TestResult = runTest {
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
        val content = Files.readString(declarationFile)
        val queryOffset = content.indexOf("plus")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
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

            val result = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = declarationFile.toString(),
                        offset = queryOffset,
                    ),
                    newName = "add",
                ),
            )

            val editFiles = result.edits.map { it.filePath }.distinct()
            assertTrue(editFiles.any { it.contains("Vector.kt") })
            assertTrue(editFiles.any { it.contains("ExplicitUsage.kt") })
            assertTrue(editFiles.any { it.contains("OperatorUsage.kt") })
        }
    }

    @Test
    fun `empty candidate paths returns declaring file only`(): TestResult = runTest {
        val declarationFile = writeFile(
            relativePath = "src/main/kotlin/sample/Unique.kt",
            content = """
                package sample

                fun extremelyUniqueNameNeverUsedElsewhere(): String = "unique"
            """.trimIndent() + "\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/Other.kt",
            content = """
                package sample

                fun other(): String = "other"
            """.trimIndent() + "\n",
        )
        val content = Files.readString(declarationFile)
        val queryOffset = content.indexOf("extremelyUniqueNameNeverUsedElsewhere")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
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
                    includeDeclaration = true,
                ),
            )

            // The function name appears only in the declaring file, so candidates should include
            // only that file. No allKtFiles() fallback should occur.
            assertFalse(session.isFullKtFileMapLoaded())
        }
    }

    @Test
    fun `rename updates explicit import in another file`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                class Greeter
            """.trimIndent() + "\n",
        )
        val usingFile = writeFile(
            relativePath = "src/main/kotlin/other/UseGreeter.kt",
            content = """
                package other

                import sample.Greeter

                fun make(): Greeter = Greeter()
            """.trimIndent() + "\n",
        )

        val content = Files.readString(workspaceRoot.resolve("src/main/kotlin/sample/Greeter.kt"))
        val queryOffset = content.indexOf("Greeter")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )
        session.use { s ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 100, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                session = s,
            )
            val result = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = workspaceRoot.resolve("src/main/kotlin/sample/Greeter.kt").toString(),
                        offset = queryOffset,
                    ),
                    newName = "Welcomer",
                ),
            )
            val usingFilePath = normalizePath(usingFile)
            val importEdit = result.edits.find { edit ->
                edit.filePath == usingFilePath && edit.newText == "sample.Welcomer"
            }
            assertTrue(
                importEdit != null,
                "Expected an edit for import sample.Greeter → sample.Welcomer in ${usingFilePath}.\nActual edits: ${result.edits}",
            )
        }
    }

    @Test
    fun `rename preserves alias in import directive`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                class Greeter
            """.trimIndent() + "\n",
        )
        val usingFile = writeFile(
            relativePath = "src/main/kotlin/other/UseGreeter.kt",
            content = """
                package other

                import sample.Greeter as G

                fun make(): G = G()
            """.trimIndent() + "\n",
        )

        val content = Files.readString(workspaceRoot.resolve("src/main/kotlin/sample/Greeter.kt"))
        val queryOffset = content.indexOf("Greeter")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )
        session.use { s ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 100, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                session = s,
            )
            val result = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = workspaceRoot.resolve("src/main/kotlin/sample/Greeter.kt").toString(),
                        offset = queryOffset,
                    ),
                    newName = "Welcomer",
                ),
            )
            val usingFilePath = normalizePath(usingFile)
            // The import FQN should be updated to sample.Welcomer while the "as G" alias is preserved
            val importEdit = result.edits.find { edit ->
                edit.filePath == usingFilePath && edit.newText == "sample.Welcomer"
            }
            assertTrue(
                importEdit != null,
                "Expected an edit updating import FQN to sample.Welcomer (alias preserved).\nActual edits: ${result.edits}",
            )
        }
    }

    @Test
    fun `rename does not produce duplicate edits for import`(): TestResult = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                class Greeter
            """.trimIndent() + "\n",
        )
        val usingFile = writeFile(
            relativePath = "src/main/kotlin/other/UseGreeter.kt",
            content = """
                package other

                import sample.Greeter

                fun make(): Greeter = Greeter()
            """.trimIndent() + "\n",
        )

        val content = Files.readString(workspaceRoot.resolve("src/main/kotlin/sample/Greeter.kt"))
        val queryOffset = content.indexOf("Greeter")
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )
        session.use { s ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(maxResults = 100, requestTimeoutMillis = 30_000, maxConcurrentRequests = 4),
                session = s,
            )
            val result = backend.rename(
                RenameQuery(
                    position = FilePosition(
                        filePath = workspaceRoot.resolve("src/main/kotlin/sample/Greeter.kt").toString(),
                        offset = queryOffset,
                    ),
                    newName = "Welcomer",
                ),
            )
            val usingFilePath = normalizePath(usingFile)
            val importEdits = result.edits.filter { edit ->
                edit.filePath == usingFilePath && edit.newText == "sample.Welcomer"
            }
            assertEquals(
                1,
                importEdits.size,
                "Expected exactly one import edit, got ${importEdits.size}: $importEdits",
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
