package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis

/**
 * TDD tests for the incremental implementations() fast path.
 *
 * The timing test (Test 3) is tagged `performance` so it can be excluded from
 * fast CI runs. The functional tests (Tests 1, 2, 4) run in the default suite.
 *
 * Tests 1–3 MUST FAIL before the following changes are applied:
 *   1. [DeclarationRow] gains a `supertypes` field (compilation failures below)
 *   2. [SqliteSourceIndexStore] gains `declarationsWithSupertype()` (compilation failure)
 *   3. `implementations()` uses allKtFiles() unconditionally (behavioral failure)
 *
 * Test 4 tests the fallback path, which already works, and acts as a regression guard.
 */
class IncrementalImplementationsTest {

    @TempDir
    lateinit var workspaceRoot: Path

    // -------------------------------------------------------------------------
    // Test 1: When the Phase-2 declaration index is ready, implementations()
    // must use the SQLite declaration index — not enumerate all KtFiles.
    //
    // Verified behaviourally: the index is pre-populated with the correct
    // supertypes; the result is asserted to be correct. If the fast path is
    // absent, the slow path would still work but the test would fail to
    // COMPILE because DeclarationRow.supertypes does not yet exist.
    // -------------------------------------------------------------------------
    @Test
    fun fastPathUsesDeclarationIndexWhenIndexReady(): TestResult = runTest {
        val typeFile = writeFile(
            relativePath = "src/main/kotlin/sample/Types.kt",
            content = """
                package sample

                interface Greeter
                class ConcreteGreeter : Greeter
            """.trimIndent() + "\n",
        )
        val targetOffset = Files.readString(typeFile).indexOf("Greeter")

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )

        session.use { s ->
            s.sqliteStore.ensureSchema()
            val filePath = normalizePath(typeFile)

            // Populate declaration_supertypes via the new DeclarationRow.supertypes field.
            // DeclarationRow.supertypes does NOT exist yet -> compilation failure (Red).
            s.sqliteStore.replaceDeclarationsFromFile(
                filePath = filePath,
                declarations = listOf(
                    DeclarationRow(
                        fqName = "sample.Greeter",
                        kind = DeclarationKind.INTERFACE,
                        visibility = DeclarationVisibility.PUBLIC,
                        filePath = filePath,
                        declarationOffset = null,
                        modulePath = null,
                        sourceSet = null,
                        supertypes = emptyList(),                  // <- compilation error before change 3a
                    ),
                    DeclarationRow(
                        fqName = "sample.ConcreteGreeter",
                        kind = DeclarationKind.CLASS,
                        visibility = DeclarationVisibility.PUBLIC,
                        filePath = filePath,
                        declarationOffset = null,
                        modulePath = null,
                        sourceSet = null,
                        supertypes = listOf("sample.Greeter"),     // <- compilation error before change 3a
                    ),
                ),
            )

            // Signal that Phase-2 declaration index is ready.
            completeReferenceIndex(s)
            assertTrue(s.isReferenceIndexReady())

            val backend = makeBackend(s)
            val result = backend.implementations(
                ImplementationsQuery(
                    position = FilePosition(filePath = typeFile.toString(), offset = targetOffset),
                ),
            )

            assertTrue(result.implementations.any { it.fqName == "sample.ConcreteGreeter" }) {
                "Expected ConcreteGreeter in fast-path implementations, got: ${result.implementations.map { it.fqName }}"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: The fast path must transitively expand supertypes.
    //
    // Hierarchy:  Greeter (interface)
    //               +-- BaseGreeter (abstract)  implements Greeter
    //                     +-- LoudGreeter        extends BaseGreeter
    //
    // The index stores only DIRECT supertypes. The transitive expansion must
    // discover LoudGreeter through BaseGreeter. Abstract BaseGreeter is
    // excluded from the final result.
    // -------------------------------------------------------------------------
    @Test
    fun fastPathFindsTransitiveSubtypesThroughMultipleLevels(): TestResult = runTest {
        val typeFile = writeFile(
            relativePath = "src/main/kotlin/sample/Hierarchy.kt",
            content = """
                package sample

                interface Greeter
                abstract class BaseGreeter : Greeter
                class LoudGreeter : BaseGreeter()
            """.trimIndent() + "\n",
        )
        val targetOffset = Files.readString(typeFile).indexOf("Greeter")

        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )

        session.use { s ->
            s.sqliteStore.ensureSchema()
            val filePath = normalizePath(typeFile)

            // Populate DIRECT supertypes only — transitivity is the backend's job.
            s.sqliteStore.replaceDeclarationsFromFile(
                filePath = filePath,
                declarations = listOf(
                    DeclarationRow(
                        fqName = "sample.Greeter",
                        kind = DeclarationKind.INTERFACE,
                        visibility = DeclarationVisibility.PUBLIC,
                        filePath = filePath,
                        declarationOffset = null,
                        modulePath = null,
                        sourceSet = null,
                        supertypes = emptyList(),                   // <- compilation error before change 3a
                    ),
                    DeclarationRow(
                        fqName = "sample.BaseGreeter",
                        kind = DeclarationKind.CLASS,
                        visibility = DeclarationVisibility.PUBLIC,
                        filePath = filePath,
                        declarationOffset = null,
                        modulePath = null,
                        sourceSet = null,
                        supertypes = listOf("sample.Greeter"),      // <- compilation error before change 3a
                    ),
                    DeclarationRow(
                        fqName = "sample.LoudGreeter",
                        kind = DeclarationKind.CLASS,
                        visibility = DeclarationVisibility.PUBLIC,
                        filePath = filePath,
                        declarationOffset = null,
                        modulePath = null,
                        sourceSet = null,
                        supertypes = listOf("sample.BaseGreeter"),  // <- compilation error before change 3a
                    ),
                ),
            )

            completeReferenceIndex(s)

            val backend = makeBackend(s)
            val result = backend.implementations(
                ImplementationsQuery(
                    position = FilePosition(filePath = typeFile.toString(), offset = targetOffset),
                ),
            )

            assertTrue(result.implementations.any { it.fqName == "sample.LoudGreeter" }) {
                "Transitive expansion must discover LoudGreeter. Got: ${result.implementations.map { it.fqName }}"
            }
            assertTrue(result.implementations.none { it.fqName == "sample.BaseGreeter" }) {
                "Abstract BaseGreeter must be excluded from implementations"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: Performance — fast path must complete in < 2 s with 500 source
    // files when only 5 contain relevant types.
    //
    // The fast path queries the declaration index for matching types and loads
    // KtFiles only for those 5 files — not all 500 — so the call is O(k) in
    // the number of matching types, not O(n) in workspace size.
    // -------------------------------------------------------------------------
    @Tag("performance")
    @Test
    fun completesWithin2SecondsFor500FilesWhen5AreRelevant(): TestResult =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            val totalFiles = 500
            val relevantFiles = 5

            // 495 unrelated filler files.
            repeat(totalFiles - relevantFiles) { i ->
                writeFile(
                    relativePath = "src/main/kotlin/filler/Filler$i.kt",
                    content = "package filler\nfun value$i(): Int = $i\n",
                )
            }

            // 1 file with the interface + 5 implementing classes.
            val typeFile = writeFile(
                relativePath = "src/main/kotlin/sample/FastTypes.kt",
                content = buildString {
                    appendLine("package sample")
                    appendLine()
                    appendLine("interface FastTarget")
                    repeat(relevantFiles) { i ->
                        appendLine("class FastImpl$i : FastTarget")
                    }
                },
            )
            val targetOffset = Files.readString(typeFile).indexOf("FastTarget")

            val session = StandaloneAnalysisSession(
                workspaceRoot = workspaceRoot,
                sourceRoots = emptyList(),
                classpathRoots = emptyList(),
                moduleName = "sources",
                enablePhase2Indexing = false,
            )

            session.use { s ->
                s.sqliteStore.ensureSchema()
                val filePath = normalizePath(typeFile)

                // Index the 5 relevant declarations only.
                val declarations = mutableListOf(
                    DeclarationRow(
                        fqName = "sample.FastTarget",
                        kind = DeclarationKind.INTERFACE,
                        visibility = DeclarationVisibility.PUBLIC,
                        filePath = filePath,
                        declarationOffset = null,
                        modulePath = null,
                        sourceSet = null,
                        supertypes = emptyList(),                    // <- compilation error before change 3a
                    ),
                )
                repeat(relevantFiles) { i ->
                    declarations += DeclarationRow(
                        fqName = "sample.FastImpl$i",
                        kind = DeclarationKind.CLASS,
                        visibility = DeclarationVisibility.PUBLIC,
                        filePath = filePath,
                        declarationOffset = null,
                        modulePath = null,
                        sourceSet = null,
                        supertypes = listOf("sample.FastTarget"),    // <- compilation error before change 3a
                    )
                }
                s.sqliteStore.replaceDeclarationsFromFile(filePath = filePath, declarations = declarations)

                completeReferenceIndex(s)

                val backend = makeBackend(s)

                val elapsedMs = measureTimeMillis {
                    backend.implementations(
                        ImplementationsQuery(
                            position = FilePosition(filePath = typeFile.toString(), offset = targetOffset),
                        ),
                    )
                }

                assertTrue(elapsedMs < 2_000L) {
                    "implementations() with $totalFiles files ($relevantFiles relevant) " +
                        "took ${elapsedMs}ms, expected < 2000ms"
                }
            }
        }

    // -------------------------------------------------------------------------
    // Test 4: When the declaration index is NOT ready, implementations() falls
    // back to the existing allKtFiles() scan.
    //
    // This test PASSES before the fast-path implementation (tests existing
    // behaviour) and continues to pass after it (regression guard).
    // -------------------------------------------------------------------------
    @Test
    fun fallsBackToAllKtFilesScanWhenIndexNotReady(): TestResult = runTest {
        val typeFile = writeFile(
            relativePath = "src/main/kotlin/sample/Fallback.kt",
            content = """
                package sample

                interface SlowTarget
                class SlowImpl : SlowTarget
            """.trimIndent() + "\n",
        )
        val targetOffset = Files.readString(typeFile).indexOf("SlowTarget")

        // enablePhase2Indexing = false  -> referenceIndexReady never completes.
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
            enablePhase2Indexing = false,
        )

        session.use { s ->
            assertTrue(!s.isReferenceIndexReady()) {
                "Index must NOT be ready so the fallback path is exercised"
            }

            val result = makeBackend(s).implementations(
                ImplementationsQuery(
                    position = FilePosition(filePath = typeFile.toString(), offset = targetOffset),
                ),
            )

            // Slow path (allKtFiles) must still discover SlowImpl.
            assertTrue(result.implementations.any { it.fqName == "sample.SlowImpl" }) {
                "Slow-path fallback must find SlowImpl. Got: ${result.implementations.map { it.fqName }}"
            }
            assertTrue(result.implementations.none { it.fqName == "sample.SlowTarget" }) {
                "Interface SlowTarget must be excluded from implementations"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeBackend(session: StandaloneAnalysisSession): StandaloneAnalysisBackend =
        StandaloneAnalysisBackend(
            workspaceRoot = workspaceRoot,
            limits = ServerLimits(
                maxResults = 100,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 4,
            ),
            session = session,
        )

    private fun writeFile(relativePath: String, content: String): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }

    private fun normalizePath(path: Path): String = NormalizedPath.of(path).value

    /** Marks the Phase-2 background indexer as done via reflection. */
    private fun completeReferenceIndex(session: StandaloneAnalysisSession) {
        val field = StandaloneAnalysisSession::class.java.getDeclaredField("backgroundIndexer")
        field.isAccessible = true
        val indexer = field.get(session) as BackgroundIndexer
        indexer.referenceIndexReady.complete(Unit)
    }
}
