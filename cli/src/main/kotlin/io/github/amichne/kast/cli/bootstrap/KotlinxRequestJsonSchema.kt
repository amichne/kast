@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.ProtocolAllowedValues
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.ProtocolHomogeneousCollection
import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Generates the hosted request schema from the same serializer that admits the request. */
internal fun generatedRequestSchema(serializer: KSerializer<*>): JsonObject =
    serializer.descriptor.toJsonSchema(emptyList(), includeNullability = true)

private fun SerialDescriptor.toJsonSchema(
    propertyAnnotations: List<Annotation>,
    includeNullability: Boolean,
): JsonObject {
    if (includeNullability && isNullable) {
        return buildJsonObject {
            putJsonArray("anyOf") {
                add(toJsonSchema(propertyAnnotations, includeNullability = false))
                add(buildJsonObject { put("type", "null") })
            }
        }
    }
    val annotations = annotations + propertyAnnotations
    return when (kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> stringSchema(annotations)
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
            -> integerSchema(annotations)
        PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> buildJsonObject { put("type", "number") }
        SerialKind.ENUM -> enumSchema(annotations)
        StructureKind.LIST -> arraySchema(annotations)
        StructureKind.CLASS, StructureKind.OBJECT -> objectSchema()
        PolymorphicKind.SEALED -> sealedSchema()
        else -> error("Unsupported canonical request descriptor kind $kind at $serialName")
    }
}

private fun stringSchema(annotations: List<Annotation>): JsonObject {
    val constraints = annotations.filterIsInstance<ProtocolStringConstraint>()
    return buildJsonObject {
        put("type", "string")
        constraints.maxOfOrNull(ProtocolStringConstraint::minimumLength)?.let { put("minLength", it) }
        constraints.minOfOrNull(ProtocolStringConstraint::maximumLength)
            ?.takeIf { it != Int.MAX_VALUE }
            ?.let { put("maxLength", it) }
        val patterns = constraints.map(ProtocolStringConstraint::pattern)
            .filter(String::isNotEmpty)
            .distinct()
        when (patterns.size) {
            0 -> Unit
            1 -> put("pattern", patterns.single())
            else -> putJsonArray("allOf") {
                patterns.forEach { pattern -> add(buildJsonObject { put("pattern", pattern) }) }
            }
        }
    }
}

private fun integerSchema(annotations: List<Annotation>): JsonObject {
    val constraints = annotations.filterIsInstance<ProtocolIntegerConstraint>()
    return buildJsonObject {
        put("type", "integer")
        constraints.maxOfOrNull(ProtocolIntegerConstraint::minimum)
            ?.takeIf { it != Long.MIN_VALUE }
            ?.let { put("minimum", it) }
        constraints.minOfOrNull(ProtocolIntegerConstraint::maximum)
            ?.takeIf { it != Long.MAX_VALUE }
            ?.let { put("maximum", it) }
    }
}

private fun SerialDescriptor.enumSchema(annotations: List<Annotation>): JsonObject = buildJsonObject {
    val allowed = annotations.filterIsInstance<ProtocolAllowedValues>()
        .flatMap { it.values.asList() }
        .toSet()
    put("type", "string")
    putJsonArray("enum") {
        repeat(elementsCount) { index ->
            getElementName(index).takeIf { allowed.isEmpty() || it in allowed }?.let {
                add(JsonPrimitive(it))
            }
        }
    }
}

private fun SerialDescriptor.arraySchema(annotations: List<Annotation>): JsonObject {
    val constraints = annotations.filterIsInstance<ProtocolCollectionConstraint>()
    val allowed = annotations.filterIsInstance<ProtocolAllowedValues>()
    val itemSchema = getElementDescriptor(0).toJsonSchema(
        allowed,
        includeNullability = true,
    )
    val itemVariants = itemSchema["anyOf"] as? JsonArray
    if (annotations.any { it is ProtocolHomogeneousCollection } && itemVariants != null) {
        return buildJsonObject {
            putJsonArray("anyOf") {
                itemVariants.forEach { variant -> add(arrayVariant(variant, constraints)) }
            }
        }
    }
    return arrayVariant(itemSchema, constraints)
}

private fun arrayVariant(
    itemSchema: kotlinx.serialization.json.JsonElement,
    constraints: List<ProtocolCollectionConstraint>,
): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", itemSchema)
    constraints.maxOfOrNull(ProtocolCollectionConstraint::minimumItems)
        ?.takeIf { it > 0 }
        ?.let { put("minItems", it) }
    constraints.minOfOrNull(ProtocolCollectionConstraint::maximumItems)
        ?.takeIf { it != Int.MAX_VALUE }
        ?.let { put("maxItems", it) }
    if (constraints.any(ProtocolCollectionConstraint::uniqueItems)) put("uniqueItems", true)
}

private fun SerialDescriptor.objectSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        repeat(elementsCount) { index ->
            put(
                getElementName(index),
                getElementDescriptor(index).toJsonSchema(
                    getElementAnnotations(index),
                    includeNullability = true,
                ),
            )
        }
    }
    putJsonArray("required") {
        repeat(elementsCount) { index -> add(JsonPrimitive(getElementName(index))) }
    }
}

private fun SerialDescriptor.sealedSchema(): JsonObject {
    val discriminator = annotations.filterIsInstance<JsonClassDiscriminator>()
        .lastOrNull()
        ?.discriminator
        ?: "type"
    val variants = getElementDescriptor(1)
    return buildJsonObject {
        putJsonArray("anyOf") {
            repeat(variants.elementsCount) { index ->
                val tag = variants.getElementName(index).substringAfterLast('.')
                add(
                    variants.getElementDescriptor(index)
                        .toJsonSchema(emptyList(), includeNullability = false)
                        .withDiscriminator(discriminator, tag),
                )
            }
        }
    }
}

private fun JsonObject.withDiscriminator(name: String, value: String): JsonObject {
    val properties = this["properties"] as? JsonObject
        ?: error("A sealed request variant must serialize as an object")
    val required = this["required"] as? JsonArray
        ?: error("A sealed request variant must declare required fields")
    return buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            put(name, buildJsonObject {
                put("type", "string")
                put("const", value)
            })
            properties.forEach(::put)
        }
        put("required", buildJsonArray {
            add(JsonPrimitive(name))
            required.forEach(::add)
        })
    }
}
