package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.system.measureTimeMillis

/**
 * Performance baselines that exercise actual indexer operations through compiler PSI
 * platform fixtures.
 *
 * These tests validate scope narrowing, parallel diagnostics, and batched read actions
 * against real PSI infrastructure. Timing budgets are generous to avoid flaky CI
 * failures; the goal is to catch gross regressions, not micro-benchmark.
 */
@Tag("performance")
@TestApplication
class IdeaBackendOperationPerformanceTest {

    companion object {
        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private const val FIND_REFERENCES_BUDGET_MS = 5_000L
        private const val FIND_REFERENCES_P95_BUDGET_MS = 1_000L
        private const val FIND_REFERENCES_P95_ITERATIONS = 10
        private const val PROJECT_SCOPE_KOTLIN_FILE_COUNT = 6
        private const val DIAGNOSTICS_BUDGET_MS = 10_000L
        private const val WORKSPACE_SYMBOL_SEARCH_BUDGET_MS = 5_000L

        private const val publicFunctionSource = """
            package perf

            fun publicHelper(): String = "public"
        """

        private const val privateFunctionSource = """
            package perf

            private fun privateHelper(): String = "private"

            fun callsPrivate(): String = privateHelper()
        """

        private const val internalFunctionSource = """
            package perf

            internal fun internalHelper(): String = "internal"
        """

        private const val diagnosticsFileA = """
            package perf.diag

            fun validA(): Int = 42
        """

        private const val diagnosticsFileB = """
            package perf.diag

            fun validB(): String = "ok"
        """

        private const val diagnosticsFileC = """
            package perf.diag

            fun validC(): Boolean = true
        """
    }

    private val projectFixture: TestFixture<Project> = projectFixture()
    private val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val sourceRootFixture: TestFixture<PsiDirectory> = moduleFixture.sourceRootFixture()

    private val publicFileFixture: TestFixture<PsiFile> = sourceRootFixture.psiFileFixture("PublicHelper.kt", publicFunctionSource)
    private val privateFileFixture: TestFixture<PsiFile> = sourceRootFixture.psiFileFixture("PrivateHelper.kt", privateFunctionSource)
    private val internalFileFixture: TestFixture<PsiFile> = sourceRootFixture.psiFileFixture("InternalHelper.kt", internalFunctionSource)
    private val diagAFixture: TestFixture<PsiFile> = sourceRootFixture.psiFileFixture("DiagA.kt", diagnosticsFileA)
    private val diagBFixture: TestFixture<PsiFile> = sourceRootFixture.psiFileFixture("DiagB.kt", diagnosticsFileB)
    private val diagCFixture: TestFixture<PsiFile> = sourceRootFixture.psiFileFixture("DiagC.kt", diagnosticsFileC)

    private val project: Project
        get() = projectFixture.get()

    private fun backend(): KastIndexerBackend = KastIndexerBackend(
        project = project,
        workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize(),
        limits = defaultLimits,
        workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(),
        workspaceTransitionRequester = TestWorkspaceTransitionRequester(),
    )

    private fun ensureProjectReady() {
        moduleFixture.get()
        publicFileFixture.get()
        privateFileFixture.get()
        internalFileFixture.get()
        diagAFixture.get()
        diagBFixture.get()
        diagCFixture.get()
        waitUntilIndexesAreReady(project)
    }

    @Test
    fun `findReferences for private symbol uses file scope`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            privateFileFixture.get().virtualFile.path to
                privateFileFixture.get().text.indexOf("privateHelper")
        }

        val elapsed = measureTimeMillis {
            val result = backend().findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                ),
            )
            assertEquals(SearchScopeKind.FILE, result.searchScope?.scope)
            assertTrue((result.searchScope?.candidateFileCount ?: Int.MAX_VALUE) <= 1) {
                "Private symbol should search at most 1 file, got ${result.searchScope?.candidateFileCount}"
            }
        }

        println("findReferences_private_ms: $elapsed")
        assertTrue(elapsed < FIND_REFERENCES_BUDGET_MS) {
            "findReferences for private symbol took ${elapsed}ms, exceeds ${FIND_REFERENCES_BUDGET_MS}ms budget"
        }
    }

    @Test
    fun `findReferences for public symbol uses project scope`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            publicFileFixture.get().virtualFile.path to
                publicFileFixture.get().text.indexOf("publicHelper")
        }

        val elapsed = measureTimeMillis {
            val result = backend().findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                ),
            )
            assertEquals(SearchScopeKind.DEPENDENT_MODULES, result.searchScope?.scope)
        }

        println("findReferences_public_ms: $elapsed")
        assertTrue(elapsed < FIND_REFERENCES_BUDGET_MS) {
            "findReferences for public symbol took ${elapsed}ms, exceeds ${FIND_REFERENCES_BUDGET_MS}ms budget"
        }
    }

    @Test
    fun `findReferences for warm public symbol stays under p95 budget`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            publicFileFixture.get().virtualFile.path to
                publicFileFixture.get().text.indexOf("publicHelper")
        }
        val backend = backend()
        val query = ReferencesQuery(
            position = FilePosition(filePath = filePath, offset = offset),
            includeDeclaration = false,
        )

        backend.findReferences(query)
        val durations = List(FIND_REFERENCES_P95_ITERATIONS) {
            var candidateFileCount = Int.MAX_VALUE
            val elapsed = measureTimeMillis {
                val result = backend.findReferences(query)
                assertEquals(SearchScopeKind.DEPENDENT_MODULES, result.searchScope?.scope)
                candidateFileCount = result.searchScope?.candidateFileCount ?: Int.MAX_VALUE
            }
            assertEquals(
                PROJECT_SCOPE_KOTLIN_FILE_COUNT,
                candidateFileCount,
                "Exhaustive fallback must account for every in-scope Kotlin fixture",
            )
            elapsed
        }.sorted()
        val p95Index = ((durations.size * 95 + 99) / 100 - 1).coerceIn(0, durations.lastIndex)
        val p95 = durations[p95Index]

        println("findReferences_public_warm_p95_ms: $p95 durations=$durations")
        assertTrue(p95 < FIND_REFERENCES_P95_BUDGET_MS) {
            "findReferences warm public p95 was ${p95}ms, exceeds ${FIND_REFERENCES_P95_BUDGET_MS}ms budget"
        }
    }

    @Test
    fun `findReferences for internal symbol uses dependent modules scope`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            internalFileFixture.get().virtualFile.path to
                internalFileFixture.get().text.indexOf("internalHelper")
        }

        val elapsed = measureTimeMillis {
            val result = backend().findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                ),
            )
            assertEquals(SearchScopeKind.DEPENDENT_MODULES, result.searchScope?.scope)
        }

        println("findReferences_internal_ms: $elapsed")
        assertTrue(elapsed < FIND_REFERENCES_BUDGET_MS) {
            "findReferences for internal symbol took ${elapsed}ms, exceeds ${FIND_REFERENCES_BUDGET_MS}ms budget"
        }
    }

    @Test
    fun `diagnostics across multiple files completes within budget`() = runBlocking {
        ensureProjectReady()

        val filePaths = readAction {
            listOf(
                diagAFixture.get().virtualFile.path,
                diagBFixture.get().virtualFile.path,
                diagCFixture.get().virtualFile.path,
            )
        }

        val elapsed = measureTimeMillis {
            val result = backend().diagnostics(DiagnosticsQuery(filePaths = filePaths))
            assertTrue(result.diagnostics.none { it.code == "ANALYSIS_FAILURE" }) {
                "Unexpected analysis failures: ${result.diagnostics.filter { it.code == "ANALYSIS_FAILURE" }}"
            }
        }

        println("diagnostics_3_files_ms: $elapsed")
        assertTrue(elapsed < DIAGNOSTICS_BUDGET_MS) {
            "diagnostics for 3 files took ${elapsed}ms, exceeds ${DIAGNOSTICS_BUDGET_MS}ms budget"
        }
    }

    @Test
    fun `workspaceSymbolSearch completes within budget`() = runBlocking {
        ensureProjectReady()

        val elapsed = measureTimeMillis {
            val result = backend().workspaceSymbolSearch(
                WorkspaceSymbolQuery(
                    pattern = "Helper",
                    maxResults = 100,
                ),
            )
            assertTrue(result.symbols.isNotEmpty()) {
                "Expected at least one symbol matching 'Helper'"
            }
        }

        println("workspaceSymbolSearch_ms: $elapsed")
        assertTrue(elapsed < WORKSPACE_SYMBOL_SEARCH_BUDGET_MS) {
            "workspaceSymbolSearch took ${elapsed}ms, exceeds ${WORKSPACE_SYMBOL_SEARCH_BUDGET_MS}ms budget"
        }
    }
}
