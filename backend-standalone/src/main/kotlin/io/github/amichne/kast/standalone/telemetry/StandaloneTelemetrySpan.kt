package io.github.amichne.kast.standalone.telemetry

import io.opentelemetry.api.trace.Span

internal class StandaloneTelemetrySpan internal constructor(
    private val telemetry: StandaloneTelemetry,
    private val scope: StandaloneTelemetryScope,
    private val span: Span?,
) {
    fun setAttribute(key: String, value: Any?) {
        if (span == null || value == null) {
            return
        }
        setAttribute(span, key, value)
    }

    fun addEvent(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
    ) {
        if (span == null || (verboseOnly && !telemetry.isVerbose(scope))) {
            return
        }
        span.addEvent(name, attributesOf(attributes))
    }

    inline fun <T> child(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
        block: (StandaloneTelemetrySpan) -> T,
    ): T = telemetry.inSpan(
        scope = scope,
        name = name,
        attributes = attributes,
        verboseOnly = verboseOnly,
        block = block,
    )

    companion object {
        fun disabled(
            telemetry: StandaloneTelemetry,
            scope: StandaloneTelemetryScope,
        ): StandaloneTelemetrySpan = StandaloneTelemetrySpan(
            telemetry = telemetry,
            scope = scope,
            span = null,
        )
    }
}
