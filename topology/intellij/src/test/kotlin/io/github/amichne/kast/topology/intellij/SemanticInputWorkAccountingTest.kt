package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.io.IOException
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Counts the production byte-hashing effect using owned streams; establishes no native VFS claim. */
class SemanticInputWorkAccountingTest {
    @Test
    fun `completed hash counts reads including EOF and preserves the independently known digest`() {
        val observation = Observation()
        val result = hashSemanticInput("abc".byteInputStream(), budget(2, observation)) as Refinement.Refined
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.value.value)
        assertEquals(2, observation.reads)
        assertEquals(3L, observation.bytes)
        assertEquals(1L, observation.completed)
        assertEquals(listOf(IntellijReadCallOutcome.RETURNED, IntellijReadCallOutcome.RETURNED), observation.outcomes)
    }

    @Test
    fun `partial rejection records actual bytes and repeated attempts accumulate physical reads`() {
        val observation = Observation()
        repeat(2) {
            assertEquals(
                Refinement.Rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED),
                hashSemanticInput(ByteArray(65536).inputStream(), budget(1, observation)),
            )
        }
        assertEquals(2, observation.reads)
        assertEquals(16384L, observation.bytes)
        assertEquals(0L, observation.completed)
    }

    @Test
    fun `input failure records attempted read without inventing bytes or completed hashes`() {
        val observation = Observation()
        val input =
            object : InputStream() {
                override fun read(): Int = throw IOException("owned input failure")
            }
        assertThrows<IOException> { hashSemanticInput(input, budget(2, observation)) }
        assertEquals(1, observation.reads)
        assertEquals(0L, observation.bytes)
        assertEquals(0L, observation.completed)
        assertEquals(listOf(IntellijReadCallOutcome.FAILED), observation.outcomes)
    }

    private fun budget(work: Long, observation: Observation) =
        DependencyCaptureBudget(
            ResourceBudget(
                (ResultLimit.parse(1) as Refinement.Refined).value,
                (WorkUnitLimit.parse(work) as Refinement.Refined).value,
                (ElapsedTimeLimitMillis.parse(1000) as Refinement.Refined).value,
            ),
            { 0L },
            {},
            observation,
        )

    private class Observation : IntellijReadObservation {
        var reads = 0
        var bytes = 0L
        var completed = 0L
        val outcomes = mutableListOf<IntellijReadCallOutcome>()

        override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
            assertEquals(IntellijReadCall.FILE_STREAM_READ, call)
            reads++
            return object : IntellijReadCallScope {
                override fun finish(outcome: IntellijReadCallOutcome) {
                    outcomes += outcome
                }
            }
        }

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            when (counter) {
                IntellijReadCounter.DEPENDENCY_HASH_BYTES_READ -> bytes += amount
                IntellijReadCounter.DEPENDENCY_HASHES_COMPLETED -> completed += amount
                else -> error("Unexpected production counter: $counter")
            }
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }
}
