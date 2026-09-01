package io.github.amichne.kast.runtime.telemetry

import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointSocketDirectory
import io.github.amichne.kast.protocol.wire.metadata.IdeRuntimeEpoch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenTelemetryFileForwardingTest {
    @Test
    fun `one socket forwards spans as private OTLP JSON lines by default`() = runTest {
        val output = output()
        try {
            val opening = OpenTelemetryFileForwarding.open(output)
            val forwarding = (opening as OpenTelemetryFileForwardingOpening.Opened).forwarding

            forwarding.observability.inSpan(KastSpanName.TOPOLOGY_BUILD) { span ->
                span.observe(KastSpanObservation(KastSpanCompletion.Complete))
            }
            val flush = forwarding.forceFlush().join(10, TimeUnit.SECONDS)
            assertTrue(flush.isSuccess)

            val directory = Path.of(output.directoryPath.value)
            val traceFile = Path.of(output.traceFilePath.value)
            assertTrue(Files.isDirectory(directory))
            assertTrue(Files.isRegularFile(traceFile))
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(directory),
            )
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(traceFile),
            )
            val document = Files.readString(traceFile)
            assertTrue(document.contains("\"resourceSpans\""))
            assertTrue(document.contains("kast.topology.build"))
            assertTrue(document.contains("io.github.amichne.kast.outcome"))
            assertFalse(document.contains("/workspace/private"))
            forwarding.shutdown().join(10, TimeUnit.SECONDS)
        } finally {
            deleteOutput(output)
        }
    }

    @Test
    fun `occupied output directory is a finite startup rejection`() {
        val output = output()
        val directory = Path.of(output.directoryPath.value)
        try {
            Files.writeString(directory, "occupied")

            val opening = OpenTelemetryFileForwarding.open(output)

            assertEquals(
                OpenTelemetryFileForwardingFailure.DIRECTORY_UNAVAILABLE,
                (opening as OpenTelemetryFileForwardingOpening.Rejected).failure,
            )
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    private fun output() = location().telemetryOutput(epoch(7))

    private fun location(): IdeEndpointLocation {
        val root = refined(
            IdeEndpointCanonicalRoot.parse("/workspace/otel-${UUID.randomUUID()}"),
        )
        return refined(
            IdeEndpointLocation.locate(
                refined(IdeEndpointSocketDirectory.parse("/tmp")),
                root,
            ),
        )
    }

    private fun epoch(raw: Long): IdeRuntimeEpoch = refined(IdeRuntimeEpoch.parse(raw))

    private fun deleteOutput(output: io.github.amichne.kast.protocol.wire.metadata.IdeEndpointTelemetryOutput) {
        Files.deleteIfExists(Path.of(output.traceFilePath.value))
        Files.deleteIfExists(Path.of(output.directoryPath.value))
    }

    private fun <Value, Failure> refined(value: Refinement<Value, Failure>): Value = when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("fixture rejected: ${value.failure}")
    }
}
