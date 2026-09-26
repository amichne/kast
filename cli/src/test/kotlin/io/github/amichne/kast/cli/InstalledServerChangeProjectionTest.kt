package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.protocol.contract.ChangeRejection
import io.github.amichne.kast.protocol.contract.ChangeRunDocument
import io.github.amichne.kast.protocol.contract.ChangeRunError
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledServerChangeProjectionTest {
    @Test
    fun `installed broker exposes one change tool without approval`() {
        val tools = projectionTools()
        val invocations = projectionInvocations()

        assertEquals(
            listOf(
                "workspace_lifecycle",
                "query_symbols",
                "symbol_lookup",
                "symbol_inspect",
                "source_read",
                "check_diagnostics",
                "change",
            ),
            tools.map { it.getValue("name").jsonPrimitive.content },
        )
        assertEquals(listOf("symbol", "inspect"), invocations.invocation("symbol_inspect").cliCommand())
        assertEquals(
            HostedApprovalPolicy.NONE.name.lowercase(),
            tools.tool("change").getValue("approvalPolicy").jsonPrimitive.content,
        )
        assertTrue(
            tools
                .filterNot {
                    it.getValue("name").jsonPrimitive.content == "workspace_lifecycle"
                }
                .all { tool ->
                    tool.getValue("approvalPolicy").jsonPrimitive.content == HostedApprovalPolicy.NONE.name.lowercase()
                }
        )
    }

    @Test
    fun `change schema admits complete and rejected one call outcomes`() {
        val tools = projectionTools()
        val schema = tools.tool("change").outputSchema()
        schema.assertAdmits(completeChange())
        schema.assertAdmits(rejectedChange())
    }

    @Test
    fun `hosted change output rejects unknown result and failure variants`() {
        val schema = projectionTools().tool("change").outputSchema()
        val unknownResult = completeChange().replace("\"status\":\"complete\"", "\"status\":\"unknown\"")
        assertTrue(schema.validate(unknownResult).isNotEmpty())
        assertTrue(schema.validate(rejectedChange().replace("PLANNING_REJECTED", "UNKNOWN")).isNotEmpty())
    }

    private fun completeChange(): String =
        changeTestJson.encodeToString(
            TestChangeEnvelope(
                document =
                    ChangeRunDocument.Complete(
                        "plan:opaque",
                        changeTestJson.encodeToJsonElement(TestChangePhase()).jsonObject,
                        changeTestJson.encodeToJsonElement(TestChangePhase(state = "verified")).jsonObject,
                    )
            )
        )

    private fun rejectedChange(): String =
        changeTestJson.encodeToString(
            TestChangeEnvelope(document = ChangeRunDocument.Rejected(ChangeRunError(ChangeRejection.PLANNING_REJECTED)))
        )

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

    private fun InstalledSchemaConstruction.constructedDocument(): CanonicalJsonDocument =
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

@Serializable private data class TestChangeEnvelope(val status: String = "completed", val document: ChangeRunDocument)

@Serializable private data class TestChangePhase(val status: String = "complete", val state: String? = null)

private val changeTestJson = Json { encodeDefaults = true }
