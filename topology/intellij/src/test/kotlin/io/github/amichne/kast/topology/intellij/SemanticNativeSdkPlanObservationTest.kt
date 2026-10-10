package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Actual production plan scopes reach the public native observation capability. */
internal class SemanticNativeSdkPlanObservationTest : SemanticReadInputFixture() {
    private fun capture(work: Long, observation: IntellijReadObservation) =
        SemanticNativeFiles(
            ReadLimits.Default,
            DependencyCaptureBudget(
                ResourceBudget(
                    ResultLimit.parse(1).value(),
                    WorkUnitLimit.parse(work).value(),
                    ElapsedTimeLimitMillis.parse(100).value(),
                ),
                { now },
                { checkCanceled() },
                observation,
            ),
            SemanticNativeFileMemo(),
            SemanticNativeDocumentPort { SemanticNativeDocumentState.CLEAN },
        )

    @Test
    fun `completed and finite rejected plans both return while their typed cause remains distinct`() {
        val observation = Observation(counts)
        val file = SdkPlanTestFile("/sdk/empty")
        capture(2, observation).sdkRoots(listOf(file), SemanticInputDigest()).value()
        val rejected = capture(1, observation).sdkRoots(listOf(file), SemanticInputDigest())
        assertEquals(Refinement.Rejected(SemanticDependencyCaptureFailure.MINIMUM_HASH_WORK_UNAVAILABLE), rejected)
        val cause = (rejected as Refinement.Rejected).failure.termination()
        assertEquals(IntellijReadTermination.SEMANTIC_INPUT_MINIMUM_HASH_WORK_UNAVAILABLE, cause)
        observation.terminated(cause)
        assertEquals(
            listOf(IntellijReadCallOutcome.RETURNED, IntellijReadCallOutcome.RETURNED),
            observation.plans(),
        )
        assertEquals(1, file.opens)
        assertEquals(
            IntellijReadTermination.SEMANTIC_INPUT_FILE_CAPTURE_ADMISSION_CONSUMED,
            SemanticDependencyCaptureFailure.FILE_CAPTURE_ADMISSION_CONSUMED.termination(),
        )
    }

    @Test
    fun `original cancellation escapes the plan and records a drained cancelled call`() {
        val observation = Observation(counts)
        val cancelled = CancellationException("Owned native cancellation")
        checkCanceled = { throw cancelled }
        assertSame(
            cancelled,
            assertThrows(CancellationException::class.java) {
                capture(2, observation).sdkRoots(listOf(SdkPlanTestFile("/sdk/empty")), SemanticInputDigest())
            },
        )
        assertEquals(listOf(IntellijReadCallOutcome.CANCELLED), observation.plans())
    }

    private class Observation(base: IntellijReadObservation) : IntellijReadObservation by base {
        private data class Exit(val call: IntellijReadCall, val outcome: IntellijReadCallOutcome)

        private val exits = mutableListOf<Exit>()

        override fun enterCall(call: IntellijReadCall): IntellijReadCallScope =
            object : IntellijReadCallScope {
                override fun finish(outcome: IntellijReadCallOutcome) {
                    exits.add(Exit(call, outcome))
                }
            }

        fun plans() = exits.filter { it.call == IntellijReadCall.SDK_FILE_PLAN }.map { it.outcome }
    }
}
