@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.ProtocolAllowedValues
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.ProtocolHomogeneousCollection
import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadSimpleRequest
import io.github.amichne.kast.protocol.registry.HostedVariants
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Generates the hosted request schema from the same serializer that admits the request. */
internal fun generatedRequestSchema(serializer: KSerializer<*>): JsonObject =
    serializer.descriptor.toJsonSchema(
        emptyList(),
        includeNullability = true,
        references = CanonicalSchemaReferences.None,
    )

/** Installed output definitions are substituted before expansion; the caller attaches their canonical authority. */
internal fun generatedOutputSchema(
    serializer: KSerializer<*>,
    references: CanonicalSchemaReferences = CanonicalSchemaReferences.Installed,
): JsonObject =
    serializer.descriptor.toJsonSchema(
        emptyList(),
        includeNullability = true,
        references = references,
        allowReference = false,
    )

/** Retains generated payload constraints while narrowing variants through the canonical hosted owner. */
internal fun generatedHostedRequestSchema(serializer: KSerializer<*>, variants: HostedVariants): JsonObject {
    val generated = generatedRequestSchema(serializer)
    if (serializer.descriptor.serialName == SourceReadRequest.serializer().descriptor.serialName) {
        return OBJECT_SCHEMA_JSON.encodeToJsonElement(
                GeneratedUnionSchemaDocument.serializer(),
                GeneratedUnionSchemaDocument(
                    listOf(generatedRequestSchema(SourceReadSimpleRequest.serializer()), generated)
                ),
            )
            .jsonObject
    }
    return when (variants) {
        HostedVariants.None -> generated
        is HostedVariants.Intents -> {
            val properties = generated["properties"] as? JsonObject ?: error("Hosted intent request must be an object")
            val intent = properties["intent"] as? JsonObject ?: error("Hosted intent request must retain intent")
            val choices = intent["anyOf"] as? JsonArray ?: error("Hosted intents must be a generated closed union")
            val allowed = variants.intents.map { it.identity }.toSet()
            val selected = choices.filter { choice ->
                val fields = (choice as? JsonObject)?.get("properties") as? JsonObject
                val tag =
                    ((fields?.get("kind") as? JsonObject)?.get("enum") as? JsonArray)?.singleOrNull() as? JsonPrimitive
                tag?.content in allowed
            }
            check(selected.size == allowed.size) { "Every hosted intent must resolve to one generated variant" }
            JsonObject(
                generated +
                    ("properties" to
                        JsonObject(properties + ("intent" to JsonObject(intent + ("anyOf" to JsonArray(selected))))))
            )
        }
    }
}

private fun SerialDescriptor.toJsonSchema(
    propertyAnnotations: List<Annotation>,
    includeNullability: Boolean,
    references: CanonicalSchemaReferences,
    allowReference: Boolean = true,
): JsonObject {
    // JsonElement is used only for contract-defined opaque JSON. An empty schema admits every JSON value.
    if (serialName.removeSuffix("?") == "kotlinx.serialization.json.JsonElement")
        return OBJECT_SCHEMA_JSON.encodeToJsonElement(UnconstrainedJsonSchema.serializer(), UnconstrainedJsonSchema)
            .jsonObject
    if (includeNullability && isNullable) {
        return OBJECT_SCHEMA_JSON.encodeToJsonElement(
                GeneratedAnyOfSchemaDocument.serializer(),
                GeneratedAnyOfSchemaDocument(
                    listOf(
                        toJsonSchema(propertyAnnotations, includeNullability = false, references, allowReference),
                        primitiveSchema(GeneratedPrimitiveSchemaType.NULL),
                    )
                ),
            )
            .jsonObject
    }
    if (allowReference && propertyAnnotations.isEmpty()) {
        references.name(this)?.let { name ->
            return OBJECT_SCHEMA_JSON.encodeToJsonElement(
                    GeneratedSchemaReference.serializer(),
                    GeneratedSchemaReference("#/\$defs/$name"),
                )
                .jsonObject
        }
    }
    val annotations = annotations + propertyAnnotations
    return when (kind) {
        PrimitiveKind.STRING,
        PrimitiveKind.CHAR -> stringSchema(annotations)
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG -> integerSchema(annotations)
        PrimitiveKind.BOOLEAN -> primitiveSchema(GeneratedPrimitiveSchemaType.BOOLEAN)
        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE -> primitiveSchema(GeneratedPrimitiveSchemaType.NUMBER)
        SerialKind.ENUM -> enumSchema(annotations)
        StructureKind.LIST -> arraySchema(annotations, references)
        StructureKind.MAP ->
            if (serialName.removeSuffix("?") == "kotlinx.serialization.json.JsonObject") {
                OBJECT_SCHEMA_JSON.encodeToJsonElement(OpenObjectSchema.serializer(), OpenObjectSchema()).jsonObject
            } else {
                error("Unsupported canonical request descriptor kind $kind at $serialName")
            }
        StructureKind.CLASS,
        StructureKind.OBJECT -> objectSchema(references)
        PolymorphicKind.SEALED -> sealedSchema(references)
        else -> error("Unsupported canonical request descriptor kind $kind at $serialName")
    }
}

private fun stringSchema(annotations: List<Annotation>): JsonObject {
    val constraints = annotations.filterIsInstance<ProtocolStringConstraint>()
    return buildJsonObject {
        put("type", "string")
        constraints.maxOfOrNull(ProtocolStringConstraint::minimumLength)?.let { put("minLength", it) }
        constraints
            .minOfOrNull(ProtocolStringConstraint::maximumLength)
            ?.takeIf { it != Int.MAX_VALUE }
            ?.let { put("maxLength", it) }
        val patterns = constraints.map(ProtocolStringConstraint::pattern).filter(String::isNotEmpty).distinct()
        when (patterns.size) {
            0 -> Unit
            1 -> put("pattern", patterns.single())
            else ->
                putJsonArray("allOf") {
                    patterns.forEach { pattern -> add(buildJsonObject { put("pattern", pattern) }) }
                }
        }
    }
}

private fun integerSchema(annotations: List<Annotation>): JsonObject {
    val constraints = annotations.filterIsInstance<ProtocolIntegerConstraint>()
    return buildJsonObject {
        put("type", "integer")
        constraints
            .maxOfOrNull(ProtocolIntegerConstraint::minimum)
            ?.takeIf { it != Long.MIN_VALUE }
            ?.let { put("minimum", it) }
        constraints
            .minOfOrNull(ProtocolIntegerConstraint::maximum)
            ?.takeIf { it != Long.MAX_VALUE }
            ?.let { put("maximum", it) }
    }
}

private fun SerialDescriptor.enumSchema(annotations: List<Annotation>): JsonObject = buildJsonObject {
    val allowed = annotations.filterIsInstance<ProtocolAllowedValues>().flatMap { it.values.asList() }.toSet()
    put("type", "string")
    putJsonArray("enum") {
        repeat(elementsCount) { index ->
            getElementName(index)
                .takeIf { allowed.isEmpty() || it in allowed }
                ?.let {
                    add(JsonPrimitive(it))
                }
        }
    }
}

private fun SerialDescriptor.arraySchema(
    annotations: List<Annotation>,
    references: CanonicalSchemaReferences,
): JsonObject {
    val constraints = annotations.filterIsInstance<ProtocolCollectionConstraint>()
    val allowed = annotations.filterIsInstance<ProtocolAllowedValues>()
    val itemSchema =
        getElementDescriptor(0)
            .toJsonSchema(
                allowed,
                includeNullability = true,
                references = references,
                allowReference = annotations.none { it is ProtocolHomogeneousCollection },
            )
    val itemVariants = itemSchema["anyOf"] as? JsonArray
    if (annotations.any { it is ProtocolHomogeneousCollection } && itemVariants != null) {
        return OBJECT_SCHEMA_JSON.encodeToJsonElement(
                GeneratedAnyOfSchemaDocument.serializer(),
                GeneratedAnyOfSchemaDocument(itemVariants.map { variant -> arrayVariant(variant, constraints) }),
            )
            .jsonObject
    }
    return arrayVariant(itemSchema, constraints)
}

private fun arrayVariant(
    itemSchema: kotlinx.serialization.json.JsonElement,
    constraints: List<ProtocolCollectionConstraint>,
): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", itemSchema)
    constraints.maxOfOrNull(ProtocolCollectionConstraint::minimumItems)?.takeIf { it > 0 }?.let { put("minItems", it) }
    constraints
        .minOfOrNull(ProtocolCollectionConstraint::maximumItems)
        ?.takeIf { it != Int.MAX_VALUE }
        ?.let { put("maxItems", it) }
    if (constraints.any(ProtocolCollectionConstraint::uniqueItems)) put("uniqueItems", true)
}

/** Property names and child schemas are the JSON Schema contract's dynamic boundary. */
@Serializable private data class OpenObjectSchema(val type: String = "object", val additionalProperties: Boolean = true)

@Serializable private data class GeneratedAnyOfSchemaDocument(val anyOf: List<JsonElement>)

@Serializable private data class GeneratedPrimitiveSchemaDocument(val type: GeneratedPrimitiveSchemaType)

@Serializable
private enum class GeneratedPrimitiveSchemaType {
    @kotlinx.serialization.SerialName("null") NULL,
    @kotlinx.serialization.SerialName("boolean") BOOLEAN,
    @kotlinx.serialization.SerialName("number") NUMBER,
}

private fun primitiveSchema(type: GeneratedPrimitiveSchemaType): JsonObject =
    OBJECT_SCHEMA_JSON.encodeToJsonElement(
            GeneratedPrimitiveSchemaDocument.serializer(),
            GeneratedPrimitiveSchemaDocument(type),
        )
        .jsonObject

@Serializable
private data class GeneratedSchemaReference(@kotlinx.serialization.SerialName("\$ref") val reference: String)

@Serializable private data object UnconstrainedJsonSchema

@Serializable
private data class GeneratedObjectSchemaDocument(
    val type: String = "object",
    val additionalProperties: Boolean = false,
    val properties: Map<String, JsonElement>,
    val required: List<String>,
)

@Serializable private data class GeneratedUnionSchemaDocument(val oneOf: List<JsonElement>)

@Serializable
private data class GeneratedSealedUnionSchemaDocument(
    val anyOf: List<JsonElement>,
    val discriminator: GeneratedSchemaDiscriminatorDocument,
)

@Serializable private data class GeneratedSchemaDiscriminatorDocument(val propertyName: String)

@Serializable private data class GeneratedEnumDiscriminatorDocument(val enum: List<String>, val type: String = "string")

private val OBJECT_SCHEMA_JSON = Json { encodeDefaults = true }

private fun SerialDescriptor.objectSchema(references: CanonicalSchemaReferences): JsonObject =
    OBJECT_SCHEMA_JSON.encodeToJsonElement(
            GeneratedObjectSchemaDocument.serializer(),
            GeneratedObjectSchemaDocument(
                properties =
                    (0 until elementsCount).associate { index ->
                        getElementName(index) to
                            getElementDescriptor(index)
                                .toJsonSchema(
                                    getElementAnnotations(index),
                                    includeNullability = true,
                                    references = references,
                                )
                    },
                required = (0 until elementsCount).filterNot(::isElementOptional).map(::getElementName),
            ),
        )
        .jsonObject

private fun SerialDescriptor.sealedSchema(references: CanonicalSchemaReferences): JsonObject {
    val discriminator = annotations.filterIsInstance<JsonClassDiscriminator>().lastOrNull()?.discriminator ?: "type"
    val variants = getElementDescriptor(1)
    return OBJECT_SCHEMA_JSON.encodeToJsonElement(
            GeneratedSealedUnionSchemaDocument.serializer(),
            GeneratedSealedUnionSchemaDocument(
                anyOf =
                    (0 until variants.elementsCount).map { index ->
                        val tag = variants.getElementName(index).substringAfterLast('.')
                        variants
                            .getElementDescriptor(index)
                            .toJsonSchema(
                                emptyList(),
                                includeNullability = false,
                                references = references,
                                allowReference = false,
                            )
                            .withDiscriminator(discriminator, tag)
                    },
                discriminator = GeneratedSchemaDiscriminatorDocument(discriminator),
            ),
        )
        .jsonObject
}

private fun JsonObject.withDiscriminator(name: String, value: String): JsonObject {
    val properties = this["properties"] as? JsonObject ?: error("A sealed request variant must serialize as an object")
    val required = this["required"] as? JsonArray ?: error("A sealed request variant must declare required fields")
    return OBJECT_SCHEMA_JSON.encodeToJsonElement(
            GeneratedObjectSchemaDocument.serializer(),
            GeneratedObjectSchemaDocument(
                properties =
                    mapOf(
                        name to
                            OBJECT_SCHEMA_JSON.encodeToJsonElement(
                                GeneratedEnumDiscriminatorDocument.serializer(),
                                GeneratedEnumDiscriminatorDocument(listOf(value)),
                            )
                    ) + properties,
                required = listOf(name) + required.map { (it as JsonPrimitive).content },
            ),
        )
        .jsonObject
}
