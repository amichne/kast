package io.github.amichne.kast.runtime.telemetry

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
import io.github.amichne.kast.kernel.KastSpanEvent
import io.github.amichne.kast.kernel.KastSpanMeasurement
import io.github.amichne.kast.kernel.KastSpanName
import io.github.amichne.kast.kernel.KastSpanObservation
import io.github.amichne.kast.kernel.KastTraceSpan
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.concurrent.CancellationException

/** OpenTelemetry adapter over the host-neutral Kast trace contract. */
class OpenTelemetryKastObservability private constructor(
    private val tracer: Tracer,
) : KastObservability {
    override suspend fun <Value> inSpan(
        name: KastSpanName,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value = traced(name, Context.current(), operation)

    private suspend fun <Value> traced(
        name: KastSpanName,
        parent: Context,
        operation: suspend (KastTraceSpan) -> Value,
    ): Value {
        val span = tracer.spanBuilder(name.wireName)
            .setParent(parent)
            .startSpan()
        val context = parent.with(span)
        val scope = OpenTelemetryKastSpan(this, span, context)
        return try {
            operation(scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            span.setAttribute(ERROR_TYPE, failure.javaClass.name)
            span.addEvent(
                "exception",
                Attributes.of(EXCEPTION_TYPE, failure.javaClass.name),
            )
            span.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            span.end()
        }
    }

    companion object {
        private const val INSTRUMENTATION_SCOPE = "io.github.amichne.kast"

        /** Uses the agent-installed global provider, or the standard no-op provider when absent. */
        fun global(): KastObservability = create(GlobalOpenTelemetry.getOrNoop())

        fun create(openTelemetry: OpenTelemetry): KastObservability =
            OpenTelemetryKastObservability(openTelemetry.getTracer(INSTRUMENTATION_SCOPE))
    }

    private class OpenTelemetryKastSpan(
        private val telemetry: OpenTelemetryKastObservability,
        private val span: Span,
        private val context: Context,
    ) : KastTraceSpan {
        override suspend fun <Value> child(
            name: KastSpanName,
            operation: suspend (KastTraceSpan) -> Value,
        ): Value = telemetry.traced(name, context, operation)

        override fun observe(observation: KastSpanObservation) {
            when (val completion = observation.completion) {
                KastSpanCompletion.Complete -> span.setAttribute(OUTCOME, "complete")
                KastSpanCompletion.Qualified -> span.setAttribute(OUTCOME, "qualified")
                is KastSpanCompletion.Rejected -> {
                    span.setAttribute(OUTCOME, "rejected")
                    span.setAttribute(
                        FAILURE_TYPE,
                        completion.failure.name.lowercase(),
                    )
                }
            }
            observation.measurements.forEach { measurement ->
                when (measurement) {
                    is KastSpanMeasurement.FileCount ->
                        span.setAttribute(FILE_COUNT, measurement.count.value)
                    is KastSpanMeasurement.RecordCount ->
                        span.setAttribute(RECORD_COUNT, measurement.count.value)
                    is KastSpanMeasurement.WorkUnitCount ->
                        span.setAttribute(WORK_UNIT_COUNT, measurement.count.value)
                }
            }
            observation.events.forEach { event ->
                when (event) {
                    is KastSpanEvent.TopologyIdentityMismatch -> span.addEvent(
                        TOPOLOGY_IDENTITY_MISMATCH_EVENT,
                        event.attributes(),
                    )
                }
            }
        }
    }
}

private fun KastSpanEvent.TopologyIdentityMismatch.attributes(): Attributes =
    Attributes.builder()
        .put(TOPOLOGY_IDENTITY_STAGE, stage.name.lowercase())
        .put(TOPOLOGY_CACHE_DISPOSITION, cacheDisposition.name.lowercase())
        .put(SOURCE_FILE, sourceFile)
        .put(SOURCE_OCCURRENCE_START, sourceOccurrence.startInclusive.toLong())
        .put(SOURCE_OCCURRENCE_END, sourceOccurrence.endExclusive.toLong())
        .put(TARGET_FILE, targetFile)
        .put(TARGET_DECLARATION_START, targetDeclaration.startInclusive.toLong())
        .put(TARGET_DECLARATION_END, targetDeclaration.endExclusive.toLong())
        .put(REGISTRY_SYMBOL_KIND, registryProjection.kind.name.lowercase())
        .put(LIVE_SYMBOL_KIND, liveProjection.kind.name.lowercase())
        .put(REGISTRY_QUALIFIED_IDENTITY, registryProjection.qualifiedIdentity)
        .put(LIVE_QUALIFIED_IDENTITY, liveProjection.qualifiedIdentity)
        .put(REGISTRY_IDENTITY, registryProjection.compilerIdentity)
        .put(LIVE_IDENTITY, liveProjection.compilerIdentity)
        .put(QUALIFIED_IDENTITY_SAME, qualifiedIdentitySame)
        .put(SIGNATURE_SAME, signatureSame)
        .put(REGISTRY_SIGNATURE, registryProjection.canonicalSignature)
        .put(LIVE_SIGNATURE, liveProjection.canonicalSignature)
        .put(
            PROJECTION_DELTA,
            delta.sortedBy { component -> component.ordinal }
                .map { component -> component.name.lowercase() },
        )
        .put(LIVE_SYMBOL_RUNTIME_KIND, liveSymbolRuntimeType)
        .put(PSI_DECLARATION_RUNTIME_KIND, psiDeclarationRuntimeType)
        .build()

/*
 * Custom attribute ownership: Kast. Ordinary span values are closed enums or exact non-negative
 * counts. The one topology mismatch event additionally carries admitted workspace-relative paths,
 * detached compiler projections, and exact ranges; it never carries source text. These event-only
 * values are prohibited as metric dimensions. There is no applicable stable semantic convention
 * for compiler-identity comparison outcomes.
 */
private val OUTCOME = AttributeKey.stringKey("io.github.amichne.kast.outcome")
private val FAILURE_TYPE = AttributeKey.stringKey("io.github.amichne.kast.failure.type")
private val FILE_COUNT = AttributeKey.longKey("io.github.amichne.kast.file.count")
private val RECORD_COUNT = AttributeKey.longKey("io.github.amichne.kast.record.count")
private val WORK_UNIT_COUNT = AttributeKey.longKey("io.github.amichne.kast.work.unit.count")

private const val TOPOLOGY_IDENTITY_MISMATCH_EVENT = "kast.topology.identity.mismatch"
private val TOPOLOGY_IDENTITY_STAGE =
    AttributeKey.stringKey("io.github.amichne.kast.topology.identity.stage")
private val TOPOLOGY_CACHE_DISPOSITION =
    AttributeKey.stringKey("io.github.amichne.kast.topology.cache.disposition")
private val SOURCE_FILE = AttributeKey.stringKey("io.github.amichne.kast.source.file")
private val SOURCE_OCCURRENCE_START =
    AttributeKey.longKey("io.github.amichne.kast.source.occurrence.start")
private val SOURCE_OCCURRENCE_END =
    AttributeKey.longKey("io.github.amichne.kast.source.occurrence.end")
private val TARGET_FILE = AttributeKey.stringKey("io.github.amichne.kast.target.file")
private val TARGET_DECLARATION_START =
    AttributeKey.longKey("io.github.amichne.kast.target.declaration.start")
private val TARGET_DECLARATION_END =
    AttributeKey.longKey("io.github.amichne.kast.target.declaration.end")
private val REGISTRY_SYMBOL_KIND =
    AttributeKey.stringKey("io.github.amichne.kast.registry.symbol.kind")
private val LIVE_SYMBOL_KIND = AttributeKey.stringKey("io.github.amichne.kast.live.symbol.kind")
private val REGISTRY_QUALIFIED_IDENTITY =
    AttributeKey.stringKey("io.github.amichne.kast.registry.qualified.identity")
private val LIVE_QUALIFIED_IDENTITY =
    AttributeKey.stringKey("io.github.amichne.kast.live.qualified.identity")
private val REGISTRY_IDENTITY = AttributeKey.stringKey("io.github.amichne.kast.registry.identity")
private val LIVE_IDENTITY = AttributeKey.stringKey("io.github.amichne.kast.live.identity")
private val QUALIFIED_IDENTITY_SAME =
    AttributeKey.booleanKey("io.github.amichne.kast.qualified.identity.same")
private val SIGNATURE_SAME = AttributeKey.booleanKey("io.github.amichne.kast.signature.same")
private val REGISTRY_SIGNATURE = AttributeKey.stringKey("io.github.amichne.kast.registry.signature")
private val LIVE_SIGNATURE = AttributeKey.stringKey("io.github.amichne.kast.live.signature")
private val PROJECTION_DELTA = AttributeKey.stringArrayKey("io.github.amichne.kast.projection.delta")
private val LIVE_SYMBOL_RUNTIME_KIND =
    AttributeKey.stringKey("io.github.amichne.kast.live.symbol.runtime.kind")
private val PSI_DECLARATION_RUNTIME_KIND =
    AttributeKey.stringKey("io.github.amichne.kast.psi.declaration.runtime.kind")

/** Stable OpenTelemetry registry attribute used only for escaped unexpected failures. */
private val ERROR_TYPE = AttributeKey.stringKey("error.type")

/** Stable exception-event type; exception messages and stack traces are excluded by policy. */
private val EXCEPTION_TYPE = AttributeKey.stringKey("exception.type")
