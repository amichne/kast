package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val SERVER_PROJECTION_SCHEMA_VERSION = 1
private const val MAXIMUM_PROTOCOL_TEXT_LENGTH = 16_384
private const val MAXIMUM_WORKSPACE_FILE_LENGTH = 4_096
private const val MAXIMUM_PROTOCOL_COUNT = 1_000

/** One closed server-facing projection owned by this installed command graph. */
@Serializable
internal data class InstalledServerProjectionDocument(
    val schemaVersion: Int,
    val namespace: String,
    val tools: List<InstalledServerToolDocument>,
)

@Serializable
internal data class InstalledServerToolDocument(
    val operationId: String,
    val name: String,
    val description: String,
    val deferLoading: Boolean,
    val cliUsage: String,
    val inputSchema: JsonElement,
    val outputSchema: JsonElement,
    val invocation: InstalledServerCliInvocationDocument,
)

@Serializable
internal data class InstalledServerCliInvocationDocument(
    val type: InstalledServerInvocationType,
    val command: List<String>,
    val bindings: List<InstalledServerCliBindingDocument>,
)

@Serializable
internal enum class InstalledServerInvocationType {
    CLI,
}

@Serializable
internal data class InstalledServerCliBindingDocument(
    val type: InstalledServerBindingType,
    val inputField: String,
    val option: String,
)

@Serializable
internal enum class InstalledServerBindingType {
    OPTION,
}

/**
 * Proof transition: `CliCommandSurface -> InstalledServerProjectionDocument`.
 *
 * The proven canonical command graph supplies exact operation usage while this closed projection
 * supplies the corresponding server name, JSON shapes, and CLI binding grammar. The resulting
 * document is the sole broker-facing authority for an installed executable; no runtime or
 * filesystem input is interpreted here.
 */
internal fun installedServerProjection(
    commandSurface: CliCommandSurface,
): InstalledServerProjectionDocument {
    val commandByOperation = commandSurface.semanticCommands.associateBy { it.operation }
    return InstalledServerProjectionDocument(
        schemaVersion = SERVER_PROJECTION_SCHEMA_VERSION,
        namespace = "kast",
        tools = InstalledServerTool.entries.map { tool ->
            tool.document(commandByOperation.getValue(tool.operation).usage)
        },
    )
}

private enum class InstalledServerTool(
    val operation: CanonicalOperation,
    private val toolName: String,
    private val toolDescription: String,
    private val inputSchema: JsonObject,
    private val command: List<String>,
    private val optionFields: List<ServerCliOptionField>,
) {
    SYMBOL_DISCOVER(
        operation = CanonicalOperation.SYMBOL_DISCOVER,
        toolName = "symbol_discover",
        toolDescription =
            "Discover bounded Kotlin symbol candidates through Kast's canonical operation.",
        inputSchema = symbolDiscoverInputSchema(),
        command = listOf("symbol", "discover"),
        optionFields = listOf(
            ServerCliOptionField("mode", "--mode"),
            ServerCliOptionField("query", "--query"),
            ServerCliOptionField("kind", "--kind"),
            ServerCliOptionField("match", "--match"),
            ServerCliOptionField("file", "--file"),
            ServerCliOptionField("offset", "--offset"),
            ServerCliOptionField("scope", "--scope"),
            ServerCliOptionField("limit", "--limit"),
        ),
    ),
    SYMBOL_RESOLVE(
        operation = CanonicalOperation.SYMBOL_RESOLVE,
        toolName = "symbol_resolve",
        toolDescription =
            "Refine one Kast discovery candidate to an exact generation-bound selector.",
        inputSchema = objectSchema(
            ServerSchemaProperty(
                "candidate",
                textSchema("Candidate selector returned by discovery."),
            ),
        ),
        command = listOf("symbol", "resolve"),
        optionFields = listOf(ServerCliOptionField("candidate", "--candidate")),
    ),
    TRAVERSAL_RUN(
        operation = CanonicalOperation.TRAVERSAL_RUN,
        toolName = "traversal_run",
        toolDescription =
            "Traverse one Kast semantic relation with explicit depth and result bounds.",
        inputSchema = objectSchema(
            ServerSchemaProperty("selector", textSchema("Exact starting selector.")),
            ServerSchemaProperty("relation", relationSchema()),
            ServerSchemaProperty(
                "maximumDepth",
                countSchema("Maximum traversal depth."),
            ),
            ServerSchemaProperty(
                "maximumResults",
                countSchema("Maximum returned symbols."),
            ),
        ),
        command = listOf("traversal", "run"),
        optionFields = listOf(
            ServerCliOptionField("selector", "--selector"),
            ServerCliOptionField("relation", "--relation"),
            ServerCliOptionField("maximumDepth", "--maximum-depth"),
            ServerCliOptionField("maximumResults", "--maximum-results"),
        ),
    ),
    ;

    fun document(cliUsage: String): InstalledServerToolDocument = InstalledServerToolDocument(
        operationId = operation.id.value,
        name = toolName,
        description = toolDescription,
        deferLoading = true,
        cliUsage = cliUsage,
        inputSchema = inputSchema,
        outputSchema = kastProcessOutputSchema,
        invocation = InstalledServerCliInvocationDocument(
            type = InstalledServerInvocationType.CLI,
            command = command,
            bindings = optionFields.map { field ->
                InstalledServerCliBindingDocument(
                    type = InstalledServerBindingType.OPTION,
                    inputField = field.inputField,
                    option = field.option,
                )
            },
        ),
    )
}

private data class ServerCliOptionField(
    val inputField: String,
    val option: String,
)

private data class ServerSchemaProperty(
    val name: String,
    val schema: JsonObject,
)

private fun symbolDiscoverInputSchema(): JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("name", "Discovery mode.")),
        ServerSchemaProperty("query", textSchema("Exact or fuzzy source-name query.")),
        ServerSchemaProperty(
            "kind",
            enumSchema(
                values = listOf("file", "class", "symbol"),
                description = "Name discovery kind.",
            ),
        ),
        ServerSchemaProperty(
            "match",
            enumSchema(
                values = listOf("fuzzy", "exact-name"),
                description = "Name matching policy.",
            ),
        ),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("location", "Discovery mode.")),
        ServerSchemaProperty("file", workspaceFileSchema()),
        ServerSchemaProperty(
            "offset",
            integerSchema(minimum = 0, description = "Non-negative source offset."),
        ),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("structure", "Discovery mode.")),
        ServerSchemaProperty("file", workspaceFileSchema()),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("text", "Discovery mode.")),
        ServerSchemaProperty("query", textSchema("Bounded source-text query.")),
        ServerSchemaProperty("scope", constantSchema("workspace", "Text discovery scope.")),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
    objectSchema(
        ServerSchemaProperty("mode", constantSchema("text", "Discovery mode.")),
        ServerSchemaProperty("query", textSchema("Bounded source-text query.")),
        ServerSchemaProperty("scope", constantSchema("file", "Text discovery scope.")),
        ServerSchemaProperty("file", workspaceFileSchema()),
        ServerSchemaProperty("limit", countSchema("Maximum returned items.")),
    ),
)

private val kastProcessOutputSchema: JsonObject = unionSchema(
    objectSchema(
        ServerSchemaProperty("status", constantSchema("completed", "Process outcome.")),
        ServerSchemaProperty("document", buildJsonObject {}),
    ),
    objectSchema(
        ServerSchemaProperty("status", constantSchema("rejected", "Process outcome.")),
        ServerSchemaProperty("diagnostic", buildJsonObject {}),
    ),
)

private fun objectSchema(vararg properties: ServerSchemaProperty): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonObject("properties") {
        properties.forEach { property -> put(property.name, property.schema) }
    }
    putJsonArray("required") {
        properties.forEach { property -> add(JsonPrimitive(property.name)) }
    }
}

private fun unionSchema(vararg variants: JsonObject): JsonObject = buildJsonObject {
    putJsonArray("anyOf") {
        variants.forEach(::add)
    }
}

private fun textSchema(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAXIMUM_PROTOCOL_TEXT_LENGTH)
    put("description", description)
}

private fun workspaceFileSchema(): JsonObject = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", MAXIMUM_WORKSPACE_FILE_LENGTH)
    put("description", "Workspace-relative file path.")
}

private fun countSchema(description: String): JsonObject = integerSchema(
    minimum = 1,
    maximum = MAXIMUM_PROTOCOL_COUNT,
    description = description,
)

private fun integerSchema(
    minimum: Int,
    maximum: Int? = null,
    description: String,
): JsonObject = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    maximum?.let { put("maximum", it) }
    put("description", description)
}

private fun constantSchema(value: String, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("const", value)
    put("description", description)
}

private fun enumSchema(values: List<String>, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun relationSchema(): JsonObject = enumSchema(
    values = listOf(
        "references",
        "callers",
        "callees",
        "implementations",
        "inheritors",
        "overrides",
        "type-uses",
    ),
    description = "One canonical Kast semantic relation.",
)
