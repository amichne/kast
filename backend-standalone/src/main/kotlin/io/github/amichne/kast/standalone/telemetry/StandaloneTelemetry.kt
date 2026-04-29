package io.github.amichne.kast.standalone.telemetry

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.workspaceDataDirectory
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

internal class StandaloneTelemetry private constructor(
    private val config: StandaloneTelemetryConfig?,
    private val tracer: Tracer?,
) {
    fun isEnabled(scope: StandaloneTelemetryScope): Boolean = config != null && scope in config.scopes

    fun isVerbose(scope: StandaloneTelemetryScope): Boolean = isEnabled(scope) && config?.detail == StandaloneTelemetryDetail.VERBOSE

    inline fun <T> inSpan(
        scope: StandaloneTelemetryScope,
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
        block: (StandaloneTelemetrySpan) -> T,
    ): T {
        if (!isEnabled(scope) || (verboseOnly && !isVerbose(scope))) {
            return block(StandaloneTelemetrySpan.disabled(this, scope))
        }

        val startedSpan = checkNotNull(tracer).spanBuilder(name).startSpan()
        applyAttributes(startedSpan, attributes)
        val otelScope = startedSpan.makeCurrent()
        val telemetrySpan = StandaloneTelemetrySpan(
            telemetry = this,
            scope = scope,
            span = startedSpan,
        )

        return try {
            block(telemetrySpan)
        } catch (failure: Throwable) {
            startedSpan.recordException(failure)
            startedSpan.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            otelScope.close()
            startedSpan.end()
        }
    }

    companion object {
        fun disabled(): StandaloneTelemetry = StandaloneTelemetry(
            config = null,
            tracer = null,
        )

        fun create(config: StandaloneTelemetryConfig): StandaloneTelemetry {
            if (!config.enabled || config.scopes.isEmpty()) {
                return disabled()
            }

            val exporter = JsonLineSpanExporter(
                outputFile = config.outputFile,
                detail = config.detail,
            )
            val tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
            val openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()

            return StandaloneTelemetry(
                config = config,
                tracer = openTelemetry.getTracer("io.github.amichne.kast.standalone"),
            )
        }

        fun fromConfig(
            workspaceRoot: Path,
            config: KastConfig = KastConfig.load(workspaceRoot),
        ): StandaloneTelemetry {
            if (!config.telemetry.enabled) {
                return disabled()
            }

            val scopes = if (config.telemetry.scopes.equals("all", ignoreCase = true)) {
                StandaloneTelemetryScope.entries.toSet()
            } else {
                parseScopes(config.telemetry.scopes) ?: StandaloneTelemetryScope.entries.toSet()
            }
            val detail = StandaloneTelemetryDetail.parse(config.telemetry.detail)
            val outputFile = resolveOutputFile(
                rawValue = config.telemetry.outputFile,
                workspaceRoot = workspaceRoot,
            )

            return create(
                StandaloneTelemetryConfig(
                    enabled = true,
                    scopes = scopes,
                    detail = detail,
                    outputFile = outputFile,
                ),
            )
        }

        private fun parseScopes(rawValue: String?): Set<StandaloneTelemetryScope>? {
            if (rawValue.isNullOrBlank()) {
                return null
            }

            val scopes = rawValue.split(',')
                .mapNotNull(StandaloneTelemetryScope::parse)
                .toSet()
            return scopes.ifEmpty { null }
        }

        private fun resolveOutputFile(rawValue: String?, workspaceRoot: Path): Path {
            val configuredPath = rawValue
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let { path -> if (path.isAbsolute) path else workspaceRoot.resolve(path) }

            return (configuredPath ?: workspaceDataDirectory(workspaceRoot).resolve("telemetry/standalone-spans.jsonl"))
                .toAbsolutePath()
                .normalize()
        }
    }
}

private class JsonLineSpanExporter(
    private val outputFile: Path,
    private val detail: StandaloneTelemetryDetail,
) : SpanExporter {
    private val lock = Any()

    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        val serializedSpans = spans.joinToString(separator = System.lineSeparator()) { span ->
            SerializedSpan.from(span, detail).toJson().toString()
        }
        val payload = serializedSpans + System.lineSeparator()

        return runCatching {
            outputFile.parent?.let(Files::createDirectories)
            synchronized(lock) {
                Files.writeString(outputFile, payload, CREATE, APPEND)
            }
            CompletableResultCode.ofSuccess()
        }.getOrElse { _ ->
            CompletableResultCode.ofFailure().also { it.fail() }
        }
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

private data class SerializedSpan(
    val name: String,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val kind: String,
    val status: String,
    val attributes: Map<String, String>,
    val events: List<SerializedEvent> = emptyList(),
    val startEpochNanos: Long = 0L,
    val endEpochNanos: Long = 0L,
    val durationNanos: Long = 0L,
) {
    companion object {
        fun from(
            span: SpanData,
            detail: StandaloneTelemetryDetail,
        ): SerializedSpan = SerializedSpan(
            name = span.name,
            traceId = span.traceId,
            spanId = span.spanId,
            parentSpanId = span.parentSpanContext.spanId.takeUnless { it == "0000000000000000" },
            kind = span.kind.name,
            status = span.status.statusCode.name,
            attributes = span.attributes.asMap().mapKeys { (key, _) -> key.key }.mapValues { (_, value) -> value.toString() },
            events = if (detail == StandaloneTelemetryDetail.VERBOSE) {
                span.events.map(SerializedEvent::from)
            } else {
                emptyList()
            },
            startEpochNanos = span.startEpochNanos,
            endEpochNanos = span.endEpochNanos,
            durationNanos = span.endEpochNanos - span.startEpochNanos,
        )
    }

    fun toJson() = buildJsonObject {
        put("name", name)
        put("traceId", traceId)
        put("spanId", spanId)
        parentSpanId?.let { put("parentSpanId", it) }
        put("kind", kind)
        put("status", status)
        put("startEpochNanos", startEpochNanos)
        put("endEpochNanos", endEpochNanos)
        put("durationNanos", durationNanos)
        put(
            "attributes",
            buildJsonObject {
                attributes.forEach { (key, value) ->
                    put(key, value)
                }
            },
        )
        put(
            "events",
            buildJsonArray {
                events.forEach { event ->
                    add(event.toJson())
                }
            },
        )
    }
}

private data class SerializedEvent(
    val name: String,
    val attributes: Map<String, String>,
) {
    companion object {
        fun from(event: EventData): SerializedEvent = SerializedEvent(
            name = event.name,
            attributes = event.attributes.asMap().mapKeys { (key, _) -> key.key }.mapValues { (_, value) -> value.toString() },
        )
    }

    fun toJson() = buildJsonObject {
        put("name", name)
        put(
            "attributes",
            buildJsonObject {
                attributes.forEach { (key, value) ->
                    put(key, value)
                }
            },
        )
    }
}
