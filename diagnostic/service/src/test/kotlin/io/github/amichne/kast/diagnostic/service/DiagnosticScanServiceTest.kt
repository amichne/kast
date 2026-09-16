package io.github.amichne.kast.diagnostic.service

import io.github.amichne.kast.diagnostic.contract.DiagnosticBatch
import io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationWork
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
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `funded call advances enumeration analysis and output to completion`() {
        val fixture = Fixture()
        val funded =
            ResourceBudget(
                ResultLimit.parse(20).refined(),
                WorkUnitLimit.parse(20).refined(),
                budget.elapsedTimeLimit,
            )
        val result = runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), funded) }
        val complete = assertInstanceOf(DiagnosticScanResult.Complete::class.java, result)
        assertEquals(files, complete.page.analyzedFiles)
        assertEquals((0..8).toList(), complete.page.facts.map { it.location.range.start.value })
        assertEquals(3, fixture.analyses)
        assertEquals(1, fixture.enumerations)
    }

    @Test
    fun `small clean scope completes in one funded call`() {
        val fixture = Fixture()
        fixture.diagnosticsPerFile = 0
        val funded =
            ResourceBudget(ResultLimit.parse(20).refined(), WorkUnitLimit.parse(20).refined(), budget.elapsedTimeLimit)
        val complete =
            assertInstanceOf(
                DiagnosticScanResult.Complete::class.java,
                runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), funded) },
            )
        assertEquals(files, complete.page.analyzedFiles)
        assertTrue(complete.page.facts.isEmpty())
        assertEquals(0, complete.page.knownDiagnosticCount.value)
    }

    @Test
    fun `enumeration and analysis share work grant and resumed pages match funded scan`() {
        val fixture = Fixture()
        val strict =
            ResourceBudget(ResultLimit.parse(20).refined(), WorkUnitLimit.parse(2).refined(), budget.elapsedTimeLimit)
        val first =
            assertInstanceOf(
                DiagnosticScanResult.Advancing::class.java,
                runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), strict) },
            )
        assertEquals(1, fixture.analyses)
        assertEquals(3, first.page.facts.size)
        val last =
            assertInstanceOf(
                DiagnosticScanResult.Complete::class.java,
                runSuspend { fixture.service.scan(DiagnosticScanRequest.Resume(first.checkpoint), strict) },
            )
        val uninterrupted =
            assertInstanceOf(
                DiagnosticScanResult.Complete::class.java,
                runSuspend {
                    Fixture()
                        .service
                        .scan(
                            DiagnosticScanRequest.First(query),
                            strict.copy(workUnitLimit = WorkUnitLimit.parse(20).refined()),
                        )
                },
            )
        assertEquals(
            uninterrupted.page.facts.map { it.location },
            (first.page.facts + last.page.facts).map { it.location },
        )
        assertEquals(uninterrupted.page.analyzedFiles, last.page.analyzedFiles)
        assertEquals(uninterrupted.page.knownDiagnosticCount, last.page.knownDiagnosticCount)
        assertEquals(1, fixture.enumerations)
    }

    @Test
    fun `output grant is shared across analyzed files and retained suffixes`() {
        val fixture = Fixture()
        val strict =
            ResourceBudget(ResultLimit.parse(4).refined(), WorkUnitLimit.parse(20).refined(), budget.elapsedTimeLimit)
        val facts = mutableListOf<DiagnosticFact>()
        var request: DiagnosticScanRequest = DiagnosticScanRequest.First(query)
        repeat(3) {
            when (val result = runSuspend { fixture.service.scan(request, strict) }) {
                is DiagnosticScanResult.Advancing -> {
                    assertEquals(4, result.page.facts.size)
                    facts += result.page.facts
                    request = DiagnosticScanRequest.Resume(result.checkpoint)
                }
                is DiagnosticScanResult.Complete -> {
                    facts += result.page.facts
                    assertEquals((0..8).toList(), facts.map { it.location.range.start.value })
                    assertEquals(3, fixture.analyses)
                    return
                }
                else -> error("Unexpected $result")
            }
        }
        error("scan failed to finish")
    }

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

    @Test
    fun `final authority observation consuming time rejects before publication`() {
        val fixture = Fixture()
        fixture.elapsedDuringFinalValidation = 2_000_000_000
        val result = runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), budget) }
        assertEquals(
            DiagnosticScanRejection.ExecutionTimeGrantTooSmall,
            assertInstanceOf(DiagnosticScanResult.Rejected::class.java, result).reason,
        )
        assertEquals(0, fixture.analyses)
    }

    @Test
    fun `cancelled enumeration publishes no checkpoint and retry starts independently`() {
        val fixture = Fixture()
        fixture.cancelEnumeration = true
        assertThrows(CancellationException::class.java) {
            runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), budget) }
        }
        fixture.cancelEnumeration = false
        val retry = runSuspend { fixture.service.scan(DiagnosticScanRequest.First(query), budget) }
        assertInstanceOf(DiagnosticScanResult.Advancing::class.java, retry)
        assertEquals(0, fixture.analyses)
        assertEquals(2, fixture.enumerations)
    }

    private inner class Fixture {
        var analyses = 0
        var diagnosticsPerFile = 3
        var validations = 0
        var enumerations = 0
        var cancelEnumeration = false
        var elapsedDuringFinalValidation = 0L
        var validation = SemanticReadValidation.CURRENT
        var moveDuringAnalysis = false
        var elapsedDuringAnalysis = 0L
        var now = 0L
        val service =
            DiagnosticScanService(
                SemanticReadValidationPort {
                    validations++
                    if (validations % 2 == 0) now += elapsedDuringFinalValidation
                    validation
                },
                DiagnosticScopeEnumerator { _, _ ->
                    enumerations++
                    if (cancelEnumeration) throw CancellationException("fixture cancellation")
                    DiagnosticEnumerationResult.Exhausted(files, DiagnosticEnumerationWork.NONE.incremented())
                },
                DiagnosticOperations { request ->
                    val index = analyses++
                    val batch =
                        DiagnosticBatch.create(
                                request.scope,
                                (0 until diagnosticsPerFile).map { offset ->
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
