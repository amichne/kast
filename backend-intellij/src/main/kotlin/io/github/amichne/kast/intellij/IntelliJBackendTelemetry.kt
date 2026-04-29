package io.github.amichne.kast.intellij

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.api.client.workspaceDataDirectory
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
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

internal enum class IntelliJTelemetryScope {
    RENAME,
    REFERENCES,
    CALL_HIERARCHY,
    TYPE_HIERARCHY,
    IMPLEMENTATIONS,
    COMPLETIONS,
    SEMANTIC_INSERTION_POINT,
    DIAGNOSTICS,
    OPTIMIZE_IMPORTS,
    RESOLVE,
    WORKSPACE_FILES,
    WORKSPACE_SYMBOL_SEARCH,
    READ_ACTION,
    FILE_OUTLINE,
    APPLY_EDITS,
    REFRESH,
    ;

    companion object {
        fun parse(rawValue: String): IntelliJTelemetryScope? = when (rawValue.trim().lowercase()) {
            "rename" -> RENAME
            "references", "find-references", "find_references" -> REFERENCES
            "call-hierarchy", "call_hierarchy", "callhierarchy" -> CALL_HIERARCHY
            "type-hierarchy", "type_hierarchy", "typehierarchy" -> TYPE_HIERARCHY
            "implementations" -> IMPLEMENTATIONS
            "completions" -> COMPLETIONS
            "semantic-insertion-point", "semantic_insertion_point", "semanticinsertionpoint" -> SEMANTIC_INSERTION_POINT
            "diagnostics" -> DIAGNOSTICS
            "optimize-imports", "optimize_imports", "optimizeimports" -> OPTIMIZE_IMPORTS
            "resolve", "symbol-resolve", "symbol_resolve" -> RESOLVE
            "workspace-files", "workspace_files", "workspacefiles" -> WORKSPACE_FILES
            "workspace-symbol-search", "workspace_symbol_search", "workspacesymbolsearch" -> WORKSPACE_SYMBOL_SEARCH
            "read-action", "read_action", "readaction" -> READ_ACTION
            "file-outline", "file_outline", "fileoutline", "outline" -> FILE_OUTLINE
            "apply-edits", "apply_edits", "applyedits" -> APPLY_EDITS
            "refresh" -> REFRESH
            else -> null
        }
    }
}

internal enum class IntelliJTelemetryDetail {
    BASIC,
    VERBOSE,
    ;

    companion object {
        fun parse(rawValue: String?): IntelliJTelemetryDetail = when (rawValue?.trim()?.lowercase()) {
            "verbose" -> VERBOSE
            else -> BASIC
        }
    }
}

internal data class IntelliJTelemetryConfig(
    val enabled: Boolean,
    val scopes: Set<IntelliJTelemetryScope>,
    val detail: IntelliJTelemetryDetail,
    val outputFile: Path,
)

internal class IntelliJTelemetrySpan internal constructor(
    private val telemetry: IntelliJBackendTelemetry,
    private val scope: IntelliJTelemetryScope,
    private val span: Span?,
) {
    fun setAttribute(key: String, value: Any?) {
        if (span == null || value == null) return
        setSpanAttribute(span, key, value)
    }

    fun addEvent(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
    ) {
        if (span == null || (verboseOnly && !telemetry.isVerbose(scope))) return
        span.addEvent(name, buildAttributes(attributes))
    }

    inline fun <T> child(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
        block: (IntelliJTelemetrySpan) -> T,
    ): T = telemetry.inSpan(
        scope = scope,
        name = name,
        attributes = attributes,
        verboseOnly = verboseOnly,
        block = block,
    )

    companion object {
        fun disabled(
            telemetry: IntelliJBackendTelemetry,
            scope: IntelliJTelemetryScope,
        ): IntelliJTelemetrySpan = IntelliJTelemetrySpan(
            telemetry = telemetry,
            scope = scope,
            span = null,
        )
    }
}

internal class IntelliJBackendTelemetry private constructor(
    private val config: IntelliJTelemetryConfig?,
    private val tracer: Tracer?,
) {
    fun isEnabled(scope: IntelliJTelemetryScope): Boolean = config != null && scope in config.scopes

    fun isVerbose(scope: IntelliJTelemetryScope): Boolean = isEnabled(scope) && config?.detail == IntelliJTelemetryDetail.VERBOSE

    inline fun <T> inSpan(
        scope: IntelliJTelemetryScope,
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        verboseOnly: Boolean = false,
        block: (IntelliJTelemetrySpan) -> T,
    ): T {
        if (!isEnabled(scope) || (verboseOnly && !isVerbose(scope))) {
            return block(IntelliJTelemetrySpan.disabled(this, scope))
        }

        val startedSpan = checkNotNull(tracer).spanBuilder(name).startSpan()
        applySpanAttributes(startedSpan, attributes)
        val otelScope = startedSpan.makeCurrent()
        val telemetrySpan = IntelliJTelemetrySpan(
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

    fun recordReadAction(scope: IntelliJTelemetryScope, name: String, waitNanos: Long, holdNanos: Long) {
        if (!isEnabled(IntelliJTelemetryScope.READ_ACTION)) return
        inSpan(
            scope = scope,
            name = name,
            attributes = mapOf(
                "kast.readAction.waitNanos" to waitNanos,
                "kast.readAction.holdNanos" to holdNanos,
            ),
        ) {}
    }

    companion object {
        fun disabled(): IntelliJBackendTelemetry = IntelliJBackendTelemetry(
            config = null,
            tracer = null,
        )

        fun create(config: IntelliJTelemetryConfig): IntelliJBackendTelemetry {
            if (!config.enabled || config.scopes.isEmpty()) {
                return disabled()
            }

            val exporter = IntelliJJsonLineSpanExporter(
                outputFile = config.outputFile,
                detail = config.detail,
            )
            val tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
            val openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()

            return IntelliJBackendTelemetry(
                config = config,
                tracer = openTelemetry.getTracer("io.github.amichne.kast.intellij"),
            )
        }

        fun fromConfig(
            workspaceRoot: Path,
            config: KastConfig = KastConfig.load(workspaceRoot),
        ): IntelliJBackendTelemetry {
            if (!config.telemetry.enabled) {
                return disabled()
            }

            val scopes = if (config.telemetry.scopes.equals("all", ignoreCase = true)) {
                IntelliJTelemetryScope.entries.toSet()
            } else {
                parseScopes(config.telemetry.scopes) ?: IntelliJTelemetryScope.entries.toSet()
            }
            val detail = IntelliJTelemetryDetail.parse(config.telemetry.detail)
            val outputFile = resolveOutputFile(
                rawValue = config.telemetry.outputFile,
                workspaceRoot = workspaceRoot,
            )

            return create(
                IntelliJTelemetryConfig(
                    enabled = true,
                    scopes = scopes,
                    detail = detail,
                    outputFile = outputFile,
                ),
            )
        }

        private fun parseScopes(rawValue: String?): Set<IntelliJTelemetryScope>? {
            if (rawValue.isNullOrBlank()) return null
            val scopes = rawValue.split(',')
                .mapNotNull(IntelliJTelemetryScope::parse)
                .toSet()
            return scopes.ifEmpty { null }
        }

        private fun resolveOutputFile(rawValue: String?, workspaceRoot: Path): Path {
            val configuredPath = rawValue
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let { path -> if (path.isAbsolute) path else workspaceRoot.resolve(path) }

            return (configuredPath ?: workspaceDataDirectory(workspaceRoot).resolve("telemetry/intellij-spans.jsonl"))
                .toAbsolutePath()
                .normalize()
        }
    }
}

// --- Shared attribute helpers ---

private fun applySpanAttributes(span: Span, attributes: Map<String, Any?>) {
    attributes.forEach { (key, value) ->
        if (value != null) setSpanAttribute(span, key, value)
    }
}

private fun buildAttributes(attributes: Map<String, Any?>): Attributes {
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

private fun setSpanAttribute(span: Span, key: String, value: Any) {
    when (value) {
        is Boolean -> span.setAttribute(AttributeKey.booleanKey(key), value)
        is Double -> span.setAttribute(AttributeKey.doubleKey(key), value)
        is Int -> span.setAttribute(AttributeKey.longKey(key), value.toLong())
        is Long -> span.setAttribute(AttributeKey.longKey(key), value)
        else -> span.setAttribute(AttributeKey.stringKey(key), value.toString())
    }
}

// --- JSON-line exporter ---

private class IntelliJJsonLineSpanExporter(
    private val outputFile: Path,
    private val detail: IntelliJTelemetryDetail,
) : SpanExporter {
    private val lock = Any()

    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        val serializedSpans = spans.joinToString(separator = System.lineSeparator()) { span ->
            IntelliJSerializedSpan.from(span, detail).toJson().toString()
        }
        val payload = serializedSpans + System.lineSeparator()

        return runCatching {
            outputFile.parent?.let(Files::createDirectories)
            synchronized(lock) {
                Files.writeString(outputFile, payload, CREATE, APPEND)
            }
            CompletableResultCode.ofSuccess()
        }.getOrElse {
            CompletableResultCode.ofFailure().also { code -> code.fail() }
        }
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

private data class IntelliJSerializedSpan(
    val name: String,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val kind: String,
    val status: String,
    val attributes: Map<String, String>,
    val events: List<IntelliJSerializedEvent> = emptyList(),
    val startEpochNanos: Long = 0L,
    val endEpochNanos: Long = 0L,
    val durationNanos: Long = 0L,
) {
    companion object {
        fun from(span: SpanData, detail: IntelliJTelemetryDetail): IntelliJSerializedSpan = IntelliJSerializedSpan(
            name = span.name,
            traceId = span.traceId,
            spanId = span.spanId,
            parentSpanId = span.parentSpanContext.spanId.takeUnless { it == "0000000000000000" },
            kind = span.kind.name,
            status = span.status.statusCode.name,
            attributes = span.attributes.asMap().mapKeys { (key, _) -> key.key }.mapValues { (_, value) -> value.toString() },
            events = if (detail == IntelliJTelemetryDetail.VERBOSE) {
                span.events.map(IntelliJSerializedEvent::from)
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
        put("attributes", buildJsonObject {
            attributes.forEach { (key, value) -> put(key, value) }
        })
        put("events", buildJsonArray {
            events.forEach { event -> add(event.toJson()) }
        })
    }
}

private data class IntelliJSerializedEvent(
    val name: String,
    val attributes: Map<String, String>,
) {
    companion object {
        fun from(event: EventData): IntelliJSerializedEvent = IntelliJSerializedEvent(
            name = event.name,
            attributes = event.attributes.asMap().mapKeys { (key, _) -> key.key }.mapValues { (_, value) -> value.toString() },
        )
    }

    fun toJson() = buildJsonObject {
        put("name", name)
        put("attributes", buildJsonObject {
            attributes.forEach { (key, value) -> put(key, value) }
        })
    }
}
