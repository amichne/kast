package io.github.amichne.kast.standalone

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Scope as OtelScope
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class StandaloneTelemetryScope(
    val wireName: String,
) {
    RENAME("rename"),
    CALL_HIERARCHY("call-hierarchy"),
    ;

    companion object {
        fun parse(rawValue: String): StandaloneTelemetryScope? = when (rawValue.trim().lowercase()) {
            "rename" -> RENAME
            "call-hierarchy", "call_hierarchy", "callhierarchy" -> CALL_HIERARCHY
            else -> null
        }
    }
}

internal enum class StandaloneTelemetryDetail {
    BASIC,
    VERBOSE,
    ;

    companion object {
        fun parse(rawValue: String?): StandaloneTelemetryDetail = when (rawValue?.trim()?.lowercase()) {
            "verbose" -> VERBOSE
            else -> BASIC
        }
    }
}

internal data class StandaloneTelemetryConfig(
    val enabled: Boolean,
    val scopes: Set<StandaloneTelemetryScope>,
    val detail: StandaloneTelemetryDetail,
    val outputFile: Path,
)

internal class StandaloneTelemetry private constructor(
    private val config: StandaloneTelemetryConfig?,
    private val tracerProvider: SdkTracerProvider?,
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
            tracerProvider = null,
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
                tracerProvider = tracerProvider,
                tracer = openTelemetry.getTracer("io.github.amichne.kast.standalone"),
            )
        }

        fun fromEnvironment(workspaceRoot: Path): StandaloneTelemetry {
            val legacyRenameEnabled = System.getenv("KAST_PROFILE_RENAME").isTruthy()
            val enabled = System.getenv("KAST_OTEL_ENABLED").isTruthy() || legacyRenameEnabled
            if (!enabled) {
                return disabled()
            }

            val scopes = parseScopes(System.getenv("KAST_OTEL_SCOPES"))
                ?: if (legacyRenameEnabled) {
                    setOf(StandaloneTelemetryScope.RENAME)
                } else {
                    StandaloneTelemetryScope.entries.toSet()
                }
            val detail = StandaloneTelemetryDetail.parse(
                System.getenv("KAST_OTEL_DETAIL") ?: if (legacyRenameEnabled) "verbose" else null,
            )
            val outputFile = resolveOutputFile(
                rawValue = System.getenv("KAST_OTEL_OUTPUT_FILE") ?: System.getenv("KAST_PROFILE_RENAME_FILE"),
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

            return (configuredPath ?: workspaceRoot.resolve(".kast/telemetry/standalone-spans.jsonl"))
                .toAbsolutePath()
                .normalize()
        }
    }
}

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
        }.getOrElse { failure ->
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
        )
    }

    fun toJson() = buildJsonObject {
        put("name", name)
        put("traceId", traceId)
        put("spanId", spanId)
        parentSpanId?.let { put("parentSpanId", it) }
        put("kind", kind)
        put("status", status)
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

private fun applyAttributes(
    span: Span,
    attributes: Map<String, Any?>,
) {
    attributes.forEach { (key, value) ->
        if (value != null) {
            setAttribute(span, key, value)
        }
    }
}

private fun attributesOf(attributes: Map<String, Any?>): Attributes {
    val builder = Attributes.builder()
    attributes.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is Boolean -> builder.put(AttributeKey.booleanKey(key), value)
            is Double -> builder.put(AttributeKey.doubleKey(key), value)
            is Int -> builder.put(AttributeKey.longKey(key), value.toLong())
            is Long -> builder.put(AttributeKey.longKey(key), value)
            else -> builder.put(AttributeKey.stringKey(key), value.toString())
        }
    }
    return builder.build()
}

private fun setAttribute(
    span: Span,
    key: String,
    value: Any,
) {
    when (value) {
        is Boolean -> span.setAttribute(AttributeKey.booleanKey(key), value)
        is Double -> span.setAttribute(AttributeKey.doubleKey(key), value)
        is Int -> span.setAttribute(AttributeKey.longKey(key), value.toLong())
        is Long -> span.setAttribute(AttributeKey.longKey(key), value)
        else -> span.setAttribute(AttributeKey.stringKey(key), value.toString())
    }
}

private fun String?.isTruthy(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "true", "yes", "on" -> true
    else -> false
}
