package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledServerChangeProjectionTest {
    @Test
    fun `installed broker exposes workflow facade names and explicit change approval`() {
        val tools = projectionTools()
        val invocations = projectionInvocations()

        assertEquals(
            listOf(
                "search_classes",
                "search_functions",
                "search_declarations",
                "query_symbols",
                "symbol_lookup",
                "symbol_inspect",
                "source_read",
                "semantic_query",
                "impact_analyze",
                "check_diagnostics",
                "change_plan",
                "change_apply",
                "change_recover",
            ),
            tools.map { it.getValue("name").jsonPrimitive.content },
        )
        assertEquals(listOf("symbol", "inspect"), invocations.invocation("symbol_inspect").cliCommand())
        assertTrue(
            tools
                .filter { it.getValue("name").jsonPrimitive.content in setOf("change_apply", "change_recover") }
                .all { tool ->
                    tool.getValue("approvalPolicy").jsonPrimitive.content ==
                        HostedApprovalPolicy.EXPLICIT.name.lowercase()
                }
        )
        assertTrue(
            tools
                .filterNot { it.getValue("name").jsonPrimitive.content.startsWith("change_") }
                .all { tool ->
                    tool.getValue("approvalPolicy").jsonPrimitive.content == HostedApprovalPolicy.NONE.name.lowercase()
                }
        )
    }

    @Test
    fun `change schemas admit their emitted proof carrying previews`() {
        val tools = projectionTools()
        val preview =
            """"changes":[{"path":"src/main/kotlin/demo/EventConsumer.kt","kind":"update",""" +
                """"diff":"@@ class EventConsumer @@\n-old\n+new"}]"""

        tools
            .tool("change_plan")
            .outputSchema()
            .assertAdmits(
                """{"status":"completed","document":{"operation":"change.plan","status":"complete",""" +
                    """"planIdentity":"plan:opaque",$preview}}"""
            )
        tools
            .tool("change_apply")
            .outputSchema()
            .assertAdmits(
                """{"status":"completed","document":{"operation":"change.apply","status":"complete",""" +
                    """"state":"verified","receiptIdentity":"receipt:opaque",$preview}}"""
            )
    }

    @Test
    fun `hosted change output schemas admit live provenance and reject malformed provenance`() {
        val live =
            """"live":{"root":"/workspace","host":"00000000-0000-0000-0000-000000000001",
            "epoch":8,"contentView":"SAVED_PSI_COMMITTED","version":1}"""
        val preview = """"changes":[{"path":"src/Target.kt","kind":"update","diff":"+fun added() = 1"}]"""
        val cases =
            mapOf(
                "change_plan" to """"operation":"change.plan","planIdentity":"plan:opaque",$preview""",
                "change_apply" to
                    """"operation":"change.apply","state":"verified","receiptIdentity":"receipt:opaque",$preview""",
                "change_recover" to """"operation":"change.recover","state":"rolled-back"""",
            )
        val tools = projectionTools()
        for ((tool, payload) in cases) {
            val schema = tools.tool(tool).outputSchema()
            val document = """{"status":"completed","document":{"status":"complete",$payload,$live}}"""
            schema.assertAdmits(document)
            assertTrue(schema.validate(document.replace("\"version\":1", "\"version\":2")).isNotEmpty())
            assertTrue(schema.validate(document.replace("\"epoch\":8,", "")).isNotEmpty())
        }
    }

    private fun commandGraphFactory(): CliCommandGraphFactory =
        when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error(construction.failures)
        }

    private fun projectionTools(): List<JsonObject> {
        return installedProjection()
            .getValue("hostedBootstrap")
            .jsonObject
            .getValue("tools")
            .jsonArray
            .map(JsonElement::jsonObject)
    }

    private fun installedProjection(): JsonObject {
        val schema =
            installedSchema(
                    operationRegistry = "{}",
                    wireSchema = "{}",
                    commandSurface = commandGraphFactory().surface,
                )
                .constructedDocument()
        return Json.parseToJsonElement(schema.value).jsonObject.getValue("serverProjection").jsonObject
    }

    private fun projectionInvocations(): List<JsonObject> =
        installedProjection()
            .getValue("cliInvocations")
            .jsonObject
            .getValue("operations")
            .jsonArray
            .map(JsonElement::jsonObject)

    private fun InstalledSchemaConstruction.constructedDocument(): CliJsonDocument =
        when (this) {
            is InstalledSchemaConstruction.Constructed -> document
            is InstalledSchemaConstruction.Rejected -> error(failure)
        }

    private fun JsonObject.cliCommand(): List<String> =
        getValue("invocation").jsonObject.getValue("command").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.outputSchema(): JsonObject = getValue("outputSchema").jsonObject

    private fun JsonObject.assertAdmits(document: String) {
        val messages = validate(document)
        assertTrue(messages.isEmpty(), "schema rejected emitted document: $messages")
    }

    private fun JsonObject.validate(document: String): Set<String> =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(toString())
            .validate(document, InputFormat.JSON)
            .mapTo(linkedSetOf()) { it.message }

    private fun List<JsonObject>.tool(name: String): JsonObject = single {
        it.getValue("name").jsonPrimitive.content == name
    }

    private fun List<JsonObject>.invocation(name: String): JsonObject = single {
        it.getValue("toolName").jsonPrimitive.content == name
    }
}
