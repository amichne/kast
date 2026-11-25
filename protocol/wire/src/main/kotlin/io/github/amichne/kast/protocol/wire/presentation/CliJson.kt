package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.PresentedWorkspaceRoot
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

private val cliJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    classDiscriminator = "type"
}

private fun cliJson(root: PresentedWorkspaceRoot): Json = Json(cliJson) { serializersModule = root.serializersModule() }

interface OutputDocument {
    val value: String
}

/** A canonical compact JSON document ready for the process output boundary. */
class CanonicalJsonDocument
private constructor(
    private val detail: ToolOutputDetail,
    private val json: Json,
    private val render: (ToolOutputDetail, Json) -> String,
) : OutputDocument {
    override val value: String = render(detail, json)

    /** The owner projects typed data; transports never remove arbitrary JSON keys. */
    fun present(detail: ToolOutputDetail): CanonicalJsonDocument = CanonicalJsonDocument(detail, json, render)

    /** Presents each file below one canonical root relative to it, as file inputs require. */
    fun relativeTo(root: PresentedWorkspaceRoot): CanonicalJsonDocument =
        CanonicalJsonDocument(detail, cliJson(root), render)

    companion object {
        /** Byte measurement uses the process document's serializer settings before paths become relative. */
        @OptIn(ExperimentalSerializationApi::class)
        internal fun <Value> encodedBytes(serializer: KSerializer<Value>, value: Value): Long {
            val counter = Utf8ByteCounter()
            cliJson.encodeToStream(serializer, value, counter)
            return counter.bytes
        }

        /** Selects the generated serializer for one closed CLI document type. */
        fun <Value> generated(
            serializer: KSerializer<Value>,
            compact: (Value) -> Value = { it },
        ): Factory<Value> = Factory(serializer, compact)
    }

    /** A generated serializer bound to the CLI's sole configured JSON instance. */
    class Factory<Value>(private val serializer: KSerializer<Value>, private val compact: (Value) -> Value) {
        fun create(value: Value): CanonicalJsonDocument {
            val render: (ToolOutputDetail, Json) -> String = { detail, json ->
                json.encodeToString(
                    serializer,
                    when (detail) {
                        ToolOutputDetail.COMPACT -> compact(value)
                        ToolOutputDetail.VERBOSE -> value
                    },
                )
            }
            return CanonicalJsonDocument(ToolOutputDetail.VERBOSE, cliJson, render)
        }
    }
}

/** Counts the serializer's emitted bytes without retaining a discarded JSON document. */
private class Utf8ByteCounter : OutputStream() {
    var bytes = 0L
        private set

    override fun write(value: Int) {
        bytes++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        bytes += length
    }
}

/** Exhaustive operation-outcome projection before process status selection. */
sealed interface ProjectedOperationOutcome {
    fun relativeTo(root: PresentedWorkspaceRoot): ProjectedOperationOutcome =
        when (this) {
            is Complete -> Complete(document.relativeTo(root))
            is Qualified -> Qualified(document.relativeTo(root))
            is Rejected -> Rejected(document.relativeTo(root))
        }

    data class Complete(val document: CanonicalJsonDocument) : ProjectedOperationOutcome

    data class Qualified(val document: CanonicalJsonDocument) : ProjectedOperationOutcome

    data class Rejected(val document: CanonicalJsonDocument) : ProjectedOperationOutcome
}
