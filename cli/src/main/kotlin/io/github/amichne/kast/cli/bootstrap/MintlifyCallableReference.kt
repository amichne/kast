package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.mcp.McpCallResult
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExactSymbolSelector
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SimpleSourceEntities
import io.github.amichne.kast.protocol.contract.SimpleSourceRegion
import io.github.amichne.kast.protocol.contract.SimpleSourceText
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadFormatDocument
import io.github.amichne.kast.protocol.contract.SourceReadSimpleRequest
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Build entry point for the generated public callable reference. */
internal object MintlifyCallableReference {
    val document: CanonicalJsonDocument
        get() = mintlifyCallableReference()

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty()) { "Mintlify callable reference projection accepts no arguments" }
        println(document.value)
    }
}

/**
 * Proof transition: `hosted catalog -> CanonicalJsonDocument`.
 *
 * Projects the installed public callable bindings into a documentation-only OpenAPI document. The synthetic paths
 * identify callable pages; they do not describe an HTTP transport, so this document deliberately has no `servers`
 * declaration and disables Mintlify's playground.
 */
internal fun mintlifyCallableReference(): CanonicalJsonDocument {
    val bindings = installedHostedBindings()
    val components =
        (bindings.flatMap { binding ->
                binding.tool.inputSchema.documentationComponents(binding.requestComponentName()) +
                    binding.tool.outputSchema.documentationComponents(binding.responseComponentName())
            } +
                generatedRequestSchema(ToolRpcReply.serializer())
                    .documentationComponents(MintlifyCallableComponentName.named("ToolRpcReply")) +
                generatedRequestSchema(McpCallResult.serializer())
                    .documentationComponents(MintlifyCallableComponentName.named("McpToolCallResult")) +
                McpStructuredResults.readSchema.documentationComponents(
                    MintlifyCallableComponentName.named("McpReadResult")
                ) +
                McpStructuredResults.investigationSchema.documentationComponents(
                    MintlifyCallableComponentName.named("McpInvestigationResult")
                ) +
                McpStructuredResults.changeSchema.documentationComponents(
                    MintlifyCallableComponentName.named("McpChangeResult")
                ))
            .toMap(linkedMapOf())
    return mintlifyCallableReferenceFactory.create(
        MintlifyCallableReferenceDocument(
            openapi = "3.1.0",
            info =
                MintlifyCallableInfoDocument(
                    title = "Kast callable reference",
                    version = "1",
                    description = "Public compiler-grounded Kast callables for connected agents.",
                ),
            paths =
                bindings.associateTo(linkedMapOf()) { binding ->
                    "/callables/${binding.tool.name}" to
                        MintlifyCallablePathDocument(post = binding.operationDocument())
                },
            components = MintlifyCallableComponentsDocument(components),
        )
    )
}

private fun InstalledHostedBinding.operationDocument(): MintlifyCallableOperationDocument {
    return MintlifyCallableOperationDocument(
        operationId = tool.name,
        summary = tool.name.replace('_', ' '),
        description = tool.description,
        requestBody = requestBodyDocument(),
        responses = responseDocuments(),
        mint = mintDocument(),
        kast =
            MintlifyCallableKastMetadataDocument(
                operation = operation.id.value,
                effect = tool.effect,
                approvalPolicy = tool.approvalPolicy,
                deferLoading = tool.deferLoading,
                executionBudget = tool.executionBudget,
            ),
    )
}

private fun InstalledHostedBinding.requestBodyDocument() =
    MintlifyCallableRequestBodyDocument(
        required = true,
        content =
            mapOf(
                "application/json" to
                    MintlifyCallableMediaTypeDocument(
                        schema = MintlifyCallableSchemaReference.component(requestComponentName()),
                        examples = if (operation == CanonicalOperation.SOURCE_READ) sourceReadExamples() else null,
                        invalidExamples =
                            if (operation == CanonicalOperation.SOURCE_READ) sourceReadInvalidExamples() else null,
                    )
            ),
    )

private fun InstalledHostedBinding.responseDocuments() =
    mapOf(
        "200" to
            MintlifyCallableResponseDocument(
                description =
                    "Invocation envelope: completed contains a semantic document; " +
                        "rejected contains a boundary diagnostic. A completed invocation may still contain " +
                        "a qualified or rejected semantic outcome.",
                content =
                    mapOf(
                        "application/json" to
                            MintlifyCallableMediaTypeDocument(
                                schema = MintlifyCallableSchemaReference.component(responseComponentName())
                            )
                    ),
            )
    )

private fun InstalledHostedBinding.mintDocument() =
    MintlifyCallableMintDocument(
        metadata =
            MintlifyCallablePageMetadataDocument(
                playground = MintlifyCallablePlayground.NONE,
                mode = MintlifyCallablePageMode.WIDE,
                hideApiMarker = true,
                icon = "square-terminal",
                description = "Inputs and response fields for ${tool.name}.",
                title = tool.name.replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
            ),
        content =
            "This callable is not an HTTP endpoint. Its schemas describe the hosted tool. " +
                "Direct MCP and tool RPC wrap the same semantic document differently. " +
                "See the [MCP contract](/reference/mcp-catalog) or " +
                "[tool RPC contract](/reference/rpc-catalog).",
    )

private fun InstalledHostedBinding.requestComponentName(): MintlifyCallableComponentName =
    MintlifyCallableComponentName.request(tool.name)

private fun InstalledHostedBinding.responseComponentName(): MintlifyCallableComponentName =
    MintlifyCallableComponentName.response(tool.name)

/**
 * Documentation projection of an admitted, dynamic JSON Schema resource. Lift local definitions into direct OpenAPI
 * components; retain every assertion and annotate variants for human navigation. Schema keyword maps are dynamic schema
 * data, never a manually assembled invocation payload.
 */
private fun JsonElement.documentationComponents(
    componentName: MintlifyCallableComponentName
): List<Pair<String, JsonElement>> {
    val schema = this as? JsonObject ?: error("A callable schema must be an object")
    val definitions = schema["\$defs"] as? JsonObject
    return listOf(componentName.value to schema.documentationSchema(componentName)) +
        definitions.orEmpty().map { (name, definition) ->
            "${componentName.value}_$name" to definition.documentationSchema(componentName, name)
        }
}

private fun JsonElement.documentationSchema(
    componentName: MintlifyCallableComponentName,
    title: String? = null,
): JsonElement =
    when (this) {
        is JsonArray -> Json.encodeToJsonElement(map { it.documentationSchema(componentName) })
        is JsonObject -> {
            val label = title ?: documentationTitle()
            val keywords = filterKeys {
                it != "\$defs"
            }
                .mapValues { (name, value) ->
                    if (
                        name == "\$ref" &&
                            value is JsonPrimitive &&
                            value.isString &&
                            value.content.startsWith("#/\$defs/")
                    ) {
                        JsonPrimitive(
                            "#/components/schemas/${componentName.value}_${value.content.removePrefix("#/\$defs/")}"
                        )
                    } else value.documentationSchema(componentName)
                }
            Json.encodeToJsonElement(
                    if (label != null && "title" !in keywords) keywords + ("title" to JsonPrimitive(label))
                    else keywords
                )
                .jsonObject
        }
        else -> this
    }

/** UI label derived from an existing discriminator and the schema's required live-evidence field. */
private fun JsonObject.documentationTitle(): String? {
    val properties = this["properties"] as? JsonObject
    val tag =
        listOf("status", "type", "kind").firstNotNullOfOrNull { key ->
            ((properties?.get(key) as? JsonObject)?.get("const") as? JsonPrimitive)?.content
        } ?: return null
    val required = this["required"] as? JsonArray
    // Mintlify resolves tab labels through an object lookup; the bare constructor key resolves to its prototype.
    val label = if (tag == "constructor") "constructor symbol" else tag
    return if (required?.contains(JsonPrimitive("live")) == true) "$label · live" else label
}

/** One OpenAPI component identity derived only from a canonical operation and schema role. */
@JvmInline
private value class MintlifyCallableComponentName private constructor(val value: String) {
    companion object {
        fun named(value: String): MintlifyCallableComponentName = MintlifyCallableComponentName(value)

        fun request(toolName: String): MintlifyCallableComponentName =
            MintlifyCallableComponentName("${toolName}Request")

        fun response(toolName: String): MintlifyCallableComponentName =
            MintlifyCallableComponentName("${toolName}Response")
    }
}

@Serializable
private data class MintlifyCallableReferenceDocument(
    val openapi: String,
    val info: MintlifyCallableInfoDocument,
    val paths: Map<String, MintlifyCallablePathDocument>,
    val components: MintlifyCallableComponentsDocument,
)

@Serializable
private data class MintlifyCallableInfoDocument(
    val title: String,
    val version: String,
    val description: String,
)

@Serializable private data class MintlifyCallablePathDocument(val post: MintlifyCallableOperationDocument)

@Serializable
private data class MintlifyCallableOperationDocument(
    val operationId: String,
    val summary: String,
    val description: String,
    val requestBody: MintlifyCallableRequestBodyDocument,
    val responses: Map<String, MintlifyCallableResponseDocument>,
    @SerialName("x-mint") val mint: MintlifyCallableMintDocument,
    @SerialName("x-kast") val kast: MintlifyCallableKastMetadataDocument,
)

@Serializable
private data class MintlifyCallableRequestBodyDocument(
    val required: Boolean,
    val content: Map<String, MintlifyCallableMediaTypeDocument>,
)

@Serializable
private data class MintlifyCallableResponseDocument(
    val description: String,
    val content: Map<String, MintlifyCallableMediaTypeDocument>,
)

@Serializable
private data class MintlifyCallableMediaTypeDocument(
    val schema: MintlifyCallableSchemaReference,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val examples: Map<String, MintlifyCallableExampleDocument>? = null,
    @SerialName("x-kast-invalidExamples")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val invalidExamples: Map<String, MintlifyCallableExampleDocument>? = null,
)

@Serializable private data class MintlifyCallableExampleDocument(val summary: String, val value: JsonElement)

private fun sourceReadExamples(): Map<String, MintlifyCallableExampleDocument> {
    val symbol = (ExactSymbolSelector.parse("exact:v5:${"A".repeat(21)}Q") as Refinement.Refined).value
    val bytes = (SourceTextByteLimitDocument.parse(12_000) as Refinement.Refined).value
    val count = (SourceEntityLimitDocument.parse(50) as Refinement.Refined).value
    val minimal = SourceReadSimpleRequest(symbol)
    val detailed =
        SourceReadSimpleRequest(
            symbol = symbol,
            region = SimpleSourceRegion.BODY,
            text = SimpleSourceText.Window(maximumBytes = bytes),
            entities = SimpleSourceEntities.Declarations(count),
            format = SourceReadFormatDocument.EXPANDED,
        )
    val json = Json { classDiscriminator = "type" }
    return mapOf(
        "exactSymbol" to
            MintlifyCallableExampleDocument(
                "Replace this illustrative selector with the exact selector returned by search.",
                json.encodeToJsonElement(SourceReadSimpleRequest.serializer(), minimal),
            ),
        "callableBody" to
            MintlifyCallableExampleDocument(
                "A callable body with bounded text and direct declarations.",
                json.encodeToJsonElement(SourceReadSimpleRequest.serializer(), detailed),
            ),
    )
}

@Serializable private data class InvalidSourceReadText(val mode: String = "unsupported")

@Serializable private data class InvalidSourceReadMode(val symbol: String, val text: InvalidSourceReadText)

@Serializable private data class MixedSourceReadIdentity(val symbol: String, val anchor: SourceReadAnchorDocument)

private fun sourceReadInvalidExamples(): Map<String, MintlifyCallableExampleDocument> {
    val symbol = "exact:v5:${"A".repeat(21)}Q"
    val text = (ProtocolText.parse(symbol) as Refinement.Refined).value
    return mapOf(
        "unsupportedTextMode" to
            MintlifyCallableExampleDocument(
                "An unknown text discriminator is rejected.",
                Json.encodeToJsonElement(
                    InvalidSourceReadMode.serializer(),
                    InvalidSourceReadMode(symbol, InvalidSourceReadText()),
                ),
            ),
        "mixedIdentity" to
            MintlifyCallableExampleDocument(
                "A symbol shortcut cannot be combined with an anchor.",
                Json.encodeToJsonElement(
                    MixedSourceReadIdentity.serializer(),
                    MixedSourceReadIdentity(symbol, SourceReadAnchorDocument.Symbol(text)),
                ),
            ),
    )
}

@Serializable
private data class MintlifyCallableSchemaReference private constructor(@SerialName("\$ref") val reference: String) {
    companion object {
        fun component(componentName: MintlifyCallableComponentName): MintlifyCallableSchemaReference =
            MintlifyCallableSchemaReference("#/components/schemas/${componentName.value}")
    }
}

@Serializable private data class MintlifyCallableComponentsDocument(val schemas: Map<String, JsonElement>)

@Serializable
private data class MintlifyCallableMintDocument(val metadata: MintlifyCallablePageMetadataDocument, val content: String)

@Serializable
private data class MintlifyCallablePageMetadataDocument(
    val playground: MintlifyCallablePlayground,
    val mode: MintlifyCallablePageMode,
    val hideApiMarker: Boolean,
    val icon: String,
    val description: String,
    val title: String,
)

@Serializable
private enum class MintlifyCallablePageMode {
    @SerialName("wide") WIDE
}

@Serializable
private enum class MintlifyCallablePlayground {
    @SerialName("none") NONE
}

@Serializable
private data class MintlifyCallableKastMetadataDocument(
    val operation: String,
    val effect: String,
    val approvalPolicy: String,
    val deferLoading: Boolean,
    val executionBudget: InstalledServerExecutionBudgetDocument,
)

private val mintlifyCallableReferenceFactory =
    CanonicalJsonDocument.generated(MintlifyCallableReferenceDocument.serializer())
