package io.github.amichne.kast.cli.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpValidationResultSchemaTest {
    @Test
    fun `validation result admits opaque probe evidence`() {
        val evidence =
            Json.encodeToJsonElement(McpValidationDeclaration(McpValidationKind.CLASS, "Registry", "Registry.kt"))
        val result =
            McpValidationResult(
                data =
                    McpValidationData(
                        McpProbe.passed("Exact declaration", evidence),
                        McpProbe.unverified("No source probe requested"),
                        McpProbe.unverified("No relation probe requested"),
                        McpProbe.unverified("No diagnostic probe requested"),
                    )
            )
        assertTrue(McpStructuredResults.validates("validate_workspace", Json.encodeToJsonElement(result).jsonObject))
        val evidenceSchema =
            McpStructuredResults.validationSchema
                .getValue("anyOf")
                .jsonArray[0]
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("data")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("declarationQuery")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("evidence")
                .jsonObject
        assertTrue(evidenceSchema.isEmpty())
    }
}
