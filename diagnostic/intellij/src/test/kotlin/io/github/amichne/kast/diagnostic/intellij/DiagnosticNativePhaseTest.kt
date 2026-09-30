package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real native attempt owners with controlled index/K2 effects; no IDE timing or compiler performance claim. */
class DiagnosticNativePhaseTest {
    private val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(19).refined(),
        )
    private val scope = DiagnosticScope.fromCanonicalPaths(lease, listOf(Path.of("/workspace/A.kt"))).refined()
    private val file = scope.files.single()
    private val query = DiagnosticScopeQuery.parse(lease, ".").refined()

    @Test
    fun `analysis phase precedes native effect and complete facts preserve exact coverage`() =
        runBlocking<Unit> {
            val observation = Phases()
            val fact =
                DiagnosticFact.fromBoundary(
                        scope = scope,
                        file = file,
                        start = 1,
                        endExclusive = 2,
                        severity = DiagnosticSeverity.ERROR,
                        code = "ERROR",
                        message = "message",
                    )
                    .refined()
            var calls = 0
            val result =
                compile(observation) {
                    assertAnalysisEntry(observation)
                    calls++
                    listOf(IntellijDiagnosticProjection.Projected(listOf(fact)))
                }
            assertEquals(1, calls)
            val complete = assertInstanceOf(DiagnosticCompilation.Complete::class.java, result)
            assertEquals(listOf(fact), complete.batch.facts)
            assertSame(scope, complete.batch.scope)
            assertEquals(listOf(file), complete.coverage.analyzedFiles)
            assertInstanceOf(IntellijDiagnosticCompilationEvidence.Complete::class.java, result.observation())
        }

    @Test
    fun `analysis native failures retain phase and finite incomplete coverage`() = runBlocking {
        for (failure in listOf(IllegalStateException("fixture"), LinkageError("fixture"))) {
            val observation = Phases()
            val result =
                compile(observation) {
                    assertAnalysisEntry(observation)
                    throw failure
                }
            val qualified = assertInstanceOf(DiagnosticCompilation.Qualified::class.java, result)
            assertTrue(qualified.batch.facts.isEmpty())
            assertTrue(qualified.coverage.analyzedFiles.isEmpty())
            assertEquals(
                listOf(DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE),
                qualified.coverage.limitations.map { it.reason },
            )
            assertEquals(listOf(file), qualified.coverage.limitations.map { it.file })
            assertInstanceOf(IntellijDiagnosticCompilationEvidence.Qualified::class.java, result.observation())
        }
    }

    @Test
    fun `unsupported analysis projection retains its finite qualification and phase`() = runBlocking {
        val observation = Phases()
        val result =
            compile(observation) {
                assertAnalysisEntry(observation)
                listOf(IntellijDiagnosticProjection.Rejected)
            }
        val qualified = assertInstanceOf(DiagnosticCompilation.Qualified::class.java, result)
        assertEquals(
            listOf(DiagnosticLimitationReason.UNSUPPORTED_DIAGNOSTIC),
            qualified.coverage.limitations.map { it.reason },
        )
        assertTrue(qualified.coverage.analyzedFiles.isEmpty())
    }

    @Test
    fun `analysis cancellation retains entered phase and propagates exact cancellation`() {
        for (cancelled in cancellations()) {
            val observation = Phases()
            val actual =
                assertThrows(RuntimeException::class.java) {
                    runBlocking {
                        compile(observation) {
                            assertAnalysisEntry(observation)
                            throw cancelled
                        }
                    }
                }
            assertSame(cancelled, actual)
            assertAnalysisEntry(observation)
        }
    }

    @Test
    fun `enumeration phase precedes indexed callback and exhausts actual allowance`() = runBlocking {
        val observation = Phases()
        var calls = 0
        val result =
            enumerate(observation) { collector ->
                assertEquals(listOf(IntellijReadPhase.DIAGNOSTIC_ENUMERATION), observation.values)
                calls++
                assertTrue(collector.accept { Refinement.Refined(file) })
            }
        assertEquals(1, calls)
        val exhausted = assertInstanceOf(DiagnosticEnumerationResult.Exhausted::class.java, result)
        assertEquals(listOf(file), exhausted.files)
        assertEquals(1L, exhausted.consumedWork.value)
    }

    @Test
    fun `enumeration native failure retains phase and closed rejection`() = runBlocking {
        for (failure in listOf(IllegalStateException("fixture"), LinkageError("fixture"))) {
            val observation = Phases()
            assertEquals(
                DiagnosticEnumerationResult.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE),
                enumerate(observation) {
                    assertEquals(listOf(IntellijReadPhase.DIAGNOSTIC_ENUMERATION), observation.values)
                    throw failure
                },
            )
            assertEquals(listOf(IntellijReadPhase.DIAGNOSTIC_ENUMERATION), observation.values)
        }
    }

    @Test
    fun `enumeration cancellation retains entered phase without success or failure substitution`() {
        for (cancelled in cancellations()) {
            val observation = Phases()
            val actual =
                assertThrows(RuntimeException::class.java) {
                    runBlocking {
                        enumerate(observation) {
                            assertEquals(listOf(IntellijReadPhase.DIAGNOSTIC_ENUMERATION), observation.values)
                            throw cancelled
                        }
                    }
                }
            assertSame(cancelled, actual)
            assertEquals(listOf(IntellijReadPhase.DIAGNOSTIC_ENUMERATION), observation.values)
        }
    }

    private suspend fun compile(
        observation: Phases,
        analyze: () -> List<IntellijDiagnosticProjection>,
    ): DiagnosticCompilation =
        guardedDiagnosticCompilation(observation) {
            diagnosticCompilationAttempt(scope) { collector ->
                collectDiagnosticAnalysis(file, collector, observation, analyze)
            }
        }

    private suspend fun enumerate(
        observation: Phases,
        collect: (BoundedDiagnosticEnumeration) -> Unit,
    ): DiagnosticEnumerationResult =
        guardedDiagnosticEnumeration(observation) {
            val allowance =
                DiagnosticEnumerationAllowance(
                    ResourceBudget(
                        ResultLimit.parse(2).refined(),
                        WorkUnitLimit.parse(2).refined(),
                        ElapsedTimeLimitMillis.parse(1000).refined(),
                    )
                ) {
                    0L
                }
            diagnosticEnumerationAttempt(
                request = DiagnosticEnumerationRequest.First(query),
                allowance = allowance,
                maximumBytes = 100000,
                collect = collect,
            )
        }

    private fun assertAnalysisEntry(observation: Phases) =
        assertEquals(
            listOf(IntellijReadPhase.DIAGNOSTIC_SCOPE, IntellijReadPhase.DIAGNOSTIC_ANALYSIS),
            observation.values,
        )

    private fun cancellations() = listOf(ProcessCanceledException(), CancellationException("fixture"))

    private class Phases : IntellijReadObservation {
        val values = mutableListOf<IntellijReadPhase>()

        override fun phase(value: IntellijReadPhase) {
            values += value
        }

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) = Unit

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }

    private fun <T> Refinement<T, *>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
