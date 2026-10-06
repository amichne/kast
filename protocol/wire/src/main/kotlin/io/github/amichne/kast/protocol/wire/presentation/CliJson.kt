package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val cliJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    classDiscriminator = "type"
}

interface OutputDocument {
    val value: String
}

/** A canonical compact JSON document ready for the process output boundary. */
class CanonicalJsonDocument
private constructor(
    override val value: String,
    private val render: (ToolOutputDetail) -> String,
) : OutputDocument {
    /** The owner projects typed data; transports never remove arbitrary JSON keys. */
    fun present(detail: ToolOutputDetail): CanonicalJsonDocument = CanonicalJsonDocument(render(detail), render)

    companion object {
        /** Byte measurement uses the same serializer settings as the public process document. */
        internal fun <Value> encodedBytes(serializer: KSerializer<Value>, value: Value): Long =
            cliJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8).size.toLong()

        /** Selects the generated serializer for one closed CLI document type. */
        fun <Value> generated(
            serializer: KSerializer<Value>,
            compact: (Value) -> Value = { it },
        ): Factory<Value> = Factory(serializer, compact)
    }

    /** A generated serializer bound to the CLI's sole configured JSON instance. */
    class Factory<Value>(private val serializer: KSerializer<Value>, private val compact: (Value) -> Value) {
        fun create(value: Value): CanonicalJsonDocument {
            val render: (ToolOutputDetail) -> String = { detail ->
                cliJson.encodeToString(
                    serializer,
                    when (detail) {
                        ToolOutputDetail.COMPACT -> compact(value)
                        ToolOutputDetail.VERBOSE -> value
                    },
                )
            }
            return CanonicalJsonDocument(render(ToolOutputDetail.VERBOSE), render)
        }
    }
}

/** Exhaustive operation-outcome projection before process status selection. */
sealed interface ProjectedOperationOutcome {
    data class Complete(val document: CanonicalJsonDocument) : ProjectedOperationOutcome

    data class Qualified(val document: CanonicalJsonDocument) : ProjectedOperationOutcome

    data class Rejected(val document: CanonicalJsonDocument) : ProjectedOperationOutcome
}
