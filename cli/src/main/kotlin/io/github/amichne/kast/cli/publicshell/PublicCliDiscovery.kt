package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolExample
import io.github.amichne.kast.cli.generatedRequestSchema
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.cli.rpc.QueryCostPlanReply
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Each content branch has one serializer. No execution session participates in discovery. */
internal sealed interface PublicCliDescriptor {
    data class Inventory(val value: DescribeDocument<List<PublicCliCommandDescriptor>>) : PublicCliDescriptor

    data class Summary(val value: DescribeDocument<PublicCliCommandDescriptor>) : PublicCliDescriptor

    data class Schema(val value: DescribeDocument<JsonObject>) : PublicCliDescriptor

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

internal fun encodePublicCliDescriptor(descriptor: PublicCliDescriptor): String =
    when (descriptor) {
        is PublicCliDescriptor.Inventory -> discoveryJson.encodeToString(descriptor.value)
        is PublicCliDescriptor.Summary -> discoveryJson.encodeToString(descriptor.value)
        is PublicCliDescriptor.Schema -> discoveryJson.encodeToString(descriptor.value)
        is PublicCliDescriptor.Examples -> discoveryJson.encodeToString(descriptor.value)
    }

internal fun publicCliHelp(command: PublicCliCommandId?): String {
    val commands = command?.let(::listOf) ?: PublicCliCommandId.entries
    return commands.joinToString("\n") { selected ->
        "kast ${selected.argv.joinToString(" ")} " +
            flagsFor(selected).joinToString(" ") { flag ->
                listOf("[", flag.spelling, if (flag.takesValue) " VALUE" else "", "]").joinToString("")
            }
    } + "\nSemantic commands emit one JSON document. Use kast describe COMMAND for the selected catalog contract."
}

private fun commandDescriptor(command: PublicCliCommandId, catalog: PublicToolCatalog): PublicCliCommandDescriptor {
    val input = PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS, catalog)
    return PublicCliCommandDescriptor(
        command,
        listOf("kast") + command.argv,
        if (command == PublicCliCommandId.QUERY_PLAN || command == PublicCliCommandId.DESCRIBE) PublicCliEffect.NONE
        else PublicCliEffect.READ,
        if (command == PublicCliCommandId.QUERY || command == PublicCliCommandId.QUERY_PLAN) PublicCliInputMode.entries
        else listOf(PublicCliInputMode.FLAGS),
        flagsFor(command).map { flag -> flagDescriptor(flag, input, catalog) },
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

private fun flagDescriptor(
    flag: PublicCliFlag,
    input: JsonObject,
    catalog: PublicToolCatalog,
): PublicCliFlagDescriptor {
    val definitions = input.getValue("\$defs").jsonObject
    fun property(complete: String, common: String, property: String): JsonObject? =
        ((definitions[if (catalog == PublicToolCatalog.COMPLETE) complete else common] as? JsonObject)?.get(
                "properties"
            ) as? JsonObject)
            ?.get(property) as? JsonObject
    val schema =
        when (flag) {
            PublicCliFlag.NAME -> property("SearchSource", "Search", "declarationName")
            PublicCliFlag.NAME_MATCH -> property("SearchSource", "Search", "nameMatch")
            PublicCliFlag.KIND -> property("SearchSource", "Search", "declarationKinds")
            PublicCliFlag.DIRECTORY -> property("DirectoryScope", "Scope", "relativeDirectoryPath")
            PublicCliFlag.SOURCE_SET -> property("DirectoryScope", "Scope", "sourceSetNames")
            PublicCliFlag.FIELDS -> property("SymbolsOutput", "Symbols", "fields")
            PublicCliFlag.RELATION -> property("ExpandRelation", "Relation", "relation")
            PublicCliFlag.RETAIN -> property("RunAction", "Run", "retention")
            PublicCliFlag.MAX_RESULTS -> property("ExecutionBudget", "ExecutionBudget", "maxResults")
            PublicCliFlag.MAX_BYTES -> property("ExecutionBudget", "ExecutionBudget", "maxReturnedBytes")
            PublicCliFlag.MAX_ELAPSED -> property("ExecutionBudget", "ExecutionBudget", "maxElapsedMs")
            PublicCliFlag.CURSOR ->
                definitions["ResultCursor"] as? JsonObject ?: property("ReadResultAction", "ReadResultAction", "cursor")
            PublicCliFlag.EVIDENCE_CURSOR ->
                definitions["EvidenceCursor"] as? JsonObject
                    ?: property("ReadResultAction", "ReadResultAction", "evidence_cursor")
            PublicCliFlag.REGION -> property("ReadSourceAction", "ReadSource", "region")
            PublicCliFlag.FILE -> property("LocationSource", "LocationSource", "file")
            PublicCliFlag.OFFSET -> property("LocationSource", "LocationSource", "offset")
            PublicCliFlag.CONTINUATION -> property("ResumeAction", "Resume", "continuation")
            PublicCliFlag.VERBOSE -> input.getValue("properties").jsonObject["verbose"] as? JsonObject
            PublicCliFlag.WORKSPACE,
            PublicCliFlag.CATALOG,
            PublicCliFlag.JSON,
            PublicCliFlag.HELP,
            PublicCliFlag.REQUEST,
            PublicCliFlag.REF,
            PublicCliFlag.TERMINAL,
            PublicCliFlag.OUTPUT,
            PublicCliFlag.TEXT,
            PublicCliFlag.SECTION -> null
        }
    val values =
        when (flag) {
            PublicCliFlag.CATALOG -> PublicToolCatalog.entries.map { it.name.lowercase() }
            PublicCliFlag.TERMINAL -> listOf("emit", "trace")
            PublicCliFlag.OUTPUT -> listOf("symbols", "occurrences")
            PublicCliFlag.TEXT -> listOf("complete", "none")
            PublicCliFlag.SECTION -> listOf("input", "output", "examples")
            else -> enumSpellings(schema)
        }
    return PublicCliFlagDescriptor(flag.spelling, flag.takesValue, flag.repeated, schema, values)
}

private fun enumSpellings(schema: JsonObject?): List<String> {
    val values = schema?.get("enum") as? JsonArray ?: (schema?.get("items") as? JsonObject)?.get("enum") as? JsonArray
    return values.orEmpty().mapNotNull { value ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()?.replace('_', '-')
    }
}

private fun inputSchema(command: PublicCliCommandId, catalog: PublicToolCatalog): JsonObject =
    when (command) {
        PublicCliCommandId.DESCRIBE -> generatedRequestSchema(DescribeInput.serializer())
        PublicCliCommandId.QUERY -> PublicToolContract.parameters(PublicToolIdentity.QUERY_SYMBOLS, catalog)
        PublicCliCommandId.QUERY_PLAN -> actionInputSchema("RUN", catalog)
        PublicCliCommandId.RESULT_READ -> actionInputSchema("READ_RESULT", catalog)
        PublicCliCommandId.RESULT_RESUME -> actionInputSchema("RESUME", catalog)
        PublicCliCommandId.SOURCE_READ -> actionInputSchema("READ_SOURCE", catalog)
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
        PublicCliCommandId.QUERY_PLAN -> generatedRequestSchema(QueryCostPlanReply.serializer())
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
                            generatedRequestSchema(
                                DescribeDocument.serializer(ListSerializer(PublicToolExample.serializer()))
                            ),
                        )
                    )
                )
                .jsonObject
        else -> semanticReplySchema()
    }

private fun semanticReplySchema(): JsonObject {
    val semantic = McpStructuredResults.semanticSchemaFor(PublicToolIdentity.QUERY_SYMBOLS.toolName)
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
private data class DescribeInput(val command: PublicCliCommandId? = null, val section: DescribeInputSection? = null)

@Serializable
private enum class DescribeInputSection {
    @SerialName("input") INPUT,
    @SerialName("output") OUTPUT,
    @SerialName("examples") EXAMPLES,
}

private val discoveryJson = Json {
    encodeDefaults = true
    explicitNulls = false
}
