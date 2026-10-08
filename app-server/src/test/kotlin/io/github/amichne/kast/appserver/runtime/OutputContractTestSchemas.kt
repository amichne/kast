package io.github.amichne.kast.appserver.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Typed synthetic contracts for the independent broker output-admission regressions. */
internal object OutputContractTestSchemas {
    val objectDocument = Json.encodeToJsonElement(ObjectSchema(Type.OBJECT)).jsonObject
    val input =
        Json.encodeToJsonElement(
                ClosedObjectSchema(
                    type = Type.OBJECT,
                    required = listOf("call"),
                    additionalProperties = false,
                    properties = InputProperties(ObjectSchema(Type.STRING)),
                )
            )
            .jsonObject
    val output =
        Json.encodeToJsonElement(
                ClosedObjectSchema(
                    type = Type.OBJECT,
                    required = listOf("continuation"),
                    additionalProperties = false,
                    properties = OutputProperties(PatternSchema(Type.STRING, "^valid:[a-z]+$")),
                )
            )
            .jsonObject

    @Serializable
    private enum class Type {
        @SerialName("object") OBJECT,
        @SerialName("string") STRING,
    }

    @Serializable private data class ObjectSchema(val type: Type)

    @Serializable private data class PatternSchema(val type: Type, val pattern: String)

    @Serializable
    private data class ClosedObjectSchema<Properties>(
        val type: Type,
        val required: List<String>,
        val additionalProperties: Boolean,
        val properties: Properties,
    )

    @Serializable private data class InputProperties(val call: ObjectSchema)

    @Serializable private data class OutputProperties(val continuation: PatternSchema)
}
