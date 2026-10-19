@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class CallbackSchemaFixture {
    private val definitions = installedServerOutputSchema(CanonicalOperation.QUERY_RUN).getValue("\$defs").jsonObject

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
    @SerialName("\$defs") val definitions: JsonObject,
)
