package io.github.amichne.kast.workspace.spi

import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuntimeLivenessAdmissionTest {
    @Test
    fun `frozen EDT rejects before a semantic lease or operation can start`() = runBlocking {
        val timeout = EdtHeartbeatTimeout.parse(250).refinedValue()
        val frozen = RuntimeLivenessFailure.FrozenEventDispatchThread(timeout)
        var leaseAttempts = 0
        var operationAttempts = 0
        val executor = SemanticReadExecutor(
            runtimeLiveness = {
                RuntimeLivenessAdmission.Rejected(frozen)
            },
            authority = { _ ->
                leaseAttempts += 1
                error("semantic lease must not be attempted")
            },
        )

        val result = executor.current {
            operationAttempts += 1
            "must-not-run"
        }

        assertEquals(
            SemanticReadExecution.Rejected(
                SemanticReadAdmissionFailure.RuntimeUnavailable(frozen),
            ),
            result,
        )
        assertEquals(0, leaseAttempts)
        assertEquals(0, operationAttempts)
    }

    @Test
    fun `heartbeat timeout accepts only bounded positive milliseconds`() {
        assertEquals(
            EdtHeartbeatTimeoutFailure.NOT_POSITIVE,
            EdtHeartbeatTimeout.parse(0).rejectedFailure(),
        )
        assertEquals(
            EdtHeartbeatTimeoutFailure.EXCEEDS_MAXIMUM,
            EdtHeartbeatTimeout.parse(10_001).rejectedFailure(),
        )
        assertEquals(250, EdtHeartbeatTimeout.parse(250).refinedValue().milliseconds)
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejectedFailure(): Failure = when (this) {
        is Refinement.Refined -> error("Expected rejection, got $value")
        is Refinement.Rejected -> failure
    }
}
