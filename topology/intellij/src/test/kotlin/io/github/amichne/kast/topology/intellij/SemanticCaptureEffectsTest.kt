package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class SemanticCaptureEffectsTest : SemanticReadInputFixture() {
    @Test
    fun `boundary preserves the graph success and each finite rejection by identity`() {
        val captured = Refinement.Refined(graph)
        assertSame(captured, observeSemanticInputCapture(ReadLimits.Default, counts) { captured })
        for (cause in SemanticDependencyCaptureFailure.entries) {
            val rejection = Refinement.Rejected(cause)
            assertSame(rejection, observeSemanticInputCapture(ReadLimits.Default, counts) { rejection })
        }
    }

    @Test
    fun `IO failure is finite input unavailability before memo ownership`() {
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE),
            observeSemanticInputCapture<Nothing>(ReadLimits.Default, counts) { throw IOException("Owned input") },
        )
    }

    @Test
    fun `unchecked input failure emits one bounded typed signal and preserves finite cause`() {
        val signals = mutableListOf<IntellijReadUnexpectedFailure>()
        val observation =
            object : IntellijReadObservation by counts {
                override fun unexpected(failure: IntellijReadUnexpectedFailure) {
                    signals += failure
                }
            }
        val failure = IllegalStateException("No source payload is retained")
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE),
            observeSemanticInputCapture<Nothing>(ReadLimits.Default, observation) { throw failure },
        )
        assertEquals(1, signals.size)
        assertEquals(IntellijReadStage.SEMANTIC_DEPENDENCY_PREPARATION, signals.single().stage)
        assertEquals(IntellijReadUnexpectedKind.RUNTIME, signals.single().kind)
        assertEquals(failure.javaClass.name, signals.single().exceptionType)
    }

    @Test
    fun `linkage rejection keeps its compiler failure and bounded signal kind`() {
        val signals = mutableListOf<IntellijReadUnexpectedFailure>()
        val observation =
            object : IntellijReadObservation by counts {
                override fun unexpected(failure: IntellijReadUnexpectedFailure) {
                    signals += failure
                }
            }
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE),
            observeSemanticInputCapture<Nothing>(ReadLimits.Default, observation) {
                throw LinkageError("Owned missing provider")
            },
        )
        assertEquals(1, signals.size)
        assertEquals(IntellijReadUnexpectedKind.LINKAGE, signals.single().kind)
        assertEquals(IntellijReadStage.SEMANTIC_DEPENDENCY_PREPARATION, signals.single().stage)
    }

    @Test
    fun `native and coroutine cancellation preserve throwable identity and assertion failures escape`() {
        for (cancelled in listOf(ProcessCanceledException(), CancellationException("Preempted"))) {
            assertSame(
                cancelled,
                assertThrows(cancelled.javaClass) {
                    observeSemanticInputCapture<Nothing>(ReadLimits.Default, counts) { throw cancelled }
                },
            )
        }
        val failedFixture = AssertionError("Unexpected fixture effect")
        assertSame(
            failedFixture,
            assertThrows(AssertionError::class.java) {
                observeSemanticInputCapture<Nothing>(ReadLimits.Default, counts) { throw failedFixture }
            },
        )
    }
}
