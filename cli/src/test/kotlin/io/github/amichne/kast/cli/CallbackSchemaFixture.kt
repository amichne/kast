@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactCaptureInvocationDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class CallbackSchemaFixture(schema: JsonObject = installedServerOutputSchema(CanonicalOperation.QUERY_RUN)) {
    private val definitions = schema.getValue("\$defs").jsonObject

    fun definition(name: String): JsonObject = definitions.getValue(name).jsonObject

    fun validator(name: String) =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                Json.encodeToString(DefinitionSchema.serializer(), DefinitionSchema("#/\$defs/$name", definitions))
            )
}

@Serializable
private data class DefinitionSchema(
    @SerialName("\$ref") val reference: String,
    @SerialName("\$defs") val definitions: Map<String, JsonElement>,
)

/** Independent of installed factoring: exact invocation and callback-capture descriptors end recursive expansion. */
internal fun independentImpactSchema(serializer: KSerializer<*>): JsonObject =
    generatedOutputSchema(
        serializer,
        CanonicalSchemaReferences.from(
            mapOf(
                "impactInvocationReference" to ImpactInvocationReferenceDocument.serializer(),
                "impactCaptureInvocation" to ImpactCaptureInvocationDocument.serializer(),
            )
        ),
    )

/** Validation reuses the exact site descriptor; structural comparisons retain the expanded independent schema. */
internal fun independentImpactSchemaWithDefinitions(serializer: KSerializer<*>): JsonObject {
    val shared =
        mapOf(
            "impactInvocationReference" to ImpactInvocationReferenceDocument.serializer(),
            "impactValueSiteReference" to ImpactValueSiteReferenceDocument.serializer(),
            "impactCaptureInvocation" to ImpactCaptureInvocationDocument.serializer(),
        )
    val references = CanonicalSchemaReferences.from(shared)
    return Json.encodeToJsonElement(
            DefinitionSchema.serializer(),
            DefinitionSchema(
                reference = "#/\$defs/independentRoot",
                definitions =
                    mapOf("independentRoot" to generatedOutputSchema(serializer, references)) +
                        shared.mapValues { (_, definition) -> generatedOutputSchema(definition, references) },
            ),
        )
        .jsonObject
}
