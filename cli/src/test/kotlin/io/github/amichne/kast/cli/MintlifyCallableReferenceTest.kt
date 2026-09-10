package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MintlifyCallableReferenceTest {
    @Test
    fun `reference preserves every public callable without claiming an HTTP server`() {
        val surface = commandGraphFactory().surface
        val installed = installedServerProjection(surface)
        val reference = Json.parseToJsonElement(mintlifyCallableReference(surface).value).jsonObject
        val paths = reference.getValue("paths").jsonObject
        val components = reference.getValue("components")
            .jsonObject.getValue("schemas").jsonObject
        val invocationByOperation = installed.cliInvocations.operations.associateBy { it.operationId }
        val publicOperationIds = HostedOperationProjection.publicDefinitions.map { it.operation.id.value }
        val internalOperationIds = HostedOperationProjection.internalDefinitions.map { it.operation.id.value }

        assertEquals("3.1.0", reference.getValue("openapi").jsonPrimitive.content)
        assertFalse("servers" in reference)
        assertEquals(
            installed.hostedBootstrap.tools.map { "/callables/${it.name}" },
            paths.keys.toList(),
        )
        assertEquals(publicOperationIds, installed.hostedBootstrap.tools.map { it.operationId })
        assertFalse(installed.hostedBootstrap.tools.any { it.operationId in internalOperationIds })
        assertEquals(installed.hostedBootstrap.tools.size * 2, components.size)

        components.values.flatMap { schema -> schema.localReferences() }.forEach { schemaReference ->
            assertTrue(schemaReference.startsWith("#/components/schemas/"))
            assertTrue(schemaReference.substringAfter("#/components/schemas/").substringBefore('/') in components)
            schemaReference.removePrefix("#/").split('/').fold(reference as JsonElement) { value, key ->
                checkNotNull((value as? JsonObject)?.get(key)) { "Dangling callable schema reference: $schemaReference" }
            }
        }

        installed.hostedBootstrap.tools.forEach { tool ->
            val operation = paths.getValue("/callables/${tool.name}")
                .jsonObject.getValue("post").jsonObject
            val mintMetadata = operation.getValue("x-mint")
                .jsonObject.getValue("metadata").jsonObject
            val kastMetadata = operation.getValue("x-kast").jsonObject
            val requestReference = operation.getValue("requestBody")
                .jsonObject.getValue("content").jsonObject
                .getValue("application/json").jsonObject
                .getValue("schema").jsonObject.reference()
            val responseReference = operation.getValue("responses")
                .jsonObject.getValue("200").jsonObject
                .getValue("content").jsonObject
                .getValue("application/json").jsonObject
                .getValue("schema").jsonObject.reference()
            val invocation = invocationByOperation.getValue(tool.operationId)
            val sample = operation.getValue("x-codeSamples").jsonArray.single().jsonObject

            assertEquals(tool.operationId, operation.getValue("operationId").jsonPrimitive.content)
            assertEquals("none", mintMetadata.getValue("playground").jsonPrimitive.content)
            assertTrue(
                operation.getValue("description").jsonPrimitive.content
                    .contains("not an HTTP endpoint"),
            )
            assertEquals(tool.effect, kastMetadata.getValue("effect").jsonPrimitive.content)
            assertEquals(
                tool.approvalPolicy,
                kastMetadata.getValue("approvalPolicy").jsonPrimitive.content,
            )
            assertEquals(
                tool.inputSchema.rebaseLocalDefinitions(requestReference),
                components.getValue(requestReference),
            )
            assertEquals(
                tool.outputSchema.rebaseLocalDefinitions(responseReference),
                components.getValue(responseReference),
            )
            assertEquals("bash", sample.getValue("lang").jsonPrimitive.content)
            assertEquals(
                "kast ${invocation.invocation.command.joinToString(" ")} < request.json",
                sample.getValue("source").jsonPrimitive.content,
            )
        }
    }

    private fun JsonObject.reference(): String =
        getValue("\$ref").jsonPrimitive.content.substringAfterLast('/')

    private fun JsonElement.localReferences(): List<String> = when (this) {
        is JsonArray -> flatMap { element -> element.localReferences() }
        is JsonObject -> entries.flatMap { (name, value) ->
            if (name == "\$ref") listOf(value.jsonPrimitive.content) else value.localReferences()
        }
        else -> emptyList()
    }

    private fun JsonElement.rebaseLocalDefinitions(componentName: String): JsonElement = when (this) {
        is JsonArray -> JsonArray(map { element -> element.rebaseLocalDefinitions(componentName) })
        is JsonObject -> JsonObject(mapValues { (name, value) ->
            if (name == "\$ref" && value.jsonPrimitive.content.startsWith("#/\$defs/")) {
                JsonPrimitive("#/components/schemas/$componentName/${value.jsonPrimitive.content.removePrefix("#/")}")
            } else {
                value.rebaseLocalDefinitions(componentName)
            }
        })
        else -> this
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error(construction.failures)
    }
}
