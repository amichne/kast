package io.github.amichne.kast.idea

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class IdeaJsonLineSpanExporter(
    private val outputFile: Path,
    private val detail: IdeaTelemetryDetail,
) : SpanExporter {
    private val lock = Any()

    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        val serializedSpans = spans.joinToString(separator = System.lineSeparator()) { span ->
            IdeaSerializedSpan.from(span, detail).toJson().toString()
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

private data class IdeaSerializedSpan(
    val name: String,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val kind: String,
    val status: String,
    val attributes: Map<String, String>,
    val events: List<IdeaSerializedEvent> = emptyList(),
    val startEpochNanos: Long = 0L,
    val endEpochNanos: Long = 0L,
    val durationNanos: Long = 0L,
) {
    companion object {
        fun from(span: SpanData, detail: IdeaTelemetryDetail): IdeaSerializedSpan = IdeaSerializedSpan(
            name = span.name,
            traceId = span.traceId,
            spanId = span.spanId,
            parentSpanId = span.parentSpanContext.spanId.takeUnless { it == "0000000000000000" },
            kind = span.kind.name,
            status = span.status.statusCode.name,
            attributes = span.attributes.asMap().mapKeys { (key, _) -> key.key }.mapValues { (_, value) -> value.toString() },
            events = if (detail == IdeaTelemetryDetail.VERBOSE) {
                span.events.map(IdeaSerializedEvent::from)
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

private data class IdeaSerializedEvent(
    val name: String,
    val attributes: Map<String, String>,
) {
    companion object {
        fun from(event: EventData): IdeaSerializedEvent = IdeaSerializedEvent(
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
