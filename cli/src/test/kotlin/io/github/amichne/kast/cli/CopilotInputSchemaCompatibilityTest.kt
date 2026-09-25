@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.protocol.contract.SourceContainmentDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.SourceDeclarationVisibilityDocument
import io.github.amichne.kast.protocol.contract.SourceEntityFilterDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceVisibilitySelectionDocument
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CopilotInputSchemaCompatibilityTest {
    private val schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val fixtureJson = Json { encodeDefaults = true }
    private val publishedEntities by lazy {
        val choices =
            installedTools()
                .single { it.getValue("name").jsonPrimitive.content == "source_read" }
                .getValue("inputSchema")
                .jsonObject
                .getValue("anyOf")
                .jsonArray
        Json.encodeToJsonElement(
                mapOf(
                    "anyOf" to
                        Json.encodeToJsonElement(
                            choices.mapNotNull { choice ->
                                choice.jsonObject["properties"]?.jsonObject?.get("entities")
                            }
                        )
                )
            )
            .jsonObject
    }
    private val generatedEntities by lazy {
        generatedRequestSchema(SourceReadRequest.serializer()).getValue("properties").jsonObject.getValue("entities")
    }

    @Test
    fun `every installed tool input stays within two composition levels`() {
        val violations =
            installedTools().flatMap { tool ->
                nestedCompositions(tool.getValue("inputSchema"), tool.getValue("name").jsonPrimitive.content)
            }
        assertTrue(violations.isEmpty(), "Copilot cannot load a third composition level: $violations")
    }

    @Test
    fun `source filter choices retain every typed variant after flattening`() {
        val declarations = listOf(SourceDeclarationKindDocument.FUNCTION)
        val values = listOf(SourceDeclarationVisibilityDocument.PUBLIC)
        val choices: List<SourceEntitySelectionDocument> =
            listOf(
                SourceEntitySelectionDocument.None,
                matching(SourceEntityFilterDocument.Calls),
                matching(SourceEntityFilterDocument.Declarations(declarations, SourceVisibilitySelectionDocument.Any)),
                matching(
                    SourceEntityFilterDocument.Declarations(
                        declarations,
                        SourceVisibilitySelectionDocument.Exact(values),
                    )
                ),
                matching(SourceEntityFilterDocument.Parameters),
                matching(SourceEntityFilterDocument.References),
            )
        choices.forEach { assertEntitiesAdmitted(Json.encodeToString(SourceEntitySelectionDocument.serializer(), it)) }
    }

    @Test
    fun `source filter choices reject missing or contradictory visibility`() {
        val invalid =
            listOf(
                InvalidMatching(filters = listOf(InvalidDeclaration())),
                InvalidMatching(filters = listOf(InvalidDeclaration(visibility = InvalidVisibility("exact")))),
                InvalidMatching(
                    filters = listOf(InvalidDeclaration(visibility = InvalidVisibility("any", listOf("public"))))
                ),
                InvalidMatching(filters = listOf(InvalidDeclaration(type = "unknown"))),
            )
        invalid.forEach { assertEntitiesRejected(fixtureJson.encodeToString(it)) }
    }

    private fun matching(filter: SourceEntityFilterDocument): SourceEntitySelectionDocument.Matching =
        SourceEntitySelectionDocument.Matching(SourceContainmentDocument.DIRECT, listOf(filter))

    private fun assertEntitiesAdmitted(document: String) = assertEntities(document, true)

    private fun assertEntitiesRejected(document: String) = assertEntities(document, false)

    private fun assertEntities(document: String, accepted: Boolean) {
        assertEquals(accepted, admits(generatedEntities, document), "generated: $document")
        assertEquals(accepted, admits(publishedEntities, document), "published: $document")
    }

    private fun admits(schema: JsonElement, document: String): Boolean =
        schemas.getSchema(schema.toString()).validate(document, InputFormat.JSON).isEmpty()

    private fun installedTools(): List<JsonObject> {
        val graph =
            (CliCommandGraphFactory.create(canonicalCliRequestPreparers()) as CliCommandGraphConstruction.Created)
                .factory
        val emptyMetadata = fixtureJson.encodeToString(EmptyMetadata)
        val document =
            (installedSchema(emptyMetadata, emptyMetadata, graph.surface) as InstalledSchemaConstruction.Constructed)
                .document
                .value
        return Json.parseToJsonElement(document)
            .jsonObject
            .getValue("serverProjection")
            .jsonObject
            .getValue("hostedBootstrap")
            .jsonObject
            .getValue("tools")
            .jsonArray
            .map(JsonElement::jsonObject)
    }

    private fun nestedCompositions(schema: JsonElement, path: String, parentCount: Int = 0): List<String> =
        when (schema) {
            is JsonObject ->
                schema.flatMap { (key, value) ->
                    val composition = key in setOf("anyOf", "oneOf", "allOf")
                    val location = "$path/$key"
                    (if (composition && parentCount >= 2) listOf(location) else emptyList()) +
                        nestedCompositions(value, location, parentCount + if (composition) 1 else 0)
                }
            is JsonArray ->
                schema.flatMapIndexed { index, value ->
                    nestedCompositions(value, "$path/$index", parentCount)
                }
            else -> emptyList()
        }

    @Serializable
    private data class InvalidMatching(
        val type: String = "matching",
        val containment: String = "direct",
        val filters: List<InvalidDeclaration>,
    )

    @Serializable
    private data class InvalidDeclaration(
        val type: String = "declaration",
        val kinds: List<String> = listOf("function"),
        @EncodeDefault(EncodeDefault.Mode.NEVER) val visibility: InvalidVisibility? = null,
    )

    @Serializable
    private data class InvalidVisibility(
        val type: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val values: List<String>? = null,
    )

    @Serializable private data object EmptyMetadata
}
