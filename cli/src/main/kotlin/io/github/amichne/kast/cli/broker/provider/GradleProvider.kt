package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.BrokerInvocationContext
import io.github.amichne.kast.cli.broker.core.BrokerTool
import io.github.amichne.kast.cli.broker.core.ProviderCall
import io.github.amichne.kast.cli.broker.core.ProviderFailureCode
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ProviderRegistration
import io.github.amichne.kast.cli.broker.core.ProviderStartup
import io.github.amichne.kast.cli.broker.core.ProviderVersion
import io.github.amichne.kast.cli.broker.core.ToolDescription
import io.github.amichne.kast.cli.broker.core.ToolLoading
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.cli.broker.core.ToolPresentation
import io.github.amichne.kast.cli.broker.schema.CompiledJsonSchema
import io.github.amichne.kast.cli.broker.schema.JsonDomainDefinition
import io.github.amichne.kast.cli.broker.schema.NetworkntJsonSchemaCompiler
import io.github.amichne.kast.cli.broker.schema.ValidatedJsonValue
import io.github.amichne.kast.cli.broker.schema.canonicalJson
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RefinementDefinition
import io.github.amichne.kast.kernel.Validation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object GradleProvider {
    internal fun registration(
        processExecutor: BrokerProcessExecutor = JdkBrokerProcessExecutor,
    ): Validation<ProviderRegistration<GradleRuntime>, *> = ProviderRegistration.define(
        namespace = required(ProviderNamespace.admit("gradle")),
        version = required(ProviderVersion.admit("0.3.0")),
        tools = listOf(
            tool(
                name = "inspect",
                description = "Inspect the admitted Gradle build's project structure through its wrapper.",
                loading = ToolLoading.EAGER,
                inputSchema = EMPTY_INPUT_SCHEMA,
            ) { GradleInvocation(listOf("projects")) },
            tool(
                name = "tasks",
                description = "List bounded Gradle task metadata through the admitted repository wrapper.",
                loading = ToolLoading.DEFERRED,
                inputSchema = TASKS_INPUT_SCHEMA,
            ) { value ->
                val all = value.element.jsonObject["all"]?.jsonPrimitive?.booleanOrNull == true
                GradleInvocation(if (all) listOf("tasks", "--all") else listOf("tasks"))
            },
            tool(
                name = "dependencies",
                description = "Read one Gradle dependency configuration from an exact project path.",
                loading = ToolLoading.DEFERRED,
                inputSchema = DEPENDENCIES_INPUT_SCHEMA,
            ) { value ->
                val document = value.element.jsonObject
                val configuration = document.getValue("configuration").jsonPrimitive.content
                val project = document["project"]?.jsonPrimitive?.contentOrNull.orEmpty()
                GradleInvocation(
                    listOf("${project}:dependencies", "--configuration", configuration),
                )
            },
        ),
        start = { ProviderStartup.Started(GradleRuntime(processExecutor)) },
    )

    private fun tool(
        name: String,
        description: String,
        loading: ToolLoading,
        inputSchema: CompiledJsonSchema,
        refine: (ValidatedJsonValue) -> GradleInvocation,
    ): BrokerTool<GradleRuntime, GradleInvocation, GradleOutput, Nothing> = BrokerTool(
        name = required(ToolName.admit(name)),
        description = required(ToolDescription.admit(description)),
        loading = loading,
        input = JsonDomainDefinition(
            inputSchema,
            RefinementDefinition { admitted -> Validation.validated(refine(admitted)) },
        ),
        outputSchema = OUTPUT_SCHEMA,
        invoke = { runtime, input, context -> runtime.invoke(input, context) },
        encode = GradleOutput::document,
        present = { output ->
            ToolPresentation.text(
                canonicalJson(output.document),
                success = output.exitCode == 0,
            )
        },
    )

    private fun schema(source: String): CompiledJsonSchema = required(
        NetworkntJsonSchemaCompiler.compile(Json.parseToJsonElement(source).jsonObject),
    )

    private fun <Strong, Failure> required(refinement: Refinement<Strong, Failure>): Strong =
        when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error("Static Gradle provider definition is invalid")
        }

    private val EMPTY_INPUT_SCHEMA = schema(
        """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": false,
          "properties": {}
        }
        """.trimIndent(),
    )
    private val TASKS_INPUT_SCHEMA = schema(
        """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": false,
          "properties": { "all": { "type": "boolean", "default": false } }
        }
        """.trimIndent(),
    )
    private val DEPENDENCIES_INPUT_SCHEMA = schema(
        """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": false,
          "required": ["configuration"],
          "properties": {
            "configuration": {
              "type": "string",
              "minLength": 1,
              "maxLength": 128,
              "pattern": "^[A-Za-z0-9_.-]+${'$'}"
            },
            "project": {
              "type": "string",
              "minLength": 2,
              "maxLength": 256,
              "pattern": "^(:[A-Za-z0-9_.-]+)+${'$'}"
            }
          }
        }
        """.trimIndent(),
    )
    private val OUTPUT_SCHEMA = schema(
        """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "additionalProperties": false,
          "required": ["exitCode", "stdout", "stderr"],
          "properties": {
            "exitCode": { "type": "integer" },
            "stdout": { "type": "string" },
            "stderr": { "type": "string" }
          }
        }
        """.trimIndent(),
    )
}

internal data class GradleInvocation(val arguments: List<String>)

internal data class GradleOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val document: JsonObject = buildJsonObject {
        put("exitCode", exitCode)
        put("stdout", stdout)
        put("stderr", stderr)
    }
}

internal class GradleRuntime(
    private val processExecutor: BrokerProcessExecutor,
) {
    internal suspend fun invoke(
        input: GradleInvocation,
        context: BrokerInvocationContext,
    ): ProviderCall<GradleOutput> {
        val wrapper = when (
            val admission = BrokerExecutable.admit(context.workingDirectory.path.resolve("gradlew"))
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return ProviderCall.Rejected(
                ProviderFailureCode.GRADLE_WRAPPER_UNAVAILABLE,
            )
        }
        val request = when (
            val admission = BrokerProcessRequest.admit(
                    executable = wrapper,
                    arguments = listOf("--console=plain", "--no-daemon") + input.arguments,
                    workingDirectory = context.workingDirectory,
                    maximumOutputBytes = MAXIMUM_OUTPUT_BYTES,
                    timeoutMillis = INVOCATION_TIMEOUT_MILLIS,
                )
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return ProviderCall.Rejected(
                ProviderFailureCode.UNEXPECTED_FAILURE,
            )
        }
        return when (val execution = processExecutor.execute(request)) {
            is BrokerProcessExecution.Completed -> ProviderCall.Completed(
                GradleOutput(execution.exitCode, execution.stdout, execution.stderr),
            )
            is BrokerProcessExecution.Rejected -> ProviderCall.Rejected(
                execution.failure.providerFailureCode(),
            )
        }
    }

    private companion object {
        const val MAXIMUM_OUTPUT_BYTES = 512 * 1_024
        const val INVOCATION_TIMEOUT_MILLIS = 30_000L
    }
}
