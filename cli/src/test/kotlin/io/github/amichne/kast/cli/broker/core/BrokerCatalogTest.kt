package io.github.amichne.kast.cli.broker.core

import io.github.amichne.kast.cli.broker.schema.CompiledJsonSchema
import io.github.amichne.kast.cli.broker.schema.JsonDomainDefinition
import io.github.amichne.kast.cli.broker.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BrokerCatalogTest {
    @Test
    fun `catalog identity is deterministic across provider definition order`() {
        val alpha = echoProvider("alpha")
        val beta = echoProvider("beta")

        val first = Broker.create(listOf(beta, alpha), BrokerLimits.defaults()).validatedValue()
        val second = Broker.create(listOf(alpha, beta), BrokerLimits.defaults()).validatedValue()

        assertEquals(first.catalog.digest, second.catalog.digest)
        assertEquals(listOf("alpha", "beta"), first.catalog.namespaces.map { it.name.value })
    }

    @Test
    fun `schema and domain admission happen before one lazy provider runtime`(
        @TempDir temporary: Path,
    ) = runBlocking {
        var starts = 0
        var invocations = 0
        val provider = echoProvider("echo") {
            starts += 1
            EchoRuntime { invocations += 1 }
        }
        val broker = Broker.create(listOf(provider), BrokerLimits.defaults()).validatedValue()
        val context = invocationContext(temporary)

        val invalid = broker.dispatch(
            BrokerDispatchRequest(
                address = ToolAddress(namespace("echo"), toolName("say")),
                arguments = buildJsonObject {},
                context = context,
            ),
        )

        assertInstanceOf(BrokerDispatch.Rejected::class.java, invalid)
        assertEquals(0, starts)
        assertEquals(0, invocations)

        val first = broker.dispatch(
            BrokerDispatchRequest(
                address = ToolAddress(namespace("echo"), toolName("say")),
                arguments = buildJsonObject { put("value", "hello") },
                context = context,
            ),
        )
        val second = broker.dispatch(
            BrokerDispatchRequest(
                address = ToolAddress(namespace("echo"), toolName("say")),
                arguments = buildJsonObject { put("value", "again") },
                context = context,
            ),
        )

        assertEquals("hello", first.completedText())
        assertEquals("again", second.completedText())
        assertEquals(1, starts)
        assertEquals(2, invocations)
    }

    @Test
    fun `vendored argument and result byte budgets fail closed`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val context = invocationContext(temporary)
        val argumentBroker = Broker.create(
            listOf(echoProvider("echo")),
            BrokerLimits.defaults(),
        ).validatedValue()
        val oversizedArgument = argumentBroker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("echo"), toolName("say")),
                buildJsonObject { put("value", "x".repeat(64 * 1_024)) },
                context,
            ),
        ) as BrokerDispatch.Rejected
        assertEquals(
            BrokerFailure.Overloaded(BrokerLimit.MAXIMUM_TOOL_ARGUMENT_BYTES),
            oversizedArgument.failure,
        )

        val resultBroker = Broker.create(
            listOf(echoProvider("echo", outputValue = { "x".repeat(1_024 * 1_024) })),
            BrokerLimits.defaults(),
        ).validatedValue()
        val oversizedResult = resultBroker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("echo"), toolName("say")),
                buildJsonObject { put("value", "small") },
                context,
            ),
        ) as BrokerDispatch.Rejected
        assertEquals(
            BrokerFailure.Overloaded(BrokerLimit.MAXIMUM_TOOL_RESULT_BYTES),
            oversizedResult.failure,
        )
    }

    private fun echoProvider(
        namespace: String,
        outputValue: (String) -> String = { value -> value },
        start: suspend () -> EchoRuntime = { EchoRuntime {} },
    ): ProviderRegistration<EchoRuntime> {
        val inputSchema = schema(
            """
            {
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "additionalProperties": false,
              "required": ["value"],
              "properties": { "value": { "type": "string", "minLength": 1 } }
            }
            """.trimIndent(),
        )
        val outputSchema = schema(
            """
            {
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "additionalProperties": false,
              "required": ["value"],
              "properties": { "value": { "type": "string" } }
            }
            """.trimIndent(),
        )
        val input = JsonDomainDefinition(
            inputSchema,
            RefinementDefinition { admitted ->
                val value = admitted.element.jsonObject.getValue("value").jsonPrimitive.content
                if (value.isNotBlank()) {
                    Validation.validated(EchoInput(value))
                } else {
                    Validation.rejected(EchoInputFailure.BLANK)
                }
            },
        )
        val tool: BrokerTool<EchoRuntime, EchoInput, EchoOutput, EchoInputFailure> = BrokerTool(
            name = toolName("say"),
            description = ToolDescription.admit("Echo one admitted value.").refinedValue(),
            loading = ToolLoading.EAGER,
            input = input,
            outputSchema = outputSchema,
            invoke = { runtime, argument, _ ->
                runtime.record()
                ProviderCall.Completed(EchoOutput(outputValue(argument.value)))
            },
            encode = { output -> buildJsonObject { put("value", output.value) } },
            present = { output -> ToolPresentation.text(output.value, success = true) },
        )
        return ProviderRegistration.define(
            namespace = namespace(namespace),
            version = ProviderVersion.admit("1.0.0").refinedValue(),
            tools = listOf(tool),
            start = { ProviderStartup.Started(start()) },
        ).validatedValue()
    }

    private fun schema(source: String): CompiledJsonSchema =
        NetworkntJsonSchemaCompiler.compile(Json.parseToJsonElement(source).jsonObject).refinedValue()

    private fun namespace(value: String): ProviderNamespace =
        ProviderNamespace.admit(value).refinedValue()

    private fun toolName(value: String): ToolName = ToolName.admit(value).refinedValue()

    private fun invocationContext(temporary: Path): BrokerInvocationContext {
        val cwd = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        return BrokerInvocationContext.admit(
            threadId = "thread-1",
            turnId = "turn-1",
            callId = "call-1",
            workingDirectory = cwd,
        ).refinedValue()
    }

    private fun BrokerDispatch.completedText(): String =
        when (this) {
            is BrokerDispatch.Completed -> presentation.content.single().text
            is BrokerDispatch.Rejected -> throw AssertionError("Expected completion, received $failure")
        }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
        }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong =
        when (this) {
            is Validation.Validated -> value
            is Validation.Rejected -> throw AssertionError("Expected validation, received $failures")
        }

    private data class EchoInput(val value: String)
    private data class EchoOutput(val value: String)
    private enum class EchoInputFailure { BLANK }
    private fun interface EchoRuntime { fun record() }
}
