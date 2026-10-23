@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
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

/** Independent of installed factoring: only the exact recursive invocation descriptor is cut. */
internal fun independentImpactSchema(serializer: KSerializer<*>): JsonObject =
    generatedOutputSchema(
        serializer,
        CanonicalSchemaReferences.from(
            mapOf("impactInvocationReference" to ImpactInvocationReferenceDocument.serializer())
        ),
    )

internal fun independentImpactSchemaWithDefinitions(serializer: KSerializer<*>): JsonObject =
    Json.encodeToJsonElement(
            DefinitionSchema.serializer(),
            DefinitionSchema(
                reference = "#/\$defs/independentRoot",
                definitions =
                    mapOf(
                        "independentRoot" to independentImpactSchema(serializer),
                        "impactInvocationReference" to
                            independentImpactSchema(ImpactInvocationReferenceDocument.serializer()),
                    ),
            ),
        )
        .jsonObject
