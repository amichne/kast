package io.github.amichne.kast.distribution.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ConnectionConfigurationContractTest {
    private val schemaDocument =
        checkNotNull(javaClass.getResource("/management/connection-configuration.schema.json")).readText()
    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaDocument)

    @Test
    fun encodedConfigurationHasIndependentRequiredShapeForEveryConnection() {
        val expected = Json.parseToJsonElement(schemaDocument).jsonObject.getValue("examples").jsonArray.single()
        val document =
            ConnectionConfigurationDocument(
                1,
                listOf(
                    SavedConnectionDirectory(HarnessConnection.CODEX_MCP, "/Users/developer/.codex"),
                    SavedConnectionDirectory(HarnessConnection.CODEX_APP_SERVER, "/Users/developer/.local/bin"),
                    SavedConnectionDirectory(HarnessConnection.COPILOT, "/Users/developer/.copilot"),
                    SavedConnectionDirectory(HarnessConnection.PI, "/Users/developer/.config/pi/agent"),
                ),
            )
        val encoded = managementJson.encodeToString(document)
        assertEquals(expected, Json.parseToJsonElement(encoded))
        assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty())
        val decoded = ConnectionConfiguration.decode(encoded) as ConnectionConfigurationAdmission.Admitted
        assertEquals(document, decoded.configuration.document())
        assertTrue(
            schema
                .validate(managementJson.encodeToString(ConnectionConfiguration.empty().document()), InputFormat.JSON)
                .isEmpty()
        )
    }

    @Test
    fun parserAndSchemaRejectUnknownMissingExtraDuplicateAndMalformedFacts() {
        // Deliberately invalid boundary documents.
        val invalid =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/management/connection-config-invalid-shapes.json")).readText()
                )
                .jsonArray
                .map { it.toString() }
        for (raw in invalid) {
            assertTrue(schema.validate(raw, InputFormat.JSON).isNotEmpty(), raw)
            assertTrue(ConnectionConfiguration.decode(raw) is ConnectionConfigurationAdmission.Rejected, raw)
        }
    }
}
