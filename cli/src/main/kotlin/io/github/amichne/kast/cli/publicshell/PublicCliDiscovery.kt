package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolExample
import io.github.amichne.kast.cli.generatedRequestSchema
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.cli.queryPlanOutputSchema
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_CONTRACT_VERSION
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Each content branch has one serializer. No execution session participates in discovery. */
internal sealed interface PublicCliDescriptor {
    data class Inventory(val value: DescribeDocument<List<PublicCliCommandDescriptor>>) : PublicCliDescriptor

    data class Summary(val value: DescribeDocument<PublicCliCommandDescriptor>) : PublicCliDescriptor

    data class Schema(val value: DescribeDocument<JsonObject>) : PublicCliDescriptor

    data class SchemaPage(val value: DescribeDocument<PublicSchemaPageReply>) : PublicCliDescriptor

    data class Examples(val value: DescribeDocument<List<PublicToolExample>>) : PublicCliDescriptor
}

@Serializable
internal data class DescribeDocument<Content>(
    val catalog: PublicToolCatalog,
    val section: PublicCliDescribeSection,
    val content: Content,
    val command: PublicCliCommandId? = null,
    @Required val schema: String = "kast.cli.describe.v1",
    @Required @SerialName("public_contract_version") val publicContractVersion: Int = PUBLIC_TOOL_CONTRACT_VERSION,
)

@Serializable
internal data class PublicCliCommandDescriptor(
    val command: PublicCliCommandId,
    val argv: List<String>,
    val effect: PublicCliEffect,
    @SerialName("input_modes") val inputModes: List<PublicCliInputMode>,
    val flags: List<PublicCliFlagDescriptor>,
    @SerialName("input_schema") val inputSchema: String,
    @SerialName("output_schema") val outputSchema: String,
    @SerialName("catalog_restriction") val catalogRestriction: String,
)

@Serializable
internal enum class PublicCliEffect {
    NONE,
    READ,
}

@Serializable
internal enum class PublicCliInputMode {
    FLAGS,
    FILE,
    STDIN,
}

@Serializable
internal data class PublicCliFlagDescriptor(
    val name: String,
    @SerialName("takes_value") val takesValue: Boolean,
    val repeated: Boolean,
    /** The packaged JSON Schema fragment is dynamic schema data, not a second semantic contract. */
    val schema: JsonObject? = null,
    @SerialName("value_spellings") val valueSpellings: List<String> = emptyList(),
)

internal fun describePublicCli(command: PublicCliCommand.Describe): PublicCliDescriptor {
    val selected = command.command
    if (command.selection != null) return describeSchemaPage(command)
    return when (command.section) {
        PublicCliDescribeSection.INVENTORY ->
            PublicCliDescriptor.Inventory(
                DescribeDocument(
                    command.catalog,
                    command.section,
                    PublicCliCommandId.entries.map { commandDescriptor(it, command.catalog) },
                )
            )
        PublicCliDescribeSection.SUMMARY ->
            PublicCliDescriptor.Summary(
                DescribeDocument(
                    command.catalog,
                    command.section,
                    commandDescriptor(requireNotNull(selected), command.catalog),
                    selected,
                )
            )
        PublicCliDescribeSection.INPUT ->
            PublicCliDescriptor.Schema(
                DescribeDocument(
                    command.catalog,
                    command.section,
                    inputSchema(requireNotNull(selected), command.catalog),
                    selected,
                )
            )
        PublicCliDescribeSection.OUTPUT ->
            PublicCliDescriptor.Schema(
                DescribeDocument(command.catalog, command.section, outputSchema(requireNotNull(selected)), selected)
            )
        PublicCliDescribeSection.EXAMPLES ->
            PublicCliDescriptor.Examples(
                DescribeDocument(
                    command.catalog,
                    command.section,
                    examples(requireNotNull(selected), command.catalog),
                    selected,
                )
            )
    }
}

private fun describeSchemaPage(command: PublicCliCommand.Describe): PublicCliDescriptor.SchemaPage {
    val selected = requireNotNull(command.command)
    val schema =
        when (command.section) {
            PublicCliDescribeSection.INPUT -> inputSchema(selected, command.catalog)
            PublicCliDescribeSection.OUTPUT -> outputSchema(selected)
            else -> error("Schema selection requires input or output")
        }
    return PublicCliDescriptor.SchemaPage(browsePublicSchema(schema, command))
}

internal fun encodePublicCliDescriptor(descriptor: PublicCliDescriptor): String =
    when (descriptor) {
        is PublicCliDescriptor.Inventory -> discoveryJson.encodeToString(descriptor.value)
        is PublicCliDescriptor.Summary -> discoveryJson.encodeToString(descriptor.value)
        is PublicCliDescriptor.Schema -> discoveryJson.encodeToString(descriptor.value)
        is PublicCliDescriptor.SchemaPage -> discoveryJson.encodeToString(descriptor.value)
        is PublicCliDescriptor.Examples -> discoveryJson.encodeToString(descriptor.value)
    }

private fun commandDescriptor(command: PublicCliCommandId, catalog: PublicToolCatalog): PublicCliCommandDescriptor {
    val input = PublicToolContract.parameters(command.toolIdentity(), catalog)
    return PublicCliCommandDescriptor(
        command,
        listOf("kast") + command.argv,
        if (command == PublicCliCommandId.QUERY_PLAN || command == PublicCliCommandId.DESCRIBE) PublicCliEffect.NONE
        else PublicCliEffect.READ,
        if (
            command in
                setOf(PublicCliCommandId.QUERY, PublicCliCommandId.QUERY_PLAN, PublicCliCommandId.DIAGNOSTIC_CHECK)
        )
            PublicCliInputMode.entries
        else listOf(PublicCliInputMode.FLAGS),
        flagsFor(command).map { flag -> flagDescriptor(flag, input, catalog, command) },
        "kast.cli.${command.id}.input.v1:${catalog.name}",
        when (command) {
            PublicCliCommandId.QUERY_PLAN -> "kast.query.plan.v1"
            PublicCliCommandId.DESCRIBE -> "kast.cli.describe.v1"
            else -> "kast.cli.query.reply.v1"
        },
        when (catalog) {
            PublicToolCatalog.COMMON ->
                "COMMON admits only the packaged common grammar. Unsupported options reject without catalog promotion."
            PublicToolCatalog.COMPLETE -> "COMPLETE admits the complete packaged query_symbols grammar."
        },
    )
}

private fun inputSchema(command: PublicCliCommandId, catalog: PublicToolCatalog): JsonObject =
    when (command) {
        PublicCliCommandId.DESCRIBE -> generatedRequestSchema(DescribeInput.serializer())
        PublicCliCommandId.QUERY -> PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS, catalog)
        PublicCliCommandId.QUERY_PLAN -> actionInputSchema("RUN", catalog)
        PublicCliCommandId.RESULT_READ -> actionInputSchema("READ_RESULT", catalog)
        PublicCliCommandId.RESULT_RESUME -> actionInputSchema("RESUME", catalog)
        PublicCliCommandId.SOURCE_READ -> actionInputSchema("READ_SOURCE", catalog)
        PublicCliCommandId.DIAGNOSTIC_CHECK ->
            PublicToolContract.parameters(PublicToolIdentity.CHECK_DIAGNOSTICS, catalog)
    }

private fun actionInputSchema(action: String, catalog: PublicToolCatalog): JsonObject {
    val input = PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS, catalog)
    val tag = ReplyTypeSchema(ReplyTypeProperty(SchemaTag(listOf(action))))
    val constraint = discoveryJson.encodeToJsonElement(ActionInputConstraint(ActionInputProperties(tag))).jsonObject
    return discoveryJson
        .encodeToJsonElement(PublicCliReplySchema(listOf(input, constraint), input["\$defs"] as? JsonObject))
        .jsonObject
}

private fun outputSchema(command: PublicCliCommandId): JsonObject =
    when (command) {
        PublicCliCommandId.QUERY_PLAN -> queryPlanOutputSchema()
        PublicCliCommandId.DESCRIBE ->
            discoveryJson
                .encodeToJsonElement(
                    SchemaUnion(
                        listOf(
                            generatedRequestSchema(
                                DescribeDocument.serializer(PublicCliCommandDescriptor.serializer())
                            ),
                            generatedRequestSchema(
                                DescribeDocument.serializer(ListSerializer(PublicCliCommandDescriptor.serializer()))
                            ),
                            generatedRequestSchema(DescribeDocument.serializer(JsonObject.serializer())),
                            generatedRequestSchema(DescribeDocument.serializer(PublicSchemaPageReply.serializer())),
                            generatedRequestSchema(
                                DescribeDocument.serializer(ListSerializer(PublicToolExample.serializer()))
                            ),
                        )
                    )
                )
                .jsonObject
        else -> semanticReplySchema(command.toolIdentity())
    }

private fun semanticReplySchema(identity: PublicToolIdentity): JsonObject {
    val semantic = McpStructuredResults.semanticSchemaFor(identity.toolName)
    val variants =
        listOf("complete", "qualified", "rejected_document").map { outcome ->
            val payload =
                if (outcome == "rejected_document")
                    discoveryJson
                        .encodeToJsonElement(
                            SchemaUnion(
                                listOf(semantic, generatedRequestSchema(CliBoundaryDocuments.boundarySerializer))
                            )
                        )
                        .jsonObject
                else semantic
            discoveryJson
                .encodeToJsonElement(ReplyDocumentSchema(ReplyDocumentProperties(SchemaTag(listOf(outcome)), payload)))
                .jsonObject
        } +
            discoveryJson
                .encodeToJsonElement(ReplyTypeSchema(ReplyTypeProperty(SchemaTag(listOf("rejected")))))
                .jsonObject
    return discoveryJson
        .encodeToJsonElement(
            PublicCliReplySchema(
                listOf(
                    generatedRequestSchema(ToolRpcReply.serializer()),
                    discoveryJson.encodeToJsonElement(SchemaUnion(variants)).jsonObject,
                ),
                semantic["\$defs"] as? JsonObject,
            )
        )
        .jsonObject
}

private fun examples(command: PublicCliCommandId, catalog: PublicToolCatalog): List<PublicToolExample> {
    val action =
        when (command) {
            PublicCliCommandId.QUERY -> null
            PublicCliCommandId.QUERY_PLAN -> "RUN"
            PublicCliCommandId.RESULT_READ -> "READ_RESULT"
            PublicCliCommandId.RESULT_RESUME -> "RESUME"
            PublicCliCommandId.SOURCE_READ -> "READ_SOURCE"
            PublicCliCommandId.DIAGNOSTIC_CHECK ->
                return PublicToolContract.examples(PublicToolIdentity.CHECK_DIAGNOSTICS, catalog)
                    .examples
                    .values
                    .toList()
            PublicCliCommandId.DESCRIBE -> return emptyList()
        }
    return PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS, catalog).examples.values.filter { example ->
        action == null ||
            (example.value.jsonObject["request"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == action
    }
}

@Serializable
private data class PublicCliReplySchema(
    val allOf: List<JsonObject>,
    @SerialName("\$defs") val definitions: JsonObject?,
    @Required val type: String = "object",
)

@Serializable private data class SchemaUnion(val anyOf: List<JsonObject>)

@Serializable
private data class ReplyDocumentSchema(
    val properties: ReplyDocumentProperties,
    @Required val type: String = "object",
    @Required val required: List<String> = listOf("type", "document"),
    @Required val additionalProperties: Boolean = false,
)

@Serializable private data class ReplyDocumentProperties(val type: SchemaTag, val document: JsonObject)

@Serializable private data class ReplyTypeSchema(val properties: ReplyTypeProperty)

@Serializable private data class ReplyTypeProperty(val type: SchemaTag)

@Serializable private data class SchemaTag(val enum: List<String>, @Required val type: String = "string")

@Serializable private data class ActionInputConstraint(val properties: ActionInputProperties)

@Serializable private data class ActionInputProperties(val request: ReplyTypeSchema)

@Serializable
private data class DescribeInput(
    val command: PublicCliCommandId? = null,
    val section: DescribeInputSection? = null,
    val pointer: String? = null,
    val cursor: Int? = null,
    @SerialName("max_results") val maxResults: Int? = null,
    @SerialName("max_returned_bytes") val maxReturnedBytes: Int? = null,
)

@Serializable
private enum class DescribeInputSection {
    @SerialName("input") INPUT,
    @SerialName("output") OUTPUT,
    @SerialName("examples") EXAMPLES,
}

internal val discoveryJson = Json {
    encodeDefaults = true
    explicitNulls = false
}
