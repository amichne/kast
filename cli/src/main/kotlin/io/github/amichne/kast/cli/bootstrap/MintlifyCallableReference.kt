package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.mcp.McpCallResult
import io.github.amichne.kast.cli.mcp.McpStructuredResults
import io.github.amichne.kast.cli.mcp.healthInputSchema
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_CONTRACT_VERSION
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.registry.SupportToolHost
import io.github.amichne.kast.protocol.registry.SupportToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    val directSupport = SupportToolIdentity.entries.filter { SupportToolHost.MCP in it.hosts }
    val components =
        (bindings.flatMap { binding ->
                binding.tool.inputSchema.documentationComponents(binding.requestComponentName()) +
                    binding.tool.outputSchema.documentationComponents(binding.responseComponentName()) +
                    installedSemanticResultSchema(binding.operation)
                        .documentationComponents(binding.semanticResultComponentName())
            } +
                generatedRequestSchema(ToolRpcReply.serializer())
                    .documentationComponents(MintlifyCallableComponentName.named("ToolRpcReply")) +
                generatedRequestSchema(McpCallResult.serializer())
                    .documentationComponents(MintlifyCallableComponentName.named("McpToolCallResult")) +
                healthInputSchema()
                    .documentationComponents(MintlifyCallableComponentName.named("health_checkRequest")) +
                McpStructuredResults.schemaFor("health_check")
                    .documentationComponents(MintlifyCallableComponentName.named("health_checkSemanticResult")))
            .toMap(linkedMapOf())
    return mintlifyCallableReferenceFactory.create(
        MintlifyCallableReferenceDocument(
            openapi = "3.1.0",
            info =
                MintlifyCallableInfoDocument(
                    title = "Kast callable reference",
                    version = PUBLIC_TOOL_CONTRACT_VERSION.toString(),
                    description = "Public compiler-grounded Kast callables for connected agents.",
                ),
            paths =
                bindings.associateTo(linkedMapOf()) { binding ->
                    "/callables/${binding.tool.name}" to
                        MintlifyCallablePathDocument(post = binding.operationDocument())
                } +
                    directSupport.associate { identity ->
                        "/callables/${identity.toolName}" to
                            MintlifyCallablePathDocument(post = identity.supportOperationDocument())
                    },
            components = MintlifyCallableComponentsDocument(components),
        )
    )
}

private fun SupportToolIdentity.supportOperationDocument(): MintlifyCallableOperationDocument {
    val input = MintlifyCallableComponentName.request(toolName)
    val result = MintlifyCallableComponentName.named(toolName + "SemanticResult")
    return MintlifyCallableOperationDocument(
        operationId = toolName,
        summary = toolName.replace('_', ' '),
        description = description,
        requestBody =
            MintlifyCallableRequestBodyDocument(
                required = true,
                content =
                    mapOf(
                        "application/json" to
                            MintlifyCallableMediaTypeDocument(MintlifyCallableSchemaReference.component(input))
                    ),
            ),
        responses =
            mapOf(
                "200" to
                    MintlifyCallableResponseDocument(
                        description =
                            "The direct tool's semantic document. MCP and RPC use their own invocation envelopes.",
                        content =
                            mapOf(
                                "application/json" to
                                    MintlifyCallableMediaTypeDocument(MintlifyCallableSchemaReference.component(result))
                            ),
                    )
            ),
        mint =
            MintlifyCallableMintDocument(
                metadata =
                    MintlifyCallablePageMetadataDocument(
                        playground = MintlifyCallablePlayground.NONE,
                        mode = MintlifyCallablePageMode.WIDE,
                        hideApiMarker = true,
                        icon = "square-terminal",
                        description = "Inputs and response fields for $toolName.",
                        title = toolName.replace('_', ' ').replaceFirstChar { it.uppercaseChar() },
                    ),
                content =
                    "This callable is not an HTTP endpoint. It is available through direct MCP and tool RPC. " +
                        "See the [MCP contract](/reference/mcp-catalog) or " +
                        "[tool RPC contract](/reference/rpc-catalog) for invocation envelopes.",
            ),
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
                        examples =
                            PublicToolIdentity.entries
                                .singleOrNull { it.toolName == tool.name }
                                ?.let(PublicToolContract::examples)
                                ?.examples
                                ?.mapValues { (_, example) ->
                                    MintlifyCallableExampleDocument(example.summary, example.value)
                                }
                                ?.takeIf { it.isNotEmpty() },
                        invalidExamples =
                            PublicToolIdentity.entries
                                .singleOrNull { it.toolName == tool.name }
                                ?.let(PublicToolContract::examples)
                                ?.invalidExamples
                                ?.mapValues { (_, example) ->
                                    MintlifyCallableExampleDocument(example.summary, example.value)
                                }
                                ?.takeIf { it.isNotEmpty() },
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

private fun InstalledHostedBinding.semanticResultComponentName(): MintlifyCallableComponentName =
    MintlifyCallableComponentName.named(tool.name + "SemanticResult")

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
    @SerialName("x-kast")
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val kast: MintlifyCallableKastMetadataDocument? = null,
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
