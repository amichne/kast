package io.github.amichne.kast.diagnostic.service

import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticOperations
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanInventory
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanPage
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticScanResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeEnumerator
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SemanticReadValidation
import io.github.amichne.kast.workspace.contract.SemanticReadValidationPort
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticScanServiceTest {
    private val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(19).refined(),
        )
    private val query = DiagnosticScopeQuery.parse(lease, "src").refined()
    private val budget =
        ResourceBudget(
            ResultLimit.parse(1).refined(),
            WorkUnitLimit.parse(1).refined(),
            ElapsedTimeLimitMillis.parse(1000).refined(),
        )
    private val files =
        DiagnosticScope.fromCanonicalPaths(lease, (1..3).map { Path.of("/workspace/src/File$it.kt") }).refined().files

    @Test
    fun `heavy file output drains without repeated compiler analysis and final coverage is exact`() {
        val fixture = Fixture()
        val pages = mutableListOf<DiagnosticScanPage>()
        var request: DiagnosticScanRequest = DiagnosticScanRequest.First(query)
        repeat(30) {
            when (val result = runSuspend { fixture.service.scan(request, budget) }) {
                is DiagnosticScanResult.Advancing -> {
                    pages += result.page
                    request = DiagnosticScanRequest.Resume(result.checkpoint)
                }
                is DiagnosticScanResult.Complete -> {
                    pages += result.page
                    assertEquals(files, result.page.analyzedFiles)
                    assertEquals(
                        files,
                        assertInstanceOf(DiagnosticScanInventory.Exhausted::class.java, result.page.inventory).files,
                    )
                    assertEquals(9, pages.sumOf { it.facts.size })
                    assertEquals(9, result.page.knownDiagnosticCount.value)
                    assertEquals(3, fixture.analyses)
                    assertEquals((0..8).toList(), pages.flatMap { it.facts }.map { it.location.range.start.value })
                    return
                }
                else -> error("Unexpected $result")
            }
        }
        error("scan failed to finish")
    }

    @Test
    fun `enumeration alone cannot establish clean scope`() {
        val result = runSuspend { Fixture().service.scan(DiagnosticScanRequest.First(query), budget) }
        val advancing = assertInstanceOf(DiagnosticScanResult.Advancing::class.java, result)
        assertTrue(advancing.page.facts.isEmpty())
        assertTrue(advancing.page.analyzedFiles.isEmpty())
    }

    @Test
    fun `basis movement rejects continuation before additional work`() {
        val fixture = Fixture()
        val result = runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), budget) }
        fixture.validation = SemanticReadValidation.MOVED
        val rejected = runSuspend {
            fixture.service.scan(
                DiagnosticScanRequest.Resume(
                    assertInstanceOf(DiagnosticScanResult.Advancing::class.java, result).checkpoint
                ),
                budget,
            )
        }
        assertEquals(
            DiagnosticScanRejection.StaleBasis,
            assertInstanceOf(DiagnosticScanResult.Rejected::class.java, rejected).reason,
        )
        assertEquals(0, fixture.analyses)
    }

    @Test
    fun `movement during analysis rejects before publication`() {
        val fixture = Fixture()
        val first = runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), budget) }
        fixture.moveDuringAnalysis = true
        val rejected = runSuspend {
            fixture.service.scan(
                DiagnosticScanRequest.Resume(
                    assertInstanceOf(DiagnosticScanResult.Advancing::class.java, first).checkpoint
                ),
                budget,
            )
        }
        assertEquals(
            DiagnosticScanRejection.StaleBasis,
            assertInstanceOf(DiagnosticScanResult.Rejected::class.java, rejected).reason,
        )
    }

    @Test
    fun `indivisible compiler overrun returns increase budget instead of unchanged cursor`() {
        val fixture = Fixture()
        val first = runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), budget) }
        fixture.elapsedDuringAnalysis = 2_000_000_000
        val result = runSuspend {
            fixture.service.scan(
                DiagnosticScanRequest.Resume(
                    assertInstanceOf(DiagnosticScanResult.Advancing::class.java, first).checkpoint
                ),
                budget,
            )
        }
        assertEquals(
            DiagnosticScanRejection.IndivisibleUnitExceedsBudget,
            assertInstanceOf(DiagnosticScanResult.Rejected::class.java, result).reason,
        )
        assertEquals(1, fixture.analyses)
    }

    private inner class Fixture {
        var analyses = 0
        var validation = SemanticReadValidation.CURRENT
        var moveDuringAnalysis = false
        var elapsedDuringAnalysis = 0L
        var now = 0L
        val service =
            DiagnosticScanService(
                SemanticReadValidationPort { validation },
                DiagnosticScopeEnumerator { _, _ -> DiagnosticEnumerationResult.Exhausted(files) },
                DiagnosticOperations { request ->
                    val index = analyses++
                    val batch =
                        DiagnosticBatch.create(
                                request.scope,
                                (0..2).map { offset ->
                                    DiagnosticFact.fromBoundary(
                                            request.scope,
                                            request.scope.files.single(),
                                            index * 3 + offset,
                                            index * 3 + offset + 1,
                                            DiagnosticSeverity.ERROR,
                                            "REPEATED",
                                            "Same message",
                                        )
                                        .refined()
                                },
                            )
                            .refined()
                    if (moveDuringAnalysis) validation = SemanticReadValidation.MOVED
                    now += elapsedDuringAnalysis
                    val compilation = DiagnosticCompilation.complete(batch)
                    DiagnosticCheckResult.Complete(compilation.batch, compilation.coverage)
                },
                { now },
            )
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(value: Result<T>) {
                    result = value
                }
            }
        )
        return requireNotNull(result).getOrThrow()
    }
}
