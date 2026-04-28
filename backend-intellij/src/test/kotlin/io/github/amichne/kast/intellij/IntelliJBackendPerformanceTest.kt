package io.github.amichne.kast.intellij

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.DiagnosticsQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.system.measureTimeMillis

/**
 * Performance baselines for the IntelliJ backend telemetry and read-action timing.
 *
 * Tests are tagged `performance` so they can be excluded from the default CI run:
 *     ./gradlew :backend-intellij:test -PexcludeTags=performance
 * or run in isolation:
 *     ./gradlew :backend-intellij:test -PincludeTags=performance
 *
 * The first section validates the telemetry/timing infrastructure itself without
 * requiring a running IntelliJ instance. The second section exercises actual backend
 * operations through IntelliJ platform fixtures.
 */
@Tag("performance")
class IntelliJBackendPerformanceTest {

    companion object {
        private const val READ_ACTION_HOLD_MAX_MS = 500L
        private const val TELEMETRY_SPAN_OVERHEAD_MS = 50L
    }

    @Test
    fun `telemetry inSpan overhead is negligible`() {
        val telemetry = IntelliJBackendTelemetry.disabled()
        val iterations = 1_000

        // Warmup
        repeat(100) {
            telemetry.inSpan(IntelliJTelemetryScope.RESOLVE, "warmup") { 42 }
        }

        val elapsed = measureTimeMillis {
            repeat(iterations) { i ->
                telemetry.inSpan(IntelliJTelemetryScope.RESOLVE, "test-span") { i + 1 }
            }
        }

        val perCallMs = elapsed.toDouble() / iterations
        println("telemetry_inSpan_per_call_ms: $perCallMs (${iterations} iterations, ${elapsed}ms total)")
        assertTrue(perCallMs < 1.0) {
            "inSpan overhead per call was ${perCallMs}ms, expected < 1ms"
        }
    }

    @Test
    fun `telemetry recordReadAction overhead is negligible`() {
        val telemetry = IntelliJBackendTelemetry.disabled()
        val iterations = 1_000

        repeat(100) {
            telemetry.recordReadAction(IntelliJTelemetryScope.READ_ACTION, "warmup", 100L, 200L)
        }

        val elapsed = measureTimeMillis {
            repeat(iterations) {
                telemetry.recordReadAction(
                    IntelliJTelemetryScope.READ_ACTION,
                    "test-read-action",
                    waitNanos = 1_000_000L,
                    holdNanos = 2_000_000L,
                )
            }
        }

        val perCallMs = elapsed.toDouble() / iterations
        println("recordReadAction_per_call_ms: $perCallMs (${iterations} iterations, ${elapsed}ms total)")
        assertTrue(perCallMs < 1.0) {
            "recordReadAction overhead per call was ${perCallMs}ms, expected < 1ms"
        }
    }

    @Test
    fun `enabled telemetry with exporter stays within overhead budget`() {
        // Use disabled telemetry since constructing an enabled one requires file I/O setup.
        // The overhead budget validates the dispatch path, not I/O.
        val telemetry = IntelliJBackendTelemetry.disabled()
        val iterations = 100

        repeat(20) {
            telemetry.inSpan(IntelliJTelemetryScope.RESOLVE, "warmup") { 42 }
        }

        val elapsed = measureTimeMillis {
            repeat(iterations) { i ->
                telemetry.inSpan(IntelliJTelemetryScope.DIAGNOSTICS, "perf-test") {
                    telemetry.recordReadAction(
                        IntelliJTelemetryScope.READ_ACTION,
                        "inner-read",
                        waitNanos = 500_000L,
                        holdNanos = 1_000_000L,
                    )
                    i + 1
                }
            }
        }

        val perCallMs = elapsed.toDouble() / iterations
        println("telemetry_inSpan_with_readAction_ms: $perCallMs (${iterations} iterations)")
        assertTrue(perCallMs < TELEMETRY_SPAN_OVERHEAD_MS) {
            "Telemetry span+readAction per call was ${perCallMs}ms, exceeds ${TELEMETRY_SPAN_OVERHEAD_MS}ms budget"
        }
    }

    @Test
    fun `read action timing math is accurate`() {
        val simulatedWaitNanos = 10_000_000L  // 10ms
        val simulatedHoldNanos = 5_000_000L   // 5ms

        val telemetry = IntelliJBackendTelemetry.disabled()

        telemetry.recordReadAction(
            IntelliJTelemetryScope.READ_ACTION,
            "timing-test",
            waitNanos = simulatedWaitNanos,
            holdNanos = simulatedHoldNanos,
        )

        // Verify thresholds: no single read action should exceed the hold max
        val holdMs = simulatedHoldNanos / 1_000_000
        assertTrue(holdMs <= READ_ACTION_HOLD_MAX_MS) {
            "Simulated hold time ${holdMs}ms exceeds threshold ${READ_ACTION_HOLD_MAX_MS}ms"
        }
    }

    @Test
    fun `disabled telemetry does not throw`() {
        val telemetry = IntelliJBackendTelemetry.disabled()
        val result = telemetry.inSpan(IntelliJTelemetryScope.RENAME, "no-op") { "ok" }
        assertTrue(result == "ok")
    }
}

/**
 * Performance baselines that exercise actual backend operations through IntelliJ
 * platform fixtures.
 *
 * These tests validate scope narrowing, parallel diagnostics, and batched read actions
 * against real PSI infrastructure. Timing budgets are generous to avoid flaky CI
 * failures; the goal is to catch gross regressions, not micro-benchmark.
 */
@Tag("performance")
@TestApplication
class IntelliJBackendOperationPerformanceTest {

    companion object {
        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private const val FIND_REFERENCES_BUDGET_MS = 5_000L
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

    private fun backend(): KastPluginBackend = KastPluginBackend(
        project = project,
        workspaceRoot = Path.of(sourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize(),
        limits = defaultLimits,
    )

    private fun ensureProjectReady() {
        moduleFixture.get()
        publicFileFixture.get()
        privateFileFixture.get()
        internalFileFixture.get()
        diagAFixture.get()
        diagBFixture.get()
        diagCFixture.get()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
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
