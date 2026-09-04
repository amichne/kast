package io.github.amichne.kast.runtime.telemetry

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanCount
import io.github.amichne.kast.kernel.KastSpanFailure
import io.github.amichne.kast.kernel.KastSpanMeasurement
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.KastSpanEvent
import io.github.amichne.kast.kernel.KastTopologyCacheDisposition
import io.github.amichne.kast.kernel.KastTopologyCompilerProjection
import io.github.amichne.kast.kernel.KastTopologyCompilerProjectionComponent
import io.github.amichne.kast.kernel.KastTopologyCompilerSymbolKind
import io.github.amichne.kast.kernel.KastTopologyIdentityStage
import io.github.amichne.kast.kernel.KastTopologySourceRange
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
    fun `compiler identity mismatch exports one structured diagnostic event`() = runTest {
        val capture = CapturingExporter()
        val telemetry = telemetry(capture)
        val event = KastSpanEvent.TopologyIdentityMismatch(
            stage = KastTopologyIdentityStage.REFERENCE_TARGET,
            cacheDisposition = KastTopologyCacheDisposition.COMPUTED,
            sourceFile = "events/EventConsumer.kt",
            sourceOccurrence = KastTopologySourceRange(41, 54),
            targetFile = "events/Event.kt",
            targetDeclaration = KastTopologySourceRange(8, 31),
            registryProjection = KastTopologyCompilerProjection(
                kind = KastTopologyCompilerSymbolKind.FUNCTION,
                qualifiedIdentity = "sample.consume",
                canonicalSignature = "registry-signature",
                compilerIdentity = "registry-identity",
            ),
            liveProjection = KastTopologyCompilerProjection(
                kind = KastTopologyCompilerSymbolKind.FUNCTION,
                qualifiedIdentity = "sample.consume",
                canonicalSignature = "live-signature",
                compilerIdentity = "live-identity",
            ),
            liveSymbolRuntimeType = "KaNamedFunctionSymbol",
            psiDeclarationRuntimeType = "KtNamedFunction",
            delta = setOf(
                KastTopologyCompilerProjectionComponent.VALUE_PARAMETERS,
                KastTopologyCompilerProjectionComponent.IDENTITY,
            ),
        )

        telemetry.inSpan(KastSpanName.TOPOLOGY_EXTRACTION) { span ->
            span.observe(
                KastSpanObservation(
                    completion = KastSpanCompletion.Rejected(
                        KastSpanFailure.TOPOLOGY_EXTRACTION,
                    ),
                    events = setOf(event),
                ),
            )
        }

        val span = capture.spans.single()
        val mismatch = span.events.single()
        assertEquals("kast.topology.identity.mismatch", mismatch.name)
        assertEquals(
            "reference_target",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.topology.identity.stage"),
            ),
        )
        assertEquals(
            "computed",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.topology.cache.disposition"),
            ),
        )
        assertEquals(
            "events/EventConsumer.kt",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.source.file"),
            ),
        )
        assertEquals(
            41L,
            mismatch.attributes.get(
                AttributeKey.longKey("io.github.amichne.kast.source.occurrence.start"),
            ),
        )
        assertEquals(
            54L,
            mismatch.attributes.get(
                AttributeKey.longKey("io.github.amichne.kast.source.occurrence.end"),
            ),
        )
        assertEquals(
            "events/Event.kt",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.target.file"),
            ),
        )
        assertEquals(
            8L,
            mismatch.attributes.get(
                AttributeKey.longKey("io.github.amichne.kast.target.declaration.start"),
            ),
        )
        assertEquals(
            31L,
            mismatch.attributes.get(
                AttributeKey.longKey("io.github.amichne.kast.target.declaration.end"),
            ),
        )
        assertEquals(
            "function",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.registry.symbol.kind"),
            ),
        )
        assertEquals(
            "function",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.live.symbol.kind"),
            ),
        )
        assertEquals(
            "sample.consume",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.registry.qualified.identity"),
            ),
        )
        assertEquals(
            "sample.consume",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.live.qualified.identity"),
            ),
        )
        assertEquals(
            "registry-identity",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.registry.identity"),
            ),
        )
        assertEquals(
            "live-identity",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.live.identity"),
            ),
        )
        assertEquals(
            true,
            mismatch.attributes.get(
                AttributeKey.booleanKey("io.github.amichne.kast.qualified.identity.same"),
            ),
        )
        assertEquals(
            false,
            mismatch.attributes.get(
                AttributeKey.booleanKey("io.github.amichne.kast.signature.same"),
            ),
        )
        assertEquals(
            "registry-signature",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.registry.signature"),
            ),
        )
        assertEquals(
            "live-signature",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.live.signature"),
            ),
        )
        assertEquals(
            listOf("value_parameters", "identity"),
            mismatch.attributes.get(
                AttributeKey.stringArrayKey("io.github.amichne.kast.projection.delta"),
            ),
        )
        assertEquals(
            "KaNamedFunctionSymbol",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.live.symbol.runtime.kind"),
            ),
        )
        assertEquals(
            "KtNamedFunction",
            mismatch.attributes.get(
                AttributeKey.stringKey("io.github.amichne.kast.psi.declaration.runtime.kind"),
            ),
        )
        assertEquals(StatusCode.UNSET, span.status.statusCode)
        assertFalse(span.toString().contains("fun leaked"))
    }

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
