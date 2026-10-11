package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class HostedCallbackDependencyAttemptsTest : HostedScopedCaptureFixture() {
    @Test
    fun `unmodeled cache universe records its typed rejection without starting capture`() {
        val counters = mutableListOf<IntellijReadCounter>()
        val reasons = mutableListOf<IntellijReadTermination>()
        val observation =
            object : IntellijReadObservation {
                override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
                    repeat(amount) { counters += counter }
                }

                override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
                    reasons += reason
                }
            }
        val attempts = HostedCallbackDependencyAttempts(observation, emptySet())
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.DEPENDENCY_MODULE_UNMODELED),
            attempts.capture(HostedCallbackDependencyUniverse.Forward(main)) {
                throw AssertionError("Unmodeled attribution cannot start native capture")
            },
        )
        assertEquals(listOf(IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS), counters)
        assertEquals(listOf(IntellijReadTermination.SEMANTIC_INPUT_DEPENDENCY_MODULE_UNMODELED), reasons)
    }

    @Test
    fun `allowed universes are copied and foreign modules cannot start an effect`() {
        val known = HostedCallbackDependencyUniverse.Forward(main)
        val unknown = HostedCallbackDependencyUniverse.Forward(dependency)
        val allowed = mutableSetOf<HostedCallbackDependencyUniverse>(known)
        val attempts = HostedCallbackDependencyAttempts(counts, allowed)
        allowed.clear()
        allowed += unknown
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.DEPENDENCY_MODULE_UNMODELED),
            attempts.capture(unknown) { throw AssertionError("Foreign module cannot capture") },
        )
        val rejection = Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
        assertSame(rejection, attempts.capture(known) { rejection })
        assertEquals(
            rejection,
            attempts.capture(known) { throw AssertionError("Known unavailable universe cannot capture") },
        )
    }

    @Test
    fun `every typed capture rejection skips subsequent effects and preserves its cause`() {
        for (cause in SemanticDependencyCaptureFailure.entries) {
            val attempts = HostedCallbackDependencyAttempts(counts)
            val rejected = Refinement.Rejected(cause)
            assertSame(rejected, attempts.capture { rejected })
            repeat(3) {
                assertEquals(rejected, attempts.capture { error("Unavailable preparation must not run again") })
            }
        }
        assertEquals(
            SemanticDependencyCaptureFailure.entries.size,
            counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS],
        )
        assertEquals(
            SemanticDependencyCaptureFailure.entries.size,
            counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS],
        )
        assertEquals(
            SemanticDependencyCaptureFailure.entries.size * 3,
            counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_PREPARATIONS_SKIPPED],
        )
    }

    @Test
    fun `successful capture is never reused even with the same authority`() {
        val attempts = HostedCallbackDependencyAttempts(counts)
        val authority = owner.admit()
        val original = Refinement.Refined(snapshot(authority))
        val changed = Refinement.Refined(snapshot(authority, 'b'))
        assertSame(original, attempts.capture { original })
        assertSame(changed, attempts.capture { changed })
        assertEquals(2, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_PREPARATIONS_SKIPPED])
    }

    @Test
    fun `rejection after success disables further attempts without retaining that success`() {
        val attempts = HostedCallbackDependencyAttempts(counts)
        val captured = Refinement.Refined(snapshot(owner.admit()))
        val rejection = Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
        assertSame(captured, attempts.capture { captured })
        assertSame(rejection, attempts.capture { rejection })
        assertEquals(rejection, attempts.capture { error("A prior success cannot bypass the rejection") })
        assertEquals(2, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS])
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS])
        assertEquals(1, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_PREPARATIONS_SKIPPED])
    }

    @Test
    fun `a fresh request owns a fresh preparation decision`() {
        val original = HostedCallbackDependencyAttempts(counts)
        val rejection = Refinement.Rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED)
        assertSame(rejection, original.capture { rejection })
        val next = HostedCallbackDependencyAttempts(counts)
        val captured = Refinement.Refined(snapshot(owner.admit()))
        assertSame(captured, next.capture { captured })
        assertEquals(rejection, original.capture { error("New request must not reactivate the original") })
        assertEquals(2, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS])
    }

    @Test
    fun `cancellation propagates and restart can attempt capture again`() {
        val attempts = HostedCallbackDependencyAttempts(counts)
        val cancelled = CancellationException("Read preempted")
        assertSame(cancelled, assertThrows(CancellationException::class.java) { attempts.capture { throw cancelled } })
        val captured = Refinement.Refined(snapshot(owner.admit()))
        assertSame(captured, attempts.capture { captured })
        assertEquals(2, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_REJECTIONS])
        assertEquals(0, counts[IntellijReadCounter.SEMANTIC_FACT_DEPENDENCY_PREPARATIONS_SKIPPED])
    }
}
