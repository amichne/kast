package io.github.amichne.kast.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Documentation projection of an admitted, dynamic JSON Schema resource. Lift local definitions into direct OpenAPI
 * components; retain every assertion and annotate variants for human navigation. Schema keyword maps are dynamic schema
 * data, never a manually assembled invocation payload.
 */
internal fun JsonElement.documentationComponents(
    componentName: MintlifyCallableComponentName
): List<Pair<String, JsonElement>> {
    val schema = this as? JsonObject ?: error("A callable schema must be an object")
    val definitions = schema["\$defs"] as? JsonObject
    return listOf(componentName.value to schema.documentationSchema(componentName)) +
        definitions.orEmpty().map { (name, definition) ->
            "${componentName.value}_$name" to definition.documentationSchema(componentName, name)
        }
}

private fun JsonElement.documentationSchema(
    componentName: MintlifyCallableComponentName,
    title: String? = null,
): JsonElement =
    when (this) {
        is JsonArray -> Json.encodeToJsonElement(map { it.documentationSchema(componentName) })
        is JsonObject -> {
            val label = title ?: documentationTitle()
            val keywords = filterKeys {
                it != "\$defs"
            }
                .mapValues { (name, value) ->
                    localReference(name, value, componentName) ?: value.documentationSchema(componentName)
                }
            Json.encodeToJsonElement(
                    if (label != null && "title" !in keywords) keywords + ("title" to JsonPrimitive(label))
                    else keywords
                )
                .jsonObject
        }
        else -> this
    }

private fun localReference(
    name: String,
    value: JsonElement,
    componentName: MintlifyCallableComponentName,
): JsonPrimitive? {
    if (name != "\$ref") return null
    val reference = value as? JsonPrimitive ?: return null
    if (!reference.isString) return null
    if (!reference.content.startsWith("#/\$defs/")) return null
    return JsonPrimitive("#/components/schemas/${componentName.value}_${reference.content.removePrefix("#/\$defs/")}")
}

/** UI label derived from an existing discriminator and the schema's required live-evidence field. */
private fun JsonObject.documentationTitle(): String? {
    val properties = this["properties"] as? JsonObject
    val tag =
        listOf("status", "type", "kind").firstNotNullOfOrNull { key ->
            ((properties?.get(key) as? JsonObject)?.get("const") as? JsonPrimitive)?.content
        } ?: return null
    val required = this["required"] as? JsonArray
    // Mintlify resolves tab labels through an object lookup; the bare constructor key resolves to its prototype.
    val label = if (tag == "constructor") "constructor symbol" else tag
    return if (required?.contains(JsonPrimitive("live")) == true) "$label · live" else label
}

/** One OpenAPI component identity derived only from a canonical operation and schema role. */
@JvmInline
internal value class MintlifyCallableComponentName private constructor(val value: String) {
    companion object {
        fun named(value: String): MintlifyCallableComponentName = MintlifyCallableComponentName(value)

        fun request(toolName: String): MintlifyCallableComponentName =
            MintlifyCallableComponentName("${toolName}Request")

        fun response(toolName: String): MintlifyCallableComponentName =
            MintlifyCallableComponentName("${toolName}Response")
    }
}
