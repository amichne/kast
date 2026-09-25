package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.registry.HostedVariants
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal fun installedCanonicalRequestSchema(
    operation: CanonicalOperation,
    serializer: KSerializer<*>,
    variants: HostedVariants,
): JsonObject {
    val generated = generatedHostedRequestSchema(serializer, variants)
    return if (operation == CanonicalOperation.SOURCE_READ) sourceReadToolInputSchema(generated) else generated
}

/** Distributes declaration visibility variants into filter choices without changing accepted requests. */
internal fun sourceReadToolInputSchema(generated: JsonObject): JsonObject {
    val rootProperties = generated.objectAt("properties")
    val entities = rootProperties.objectAt("entities")
    val entityChoices = entities.arrayAt("anyOf")
    val matching = entityChoices.singleVariant("matching")
    val matchingProperties = matching.objectAt("properties")
    val filters = matchingProperties.objectAt("filters")
    val items = filters.objectAt("items")
    val filterChoices = items.arrayAt("anyOf")
    val declaration = filterChoices.singleVariant("declaration")
    val declarationProperties = declaration.objectAt("properties")
    val visibilityChoices = declarationProperties.objectAt("visibility").arrayAt("anyOf")
    check(visibilityChoices.map { (it as? JsonObject)?.variantType() }.toSet() == setOf("any", "exact")) {
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
    val updatedEntities =
        entities.withField("anyOf", schemaArray(entityChoices.map { if (it == matching) updatedMatching else it }))
    return generated.withField("properties", rootProperties.withField("entities", updatedEntities))
}

private fun JsonObject.objectAt(name: String): JsonObject =
    this[name] as? JsonObject ?: error("Expected object schema at $name")

private fun JsonObject.arrayAt(name: String): JsonArray =
    this[name] as? JsonArray ?: error("Expected array schema at $name")

private fun JsonArray.singleVariant(type: String): JsonObject = map {
    it as? JsonObject ?: error("Expected object choice")
}
    .single { it.variantType() == type }

private fun JsonObject.variantType(): String =
    ((objectAt("properties").objectAt("type")["const"] as? JsonPrimitive)?.content)
        ?: error("Expected exact type discriminator")

/** JSON Schema property names and choice lists are the generated schema's dynamic boundary. */
private fun JsonObject.withField(name: String, value: JsonElement): JsonObject =
    Json.encodeToJsonElement<Map<String, JsonElement>>(this + (name to value)).jsonObject

private fun schemaArray(values: List<JsonElement>): JsonArray = Json.encodeToJsonElement(values).jsonArray
