package io.github.amichne.kast.appserver.core

import io.github.amichne.kast.appserver.schema.JsonDomainDefinition
import io.github.amichne.kast.appserver.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrokerToolAliasTest {
    @Test
    fun `registration rejects aliases colliding with preferred names or aliases`() {
        val combinations =
            listOf(
                listOf(tool("current", "current")),
                listOf(tool("current", "legacy"), tool("legacy")),
                listOf(tool("current", "legacy"), tool("another", "legacy")),
            )
        val expected = listOf("current", "legacy", "legacy")
        combinations.zip(expected).forEach { (tools, collision) ->
            val registration = register(tools)
            assertEquals(
                Validation.rejected<Nothing, ProviderDefinitionFailure>(
                    ProviderDefinitionFailure.DuplicateTool(name(collision))
                ),
                registration,
            )
        }
    }

    @Test
    fun `accepted aliases are input only and retain preferred catalog identity`() {
        val registration = register(listOf(tool("current", "legacy"))).validated()
        assertEquals(listOf("current"), registration.toolDocuments.map { it.name.value })
    }

    private fun register(tools: List<BrokerTool<Unit, Unit, EmptyDocument, Nothing>>) =
        ProviderRegistration.define(
            namespace = ProviderNamespace.admit("aliases").refined(),
            version = ProviderVersion.admit("1").refined(),
            tools = tools,
            start = { ProviderStartup.Started(Unit) },
        )

    private fun tool(preferred: String, vararg aliases: String): BrokerTool<Unit, Unit, EmptyDocument, Nothing> {
        val schema = NetworkntJsonSchemaCompiler.compile(json.encodeToJsonElement(ObjectSchema()).jsonObject).refined()
        return BrokerTool(
            name = name(preferred),
            description = ToolDescription.admit("Alias registration fixture.").refined(),
            loading = ToolLoading.EAGER,
            input = JsonDomainDefinition(schema, RefinementDefinition { Validation.validated(Unit) }),
            outputSchema = schema,
            invoke = { _, _, _ -> ProviderCall.Completed(EmptyDocument()) },
            encode = { json.encodeToJsonElement(it) },
            present = { ToolPresentation.text(json.encodeToJsonElement(it).toString(), success = true) },
            inputAliases = aliases.mapTo(linkedSetOf(), ::name),
        )
    }

    private fun name(value: String): ToolName = ToolName.admit(value).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Unexpected fixture rejection: $failure")
        }

    private fun <Value, Failure> Validation<Value, Failure>.validated(): Value =
        when (this) {
            is Validation.Validated -> value
            is Validation.Rejected -> error("Unexpected fixture rejection: $failures")
        }

    @Serializable private class EmptyDocument

    @Serializable private data class ObjectSchema(val type: String = "object")

    private val json = Json { encodeDefaults = true }
}
