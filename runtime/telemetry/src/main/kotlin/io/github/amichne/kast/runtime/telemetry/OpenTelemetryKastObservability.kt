package io.github.amichne.kast.runtime.telemetry

import io.github.amichne.kast.kernel.KastObservability
import io.github.amichne.kast.kernel.KastSpanCompletion
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
        }
    }
}

/*
 * Custom attribute ownership: Kast. Types are string/long. Values are closed enums or exact
 * non-negative counts; paths, selectors, declarations, hashes, and other request values are
 * prohibited. There is no applicable stable semantic convention for these domain outcomes.
 */
private val OUTCOME = AttributeKey.stringKey("io.github.amichne.kast.outcome")
private val FAILURE_TYPE = AttributeKey.stringKey("io.github.amichne.kast.failure.type")
private val FILE_COUNT = AttributeKey.longKey("io.github.amichne.kast.file.count")
private val RECORD_COUNT = AttributeKey.longKey("io.github.amichne.kast.record.count")
private val WORK_UNIT_COUNT = AttributeKey.longKey("io.github.amichne.kast.work.unit.count")

/** Stable OpenTelemetry registry attribute used only for escaped unexpected failures. */
private val ERROR_TYPE = AttributeKey.stringKey("error.type")

/** Stable exception-event type; exception messages and stack traces are excluded by policy. */
private val EXCEPTION_TYPE = AttributeKey.stringKey("exception.type")
