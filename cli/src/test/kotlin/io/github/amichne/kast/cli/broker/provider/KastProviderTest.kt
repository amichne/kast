package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerDispatch
import io.github.amichne.kast.cli.broker.core.BrokerDispatchRequest
import io.github.amichne.kast.cli.broker.core.BrokerFailure
import io.github.amichne.kast.cli.broker.core.BrokerInvocationContext
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.core.ProviderFailureCode
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ToolAddress
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class KastProviderTest {
    @Test
    fun `installed contract publishes and invokes only no-approval tools`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val executor = RecordingProcessExecutor(schema = capabilitySchema())
        val options = KastProviderOptions.admit(executable, cwd, executor).refinedValue()
        val qualification = assertInstanceOf(
            KastProviderQualification.Qualified::class.java,
            KastProviderQualifier.qualify(options),
        )
        val broker = Broker.create(
            listOf(qualification.registration),
            BrokerLimits.defaults(),
        ).validatedValue()

        assertEquals(
            listOf("symbol_lookup"),
            broker.catalog.namespaces.single().tools.map { tool -> tool.name.value },
        )
        val completed = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("kast"), toolName("symbol_lookup")),
                buildJsonObject { put("query", "Thing") },
                context(cwd),
            ),
        )
        val explicit = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("kast"), toolName("change_apply")),
                buildJsonObject { put("plan", "plan-1") },
                context(cwd),
            ),
        )

        assertEquals(true, (completed as BrokerDispatch.Completed).presentation.success)
        assertEquals(
            listOf("symbol", "discover", "--query", "Thing"),
            executor.requests.last().arguments,
        )
        assertInstanceOf(BrokerFailure.UnknownTool::class.java, (explicit as BrokerDispatch.Rejected).failure)
        assertEquals(2, executor.requests.count { it.arguments == listOf("--version") })
        assertEquals(2, executor.requests.count { it.arguments == listOf("--schema") })
    }

    @Test
    fun `provider start rejects contract drift before invocation`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val executor = RecordingProcessExecutor(
            schema = capabilitySchema(),
            replacementSchema = capabilitySchema(description = "Changed installed contract."),
        )
        val options = KastProviderOptions.admit(executable, cwd, executor).refinedValue()
        val qualification = KastProviderQualifier.qualify(options) as KastProviderQualification.Qualified
        val broker = Broker.create(
            listOf(qualification.registration),
            BrokerLimits.defaults(),
        ).validatedValue()

        val dispatch = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("kast"), toolName("symbol_lookup")),
                buildJsonObject { put("query", "Thing") },
                context(cwd),
            ),
        ) as BrokerDispatch.Rejected

        val failure = dispatch.failure as BrokerFailure.ProviderStartupRejected
        assertEquals(ProviderFailureCode.KAST_CONTRACT_CHANGED, failure.code)
        assertEquals(0, executor.requests.count { it.arguments.firstOrNull() == "symbol" })
    }

    @Test
    fun `open input object is rejected before extra arguments can be dropped`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val openInput = capabilitySchema().replaceFirst(
            """"additionalProperties": false,""",
            "",
        )
        val options = KastProviderOptions.admit(
            executable,
            cwd,
            RecordingProcessExecutor(schema = openInput),
        ).refinedValue()

        assertEquals(
            KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE),
            KastProviderQualifier.qualify(options),
        )
    }

    @Test
    fun `initial qualification is bounded even when an executor never returns`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val executable = executable(temporary.resolve("kast"))
        val cwd = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val options = KastProviderOptions.admit(
            executable,
            cwd,
            BrokerProcessExecutor { awaitCancellation() },
            qualificationTimeoutMillis = 50,
        ).refinedValue()

        val result = withTimeout(1_000) { KastProviderQualifier.qualify(options) }

        assertEquals(
            KastProviderQualification.Rejected(KastQualificationFailure.VERSION_UNAVAILABLE),
            result,
        )
    }

    private class RecordingProcessExecutor(
        private val schema: String,
        private val replacementSchema: String = schema,
    ) : BrokerProcessExecutor {
        val requests = mutableListOf<BrokerProcessRequest>()
        private var schemaReads = 0

        override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution {
            requests += request
            return when (request.arguments) {
                listOf("--version") -> BrokerProcessExecution.Completed(0, "kast 9.9.9\n", "")
                listOf("--schema") -> {
                    schemaReads += 1
                    BrokerProcessExecution.Completed(
                        0,
                        if (schemaReads == 1) schema else replacementSchema,
                        "",
                    )
                }
                else -> BrokerProcessExecution.Completed(
                    0,
                    """{"operation":"symbol.discover","outcome":"complete","items":[]}""",
                    "",
                )
            }
        }
    }

    private fun capabilitySchema(description: String = "Find one symbol."): String =
        """
        {
          "schemaVersion": 1,
          "serverProjection": {
            "schemaVersion": 2,
            "namespace": "kast",
            "tools": [
              {
                "operationId": "symbol.discover",
                "name": "symbol_lookup",
                "description": "$description",
                "deferLoading": true,
                "approvalPolicy": "none",
                "cliUsage": "kast symbol discover --query VALUE",
                "inputSchema": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["query"],
                  "properties": { "query": { "type": "string", "minLength": 1 } }
                },
                "outputSchema": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["status", "document"],
                  "properties": {
                    "status": { "const": "completed" },
                    "document": { "type": "object" }
                  }
                },
                "invocation": {
                  "type": "CLI",
                  "command": ["symbol", "discover"],
                  "bindings": [
                    { "type": "OPTION", "inputField": "query", "option": "--query" }
                  ]
                }
              },
              {
                "operationId": "change.apply",
                "name": "change_apply",
                "description": "Apply an approved plan.",
                "deferLoading": true,
                "approvalPolicy": "explicit",
                "cliUsage": "kast change apply --plan VALUE",
                "inputSchema": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["plan"],
                  "properties": { "plan": { "type": "string", "minLength": 1 } }
                },
                "outputSchema": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["status", "document"],
                  "properties": {
                    "status": { "const": "completed" },
                    "document": { "type": "object" }
                  }
                },
                "invocation": {
                  "type": "CLI",
                  "command": ["change", "apply"],
                  "bindings": [
                    { "type": "OPTION", "inputField": "plan", "option": "--plan" }
                  ]
                }
              }
            ]
          }
        }
        """.trimIndent()

    private fun executable(path: Path): Path {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path.toRealPath()
    }

    private fun context(cwd: Path): BrokerInvocationContext = BrokerInvocationContext.admit(
        "thread-1",
        "turn-1",
        "call-1",
        cwd,
    ).refinedValue()

    private fun namespace(value: String): ProviderNamespace =
        ProviderNamespace.admit(value).refinedValue()

    private fun toolName(value: String): ToolName = ToolName.admit(value).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
    }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong = when (this) {
        is Validation.Validated -> value
        is Validation.Rejected -> throw AssertionError("Expected validation, received $failures")
    }
}
