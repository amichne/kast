package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
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
    val document: CliJsonDocument
        get() {
            val commandSurface =
                when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
                    is CliCommandGraphConstruction.Created -> construction.factory.surface
                    is CliCommandGraphConstruction.Rejected ->
                        error("Canonical CLI command graph rejected: ${construction.failures}")
                }
            return mintlifyCallableReference(commandSurface)
        }

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty()) { "Mintlify callable reference projection accepts no arguments" }
        println(document.value)
    }
}

/**
 * Proof transition: `CliCommandSurface -> CliJsonDocument`.
 *
 * Projects the installed public callable bindings into a documentation-only OpenAPI document. The synthetic paths
 * identify callable pages; they do not describe an HTTP transport, so this document deliberately has no `servers`
 * declaration and disables Mintlify's playground.
 */
internal fun mintlifyCallableReference(commandSurface: CliCommandSurface): CliJsonDocument {
    val bindings = installedServerBindings(commandSurface)
    val components =
        bindings
            .flatMap { binding ->
                binding.tool.inputSchema.documentationComponents(binding.requestComponentName()) +
                    binding.tool.outputSchema.documentationComponents(binding.responseComponentName())
            }
            .toMap(linkedMapOf())
    return mintlifyCallableReferenceFactory.create(
        MintlifyCallableReferenceDocument(
            openapi = "3.1.0",
            info =
                MintlifyCallableInfoDocument(
                    title = "Kast callable reference",
                    version = "1",
                    description = "Public compiler-grounded Kast callables invoked through the Kast CLI.",
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

private fun InstalledServerBinding.operationDocument(): MintlifyCallableOperationDocument =
    MintlifyCallableOperationDocument(
        operationId = tool.name,
        summary = tool.name.replace('_', ' '),
        description = tool.description,
        requestBody =
            MintlifyCallableRequestBodyDocument(
                required = true,
                content =
                    mapOf(
                        "application/json" to
                            MintlifyCallableMediaTypeDocument(
                                schema = MintlifyCallableSchemaReference.component(requestComponentName())
                            )
                    ),
            ),
        responses =
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
            ),
        mint =
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
                    "${tool.description}\n\nThis callable is not an HTTP endpoint. " +
                        "Invoke it with the Kast CLI:\n\n" +
                        "```bash\nkast ${invocation.invocation.command.joinToString(" ")} < request.json\n```\n\n" +
                        "Read [response outcomes](/reference/responses) before using the payload. " +
                        "For compiler fields and reference reuse, see [symbol results](/reference/symbols).",
            ),
        kast =
            MintlifyCallableKastMetadataDocument(
                operation = operation.id.value,
                effect = tool.effect,
                approvalPolicy = tool.approvalPolicy,
                deferLoading = tool.deferLoading,
                executionBudget = tool.executionBudget,
                cliUsage = invocation.cliUsage,
            ),
    )

private fun InstalledServerBinding.requestComponentName(): MintlifyCallableComponentName =
    MintlifyCallableComponentName.request(tool.name)

private fun InstalledServerBinding.responseComponentName(): MintlifyCallableComponentName =
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
    return if (required?.contains(JsonPrimitive("live")) == true) "$tag · live" else tag
}

/** One OpenAPI component identity derived only from a canonical operation and schema role. */
@JvmInline
private value class MintlifyCallableComponentName private constructor(val value: String) {
    companion object {
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

@Serializable private data class MintlifyCallableMediaTypeDocument(val schema: MintlifyCallableSchemaReference)

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
    val cliUsage: String,
)

private val mintlifyCallableReferenceFactory = CliJsonDocument.generated(MintlifyCallableReferenceDocument.serializer())
