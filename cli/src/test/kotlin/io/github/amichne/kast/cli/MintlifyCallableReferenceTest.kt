package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.registry.HostedOperationProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
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
        val components = reference.getValue("components").jsonObject.getValue("schemas").jsonObject
        val invocationByTool = installed.cliInvocations.operations.associateBy { it.toolName }
        val publicOperationIds = HostedOperationProjection.publicDefinitions.map { it.operation.id.value }
        val internalOperationIds = HostedOperationProjection.internalDefinitions.map { it.operation.id.value }

        assertEquals("3.1.0", reference.getValue("openapi").jsonPrimitive.content)
        assertFalse("servers" in reference)
        assertEquals(
            installed.hostedBootstrap.tools.map { "/callables/${it.name}" },
            paths.keys.toList(),
        )
        assertEquals(publicOperationIds.toSet(), installed.hostedBootstrap.tools.map { it.operationId }.toSet())
        assertFalse(installed.hostedBootstrap.tools.any { it.operationId in internalOperationIds })
        assertTrue(components.size > installed.hostedBootstrap.tools.size * 2)

        components.values
            .flatMap { schema -> schema.localReferences() }
            .forEach { schemaReference ->
                assertTrue(schemaReference.startsWith("#/components/schemas/"))
                assertTrue(schemaReference.substringAfter("#/components/schemas/").substringBefore('/') in components)
                schemaReference.removePrefix("#/").split('/').fold(reference as JsonElement) { value, key ->
                    checkNotNull((value as? JsonObject)?.get(key)) {
                        "Dangling callable schema reference: $schemaReference"
                    }
                }
            }

        installed.hostedBootstrap.tools.forEach { tool ->
            val operation = paths.getValue("/callables/${tool.name}").jsonObject.getValue("post").jsonObject
            val mint = operation.getValue("x-mint").jsonObject
            val mintMetadata = mint.getValue("metadata").jsonObject
            val content = mint.getValue("content").jsonPrimitive.content
            val kastMetadata = operation.getValue("x-kast").jsonObject
            val requestReference =
                operation
                    .getValue("requestBody")
                    .jsonObject
                    .getValue("content")
                    .jsonObject
                    .getValue("application/json")
                    .jsonObject
                    .getValue("schema")
                    .jsonObject
                    .reference()
            val responseReference =
                operation
                    .getValue("responses")
                    .jsonObject
                    .getValue("200")
                    .jsonObject
                    .getValue("content")
                    .jsonObject
                    .getValue("application/json")
                    .jsonObject
                    .getValue("schema")
                    .jsonObject
                    .reference()
            val invocation = invocationByTool.getValue(tool.name)

            assertEquals(tool.name, operation.getValue("operationId").jsonPrimitive.content)
            assertEquals("none", mintMetadata.getValue("playground").jsonPrimitive.content)
            assertTrue(content.contains("not an HTTP endpoint"))
            assertEquals(tool.effect, kastMetadata.getValue("effect").jsonPrimitive.content)
            assertEquals(
                tool.approvalPolicy,
                kastMetadata.getValue("approvalPolicy").jsonPrimitive.content,
            )
            assertEquals(
                tool.inputSchema.expandSchema(tool.inputSchema),
                components.getValue(requestReference).expandSchema(reference),
            )
            assertEquals(
                tool.outputSchema.expandSchema(tool.outputSchema),
                components.getValue(responseReference).expandSchema(reference),
            )
            assertEquals("wide", mintMetadata.getValue("mode").jsonPrimitive.content)
            assertEquals("true", mintMetadata.getValue("hideApiMarker").jsonPrimitive.content)
            assertFalse("x-codeSamples" in operation)
            assertTrue(
                content.contains("```bash\nkast ${invocation.invocation.command.joinToString(" ")} < request.json\n```")
            )
        }
    }

    @Test
    fun `compiler arrays resolve to named OpenAPI components instead of nested definitions`() {
        val reference =
            Json.parseToJsonElement(mintlifyCallableReference(commandGraphFactory().surface).value).jsonObject
        val components = reference.getValue("components").jsonObject.getValue("schemas").jsonObject
        assertTrue(components.values.none { "\$defs" in it.jsonObject })
        val item = components.getValue("search_classesResponse_queryResultItem").jsonObject
        assertEquals("queryResultItem", item.getValue("title").jsonPrimitive.content)
        val variants = item.getValue("anyOf").jsonArray
        assertEquals(
            listOf("candidate", "exact-symbol"),
            variants.map { it.jsonObject.getValue("title").jsonPrimitive.content },
        )
        components.values
            .flatMap { it.localReferences() }
            .forEach { ref ->
                assertEquals(3, ref.removePrefix("#/").split('/').size, ref)
            }
    }

    @Test
    fun `live outcomes carry distinct navigation labels`() {
        val reference =
            Json.parseToJsonElement(mintlifyCallableReference(commandGraphFactory().surface).value).jsonObject
        val components = reference.getValue("components").jsonObject.getValue("schemas").jsonObject
        val response = components.getValue("search_classesResponse").jsonObject
        val document =
            response
                .getValue("anyOf")
                .jsonArray
                .first()
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("document")
                .jsonObject
        val outcomes = document.getValue("anyOf").jsonArray.first().jsonObject.getValue("anyOf").jsonArray.take(2)
        assertEquals(
            listOf("complete", "complete · live", "qualified", "qualified · live"),
            outcomes
                .flatMap { it.jsonObject.getValue("oneOf").jsonArray }
                .map { it.jsonObject.getValue("title").jsonPrimitive.content },
        )
    }

    private fun JsonObject.reference(): String = getValue("\$ref").jsonPrimitive.content.substringAfterLast('/')

    private fun JsonElement.localReferences(): List<String> =
        when (this) {
            is JsonArray -> flatMap { element -> element.localReferences() }
            is JsonObject ->
                entries.flatMap { (name, value) ->
                    if (name == "\$ref") listOf(value.jsonPrimitive.content) else value.localReferences()
                }
            else -> emptyList()
        }

    /** Compare resolved validation assertions independently of definition placement and UI annotations. */
    private fun JsonElement.expandSchema(root: JsonElement): JsonElement =
        when (this) {
            is JsonArray -> Json.encodeToJsonElement(map { it.expandSchema(root) })
            is JsonObject -> {
                val ref = get("\$ref")
                if (ref != null) {
                    ref.jsonPrimitive.content
                        .removePrefix("#/")
                        .split('/')
                        .fold(root) { node, key ->
                            node.jsonObject.getValue(key)
                        }
                        .expandSchema(root)
                } else
                    Json.encodeToJsonElement(
                        filterKeys { it != "\$defs" && it != "title" }
                            .mapValues { (_, value) -> value.expandSchema(root) }
                    )
            }
            else -> this
        }

    private fun commandGraphFactory(): CliCommandGraphFactory =
        when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error(construction.failures)
        }
}
