package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SidecarTelemetryOutputTest {
    @Test
    fun `output is deterministic and owned by the exact socket state directory`() {
        val refinement = SidecarTelemetryOutput.fromSocketPath("/tmp/kast-runtime/kast-root.sock")

        val output = when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error(refinement.failure)
        }
        assertEquals(
            "/tmp/kast-runtime/kast-root.sock.state/otel",
            output.directoryPath.value,
        )
        assertEquals(
            "/tmp/kast-runtime/kast-root.sock.state/otel/traces.jsonl",
            output.traceFilePath.value,
        )
        assertEquals("otlp-json-lines-v1", output.format.identity)
    }

    @Test
    fun `relative socket path is rejected`() {
        val refinement = SidecarTelemetryOutput.fromSocketPath("kast-root.sock")

        val failure = when (refinement) {
            is Refinement.Refined -> error(refinement.value)
            is Refinement.Rejected -> refinement.failure
        }
        assertEquals(SidecarTelemetryOutputFailure.NOT_ABSOLUTE, failure)
    }
}
