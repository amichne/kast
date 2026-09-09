package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandSurface
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Build entry point for the generated public callable reference. */
internal object MintlifyCallableReference {
    val document: CliJsonDocument get() {
        val commandSurface = when (
            val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
        ) {
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
 * Projects the installed public callable bindings into a documentation-only OpenAPI document.
 * The synthetic paths identify callable pages; they do not describe an HTTP transport, so this
 * document deliberately has no `servers` declaration and disables Mintlify's playground.
 */
internal fun mintlifyCallableReference(
    commandSurface: CliCommandSurface,
): CliJsonDocument {
    val bindings = installedServerBindings(commandSurface)
    val components = bindings.flatMap { binding ->
        listOf(
            binding.requestComponentName().value to
                binding.tool.inputSchema.rebaseLocalDefinitions(binding.requestComponentName()),
            binding.responseComponentName().value to
                binding.tool.outputSchema.rebaseLocalDefinitions(binding.responseComponentName()),
        )
    }.toMap(linkedMapOf())
    return mintlifyCallableReferenceFactory.create(
        MintlifyCallableReferenceDocument(
            openapi = "3.1.0",
            info = MintlifyCallableInfoDocument(
                title = "Kast callable reference",
                version = "1",
                description = "Public compiler-grounded Kast callables invoked through the Kast CLI.",
            ),
            paths = bindings.associateTo(linkedMapOf()) { binding ->
                "/callables/${binding.tool.name}" to MintlifyCallablePathDocument(
                    post = binding.operationDocument(),
                )
            },
            components = MintlifyCallableComponentsDocument(components),
        ),
    )
}

private fun InstalledServerBinding.operationDocument(): MintlifyCallableOperationDocument =
    MintlifyCallableOperationDocument(
        operationId = tool.operationId,
        summary = tool.name.replace('_', ' '),
        description = "${tool.description}\n\nThis callable is not an HTTP endpoint. " +
            "Invoke it with the Kast CLI command shown in the example.",
        requestBody = MintlifyCallableRequestBodyDocument(
            required = true,
            content = mapOf(
                "application/json" to MintlifyCallableMediaTypeDocument(
                    schema = MintlifyCallableSchemaReference.component(requestComponentName()),
                ),
            ),
        ),
        responses = mapOf(
            "200" to MintlifyCallableResponseDocument(
                description = "Canonical Kast process outcome.",
                content = mapOf(
                    "application/json" to MintlifyCallableMediaTypeDocument(
                        schema = MintlifyCallableSchemaReference.component(responseComponentName()),
                    ),
                ),
            ),
        ),
        mint = MintlifyCallableMintDocument(
            metadata = MintlifyCallablePageMetadataDocument(
                playground = MintlifyCallablePlayground.NONE,
            ),
        ),
        kast = MintlifyCallableKastMetadataDocument(
            operation = operation.id.value,
            effect = tool.effect,
            approvalPolicy = tool.approvalPolicy,
            deferLoading = tool.deferLoading,
            executionBudget = tool.executionBudget,
            cliUsage = invocation.cliUsage,
        ),
        codeSamples = listOf(
            MintlifyCallableCodeSampleDocument(
                lang = "bash",
                label = "Invoke with Kast",
                source = "kast ${invocation.invocation.command.joinToString(" ")} < request.json",
            ),
        ),
    )

private fun InstalledServerBinding.requestComponentName(): MintlifyCallableComponentName =
    MintlifyCallableComponentName.request(operation)

private fun InstalledServerBinding.responseComponentName(): MintlifyCallableComponentName =
    MintlifyCallableComponentName.response(operation)

/** Rebinds one schema resource's document-local definitions after OpenAPI component embedding. */
private fun JsonElement.rebaseLocalDefinitions(
    componentName: MintlifyCallableComponentName,
): JsonElement = when (this) {
    is JsonArray -> JsonArray(map { element -> element.rebaseLocalDefinitions(componentName) })
    is JsonObject -> JsonObject(mapValues { (name, value) ->
        if (
            name == "\$ref" &&
            value is JsonPrimitive &&
            value.isString &&
            value.content.startsWith("#/\$defs/")
        ) {
            JsonPrimitive(
                "#/components/schemas/${componentName.value}/${value.content.removePrefix("#/")}",
            )
        } else {
            value.rebaseLocalDefinitions(componentName)
        }
    })
    else -> this
}

/** One OpenAPI component identity derived only from a canonical operation and schema role. */
@JvmInline
private value class MintlifyCallableComponentName private constructor(val value: String) {
    companion object {
        fun request(operation: CanonicalOperation): MintlifyCallableComponentName =
            MintlifyCallableComponentName("${operation.name}Request")

        fun response(operation: CanonicalOperation): MintlifyCallableComponentName =
            MintlifyCallableComponentName("${operation.name}Response")
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

@Serializable
private data class MintlifyCallablePathDocument(
    val post: MintlifyCallableOperationDocument,
)

@Serializable
private data class MintlifyCallableOperationDocument(
    val operationId: String,
    val summary: String,
    val description: String,
    val requestBody: MintlifyCallableRequestBodyDocument,
    val responses: Map<String, MintlifyCallableResponseDocument>,
    @SerialName("x-mint") val mint: MintlifyCallableMintDocument,
    @SerialName("x-kast") val kast: MintlifyCallableKastMetadataDocument,
    @SerialName("x-codeSamples") val codeSamples: List<MintlifyCallableCodeSampleDocument>,
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
)

@Serializable
private data class MintlifyCallableSchemaReference private constructor(
    @SerialName("\$ref") val reference: String,
) {
    companion object {
        fun component(
            componentName: MintlifyCallableComponentName,
        ): MintlifyCallableSchemaReference = MintlifyCallableSchemaReference(
            "#/components/schemas/${componentName.value}",
        )
    }
}

@Serializable
private data class MintlifyCallableComponentsDocument(
    val schemas: Map<String, JsonElement>,
)

@Serializable
private data class MintlifyCallableMintDocument(
    val metadata: MintlifyCallablePageMetadataDocument,
)

@Serializable
private data class MintlifyCallablePageMetadataDocument(
    val playground: MintlifyCallablePlayground,
)

@Serializable
private enum class MintlifyCallablePlayground {
    @SerialName("none")
    NONE,
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

@Serializable
private data class MintlifyCallableCodeSampleDocument(
    val lang: String,
    val label: String,
    val source: String,
)

private val mintlifyCallableReferenceFactory =
    CliJsonDocument.generated(MintlifyCallableReferenceDocument.serializer())
