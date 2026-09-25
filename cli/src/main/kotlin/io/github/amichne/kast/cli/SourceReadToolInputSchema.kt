package io.github.amichne.kast.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Hoists source choices and folds nullable primitives so Copilot sees at most two composition levels. */
internal fun sourceReadToolInputSchema(generated: JsonObject, intent: JsonObject): JsonObject {
    val generatedChoices = generated["oneOf"] as? JsonArray ?: schemaArray(listOf(generated))
    val canonical =
        generatedChoices
            .map { it as? JsonObject ?: error("Expected source request choice") }
            .single { (it["properties"] as? JsonObject)?.containsKey("anchor") == true }
    val choices =
        expandEntities(canonical, "type") + generatedChoices.filter { it != canonical } + expandEntities(intent, "mode")
    return Json.encodeToJsonElement(mapOf("anyOf" to schemaArray(choices.map(::foldNullablePrimitives)))).jsonObject
}

/** Distribute the exact visibility variants into each declaration filter, then hoist entity choices. */
private fun expandEntities(root: JsonObject, discriminator: String): List<JsonObject> {
    val rootProperties = root.objectAt("properties")
    val entities = rootProperties.objectAt("entities")
    val entityChoices = entities.arrayAt("anyOf")
    val matching = entityChoices.singleVariant("matching", discriminator)
    val matchingProperties = matching.objectAt("properties")
    val filters = matchingProperties.objectAt("filters")
    val items = filters.objectAt("items")
    val filterChoices = items.arrayAt("anyOf")
    val declaration = filterChoices.singleVariant("declaration", "type")
    val declarationProperties = declaration.objectAt("properties")
    val visibilityChoices = declarationProperties.objectAt("visibility").arrayAt("anyOf")
    check(visibilityChoices.map { (it as? JsonObject)?.variantType("type") }.toSet() == setOf("any", "exact")) {
        "Source declaration visibility variants changed"
    }

    val expandedFilters = filterChoices.flatMap { choice ->
        if (choice != declaration) listOf(choice)
        else
            visibilityChoices.map { visibility ->
                declaration.withField("properties", declarationProperties.withField("visibility", visibility))
            }
    }
    val updatedItems = items.withField("anyOf", schemaArray(expandedFilters))
    val updatedFilters = filters.withField("items", updatedItems)
    val updatedMatching = matching.withField("properties", matchingProperties.withField("filters", updatedFilters))
    val expandedEntities = entityChoices.map { if (it == matching) updatedMatching else it }
    return expandedEntities.map { entity ->
        root.withField("properties", rootProperties.withField("entities", entity))
    }
}

private fun JsonObject.objectAt(name: String): JsonObject =
    this[name] as? JsonObject ?: error("Expected object schema at $name")

private fun JsonObject.arrayAt(name: String): JsonArray =
    this[name] as? JsonArray ?: error("Expected array schema at $name")

private fun JsonArray.singleVariant(type: String, discriminator: String): JsonObject = map {
    it as? JsonObject ?: error("Expected object choice")
}
    .single { it.variantType(discriminator) == type }

private fun JsonObject.variantType(discriminator: String): String =
    ((objectAt("properties").objectAt(discriminator)["const"] as? JsonPrimitive)?.content)
        ?: error("Expected exact type discriminator")

private fun foldNullablePrimitives(value: JsonElement): JsonElement =
    when (value) {
        is JsonArray -> schemaArray(value.map(::foldNullablePrimitives))
        is JsonObject -> {
            val options = value["anyOf"] as? JsonArray
            val nullOption = options?.singleOrNull { (it as? JsonObject)?.get("type") == JsonPrimitive("null") }
            val concrete = options?.singleOrNull { it != nullOption } as? JsonObject
            val kind = concrete?.get("type") as? JsonPrimitive
            val nullablePrimitive = kind?.content in setOf("string", "integer", "number", "boolean")
            if (options?.size == 2 && nullOption != null && nullablePrimitive) {
                val concreteOption = requireNotNull(concrete)
                val primitiveKind = requireNotNull(kind)
                Json.encodeToJsonElement(
                        (value - "anyOf" + concreteOption).mapValues { (key, child) ->
                            if (key == "type") schemaArray(listOf(primitiveKind, JsonPrimitive("null")))
                            else foldNullablePrimitives(child)
                        }
                    )
                    .jsonObject
            } else Json.encodeToJsonElement(value.mapValues { (_, child) -> foldNullablePrimitives(child) }).jsonObject
        }
        else -> value
    }

/** JSON Schema property names and choice lists are the generated schema's dynamic boundary. */
private fun JsonObject.withField(name: String, value: JsonElement): JsonObject =
    Json.encodeToJsonElement<Map<String, JsonElement>>(this + (name to value)).jsonObject

private fun schemaArray(values: List<JsonElement>): JsonArray = Json.encodeToJsonElement(values).jsonArray
