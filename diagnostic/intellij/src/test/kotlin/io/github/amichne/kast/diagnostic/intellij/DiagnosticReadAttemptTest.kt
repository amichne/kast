package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationStop
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
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Production attempt blocks with simulated interruption/re-entry; no IDE scheduling or K2 session is simulated. */
class DiagnosticReadAttemptTest {
    private val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(19).refined(),
        )
    private val scope =
        DiagnosticScope.fromCanonicalPaths(lease, listOf(Path.of("/workspace/A.kt"), Path.of("/workspace/B.kt")))
            .refined()
    private val query = DiagnosticScopeQuery.parse(lease, ".").refined()

    @Test
    fun `compiler attempt interruption discards accepted facts and coverage before clean retry`() {
        for (cancelled in cancellations()) {
            val observed =
                assertThrows(RuntimeException::class.java) {
                    runBlocking {
                        guardedDiagnosticCompilation {
                            diagnosticCompilationAttempt(scope) { collector ->
                                assertEquals(IntellijDiagnosticCollectionAdmission.ACCEPTED, collector.accept(fact(1)))
                                collector.recordAnalyzed(scope.files.first())
                                collector.recordLimitation(scope.files.last(), DiagnosticLimitationReason.INDEXING)
                                throw cancelled
                            }
                        }
                    }
                }
            assertSame(cancelled, observed)
            val clean = runBlocking {
                guardedDiagnosticCompilation {
                    diagnosticCompilationAttempt(scope) { collector ->
                        collector.accept(fact(2))
                        scope.files.forEach { collector.recordAnalyzed(it) }
                    }
                }
            }
            val complete = assertInstanceOf(DiagnosticCompilation.Complete::class.java, clean)
            assertEquals(listOf(2), complete.batch.facts.map { it.location.range.start.value })
            assertEquals(scope.files, complete.coverage.analyzedFiles)
        }
    }

    @Test
    fun `enumeration attempt interruption discards identities but keeps consumed work`() {
        for (cancelled in cancellations()) {
            val request = DiagnosticEnumerationRequest.First(query)
            val allowance = allowance()
            val observed =
                assertThrows(RuntimeException::class.java) {
                    runBlocking {
                        guardedDiagnosticEnumeration {
                            diagnosticEnumerationAttempt(request, allowance, 100000) { collector ->
                                assertTrue(collector.accept { Refinement.Refined(scope.files.first()) })
                                throw cancelled
                            }
                        }
                    }
                }
            assertSame(cancelled, observed)
            val clean = runBlocking {
                guardedDiagnosticEnumeration {
                    diagnosticEnumerationAttempt(request, allowance, 100000) { collector ->
                        assertTrue(collector.accept { Refinement.Refined(scope.files.last()) })
                    }
                }
            }
            assertEquals(
                listOf(scope.files.last()),
                assertInstanceOf(DiagnosticEnumerationResult.Exhausted::class.java, clean).files,
            )
            var classified = false
            val exhausted =
                diagnosticEnumerationAttempt(request, allowance, 100000) { collector ->
                    assertFalse(
                        collector.accept {
                            classified = true
                            Refinement.Refined(scope.files.first())
                        }
                    )
                }
            assertFalse(classified)
            assertEquals(
                DiagnosticEnumerationFailure.IncreaseGrant(DiagnosticEnumerationStop.WORK_LIMIT),
                assertInstanceOf(DiagnosticEnumerationResult.Rejected::class.java, exhausted).failure,
            )
        }
    }

    @Test
    fun `unexpected compiler and enumeration attempt failures retain finite refusals`() = runBlocking {
        assertEquals(
            DiagnosticCompilation.Rejected(DiagnosticCompilerRejection.WORKSPACE_INDEX_UNAVAILABLE),
            guardedDiagnosticCompilation {
                diagnosticCompilationAttempt(scope) { throw IllegalStateException("fixture") }
            },
        )
        assertEquals(
            DiagnosticEnumerationResult.Rejected(DiagnosticScopeResolutionFailure.UNAVAILABLE),
            guardedDiagnosticEnumeration {
                diagnosticEnumerationAttempt(DiagnosticEnumerationRequest.First(query), allowance(), 100000) {
                    throw LinkageError("fixture")
                }
            },
        )
    }

    private fun cancellations(): List<RuntimeException> =
        listOf(ProcessCanceledException(), CancellationException("fixture"))

    private fun allowance() =
        DiagnosticEnumerationAllowance(
            ResourceBudget(
                ResultLimit.parse(2).refined(),
                WorkUnitLimit.parse(2).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            )
        ) {
            0L
        }

    private fun fact(offset: Int) =
        DiagnosticFact.fromBoundary(
                scope,
                scope.files.first(),
                offset,
                offset + 1,
                DiagnosticSeverity.ERROR,
                "REPEATED",
                "same message",
            )
            .refined()

    private fun <T> Refinement<T, *>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}
