package io.github.amichne.kast.runtime.telemetry

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanCount
import io.github.amichne.kast.kernel.KastSpanFailure
import io.github.amichne.kast.kernel.KastSpanMeasurement
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class OpenTelemetryKastObservabilityTest {
    @Test
    fun `typed nested observations export stable shape without sensitive values`() = runTest {
        val capture = CapturingExporter()
        val telemetry = telemetry(capture)

        telemetry.inSpan(KastSpanName.TOPOLOGY_BUILD) { root ->
            root.child(KastSpanName.TOPOLOGY_EXTRACTION) { extraction ->
                extraction.observe(
                    KastSpanObservation(
                        KastSpanCompletion.Complete,
                        setOf(
                            KastSpanMeasurement.FileCount(KastSpanCount.parse(3).refined()),
                            KastSpanMeasurement.WorkUnitCount(KastSpanCount.parse(8).refined()),
                        ),
                    ),
                )
            }
            root.observe(
                KastSpanObservation(
                    KastSpanCompletion.Rejected(KastSpanFailure.TOPOLOGY_EXTRACTION),
                ),
            )
        }

        val root = capture.spans.single { it.name == "kast.topology.build" }
        val child = capture.spans.single { it.name == "kast.topology.extraction" }
        assertEquals(root.traceId, child.traceId)
        assertEquals(root.spanId, child.parentSpanId)
        assertEquals(
            "rejected",
            root.attributes.get(AttributeKey.stringKey("io.github.amichne.kast.outcome")),
        )
        assertEquals(
            "topology_extraction",
            root.attributes.get(AttributeKey.stringKey("io.github.amichne.kast.failure.type")),
        )
        assertEquals(StatusCode.UNSET, root.status.statusCode)
        assertFalse(root.attributes.asMap().keys.any { it.key == "error.type" })
        assertEquals(
            3L,
            child.attributes.get(AttributeKey.longKey("io.github.amichne.kast.file.count")),
        )
        assertEquals(
            8L,
            child.attributes.get(AttributeKey.longKey("io.github.amichne.kast.work.unit.count")),
        )
        assertFalse(capture.spans.toString().contains("/workspace"))
    }

    @Test
    fun `unexpected exception records exception event error type and error status`() {
        val capture = CapturingExporter()
        val telemetry = telemetry(capture)

        assertThrows(IllegalStateException::class.java) {
            runTest {
                telemetry.inSpan(KastSpanName.TRAVERSAL_RUN) {
                    throw IllegalStateException("escaped")
                }
            }
        }

        val span = capture.spans.single()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertEquals(
            IllegalStateException::class.java.name,
            span.attributes.get(AttributeKey.stringKey("error.type")),
        )
        val exception = span.events.single { it.name == "exception" }
        assertEquals(
            IllegalStateException::class.java.name,
            exception.attributes.get(AttributeKey.stringKey("exception.type")),
        )
        assertFalse(span.toString().contains("escaped"))
    }

    private fun telemetry(exporter: SpanExporter): KastObservability {
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val openTelemetry: OpenTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build()
        return OpenTelemetryKastObservability.create(openTelemetry)
    }

    private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.refined(): Value =
        (this as io.github.amichne.kast.kernel.Refinement.Refined).value
}

private class CapturingExporter : SpanExporter {
    val spans = CopyOnWriteArrayList<SpanData>()

    override fun export(values: MutableCollection<SpanData>): CompletableResultCode {
        spans += values
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
