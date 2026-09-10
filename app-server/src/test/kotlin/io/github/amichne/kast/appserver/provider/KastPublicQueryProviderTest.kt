package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerDispatch
import io.github.amichne.kast.appserver.core.BrokerDispatchRequest
import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.ProviderNamespace
import io.github.amichne.kast.appserver.core.ToolAddress
import io.github.amichne.kast.appserver.core.ToolName
import io.github.amichne.kast.appserver.query.PublicQueryContract
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KastPublicQueryProviderTest {
    @Test
    fun `provider sends normalized public grammar without preparing lifecycle`(@TempDir root: Path) = runTest {
        val executor = RecordingExecutor(capability())
        val broker = broker(root, executor)
        val result = broker.dispatch(request(root, """{"type":"QUERY","from":{"type":"SEARCH","query":"Order"},"select":[]}"""))
        assertTrue(result is BrokerDispatch.Completed)
        val calls = executor.requests.filter { it.arguments == listOf("query", "run") }
        assertEquals(1, calls.size)
        val sent = (calls.single().input as BrokerProcessInput.Document).value
        val parsed = PublicQueryContract.admit(Json.parseToJsonElement(sent)).refined()
        assertEquals(PublicQueryContract.encode(parsed), Json.parseToJsonElement(sent))
        assertTrue(sent.contains("\"match\":\"EXACT\""))
        assertTrue(sent.contains("\"select\":[]"))
        assertTrue(!sent.contains("execution"))
        assertTrue(executor.requests.none { it.arguments.first() in setOf("start", "index", "topology") })
    }

    @Test
    fun `invalid controls fail before semantic invocation`(@TempDir root: Path) = runTest {
        val executor = RecordingExecutor(capability())
        val broker = broker(root, executor)
        listOf(
            """{"type":"QUERY","from":{"type":"ALL"},"execution":{"kind":"exhaustive"}}""",
            """{"type":"QUERY","from":{"type":"REFS","refs":["candidate:v2:example"]}}""",
        ).forEach { input -> assertTrue(broker.dispatch(request(root, input)) is BrokerDispatch.Rejected) }
        assertEquals(0, executor.requests.count { it.arguments == listOf("query", "run") })
    }

    @Test
    fun `query qualification rejects a drifted parameter contract`(@TempDir root: Path) = runTest {
        val drifted = JsonObject(PublicQueryContract.parameters + ("description" to JsonPrimitive("drift")))
        val executor = RecordingExecutor(capability(drifted))
        assertEquals(
            KastProviderQualification.Rejected(KastQualificationFailure.SCHEMA_INCOMPATIBLE),
            KastProviderQualifier.qualify(options(root, executor)),
        )
        assertEquals(0, executor.requests.count { it.arguments == listOf("query", "run") })
    }

    private suspend fun broker(root: Path, executor: RecordingExecutor): Broker {
        val qualified = KastProviderQualifier.qualify(options(root, executor))
        assertTrue(qualified is KastProviderQualification.Qualified)
        return when (val result = Broker.create(
            listOf((qualified as KastProviderQualification.Qualified).registration), BrokerLimits.defaults(),
        )) {
            is Validation.Validated -> result.value
            is Validation.Rejected -> error("Rejected test broker")
        }
    }

    private fun options(root: Path, executor: RecordingExecutor): KastProviderOptions {
        val executable = root.resolve("kast")
        Files.writeString(executable, "#!/bin/sh\nexit 0\n")
        check(executable.toFile().setExecutable(true))
        return KastProviderOptions.admit(executable, root.toRealPath(), executor).refined()
    }

    private fun request(root: Path, input: String): BrokerDispatchRequest = BrokerDispatchRequest(
        ToolAddress(ProviderNamespace.admit("kast").refined(), ToolName.admit("query").refined()),
        Json.parseToJsonElement(input),
        BrokerInvocationContext.admit("query-thread", "query-turn", "query-call", root.toRealPath()).refined(),
    )

    private class RecordingExecutor(private val schema: String) : BrokerProcessExecutor {
        val requests = mutableListOf<BrokerProcessRequest>()
        override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution {
            requests += request
            return when (request.arguments) {
                listOf("--version") -> BrokerProcessExecution.Completed(0, "kast 9.9.9\n", "")
                listOf("--schema") -> BrokerProcessExecution.Completed(0, schema, "")
                listOf("query", "run") -> BrokerProcessExecution.Completed(
                    0, """{"operation":"query.run","status":"complete","items":[],"failures":[]}""", "",
                )
                else -> error("Unexpected subprocess: ${request.arguments}")
            }
        }
    }

    private fun capability(input: JsonObject = PublicQueryContract.parameters): String = """
        {"schemaVersion":1,"serverProjection":{"schemaVersion":9,"namespace":"kast",
        "hostedBootstrap":{"schemaVersion":1,"policy":${JsonPrimitive(CanonicalAgentToolDefinitions.policy.text)},
        "tools":[{"operationId":"query.run","name":"query",
        "description":${JsonPrimitive(CanonicalAgentToolDefinitions.query.description.value)},
        "deferLoading":false,"effect":"intellij_read","approvalPolicy":"none",
        "executionBudget":{"readinessMillis":1020000,"operationMillis":60000},
        "inputSchema":$input,"outputSchema":{"type":"object","properties":{
            "status":{"enum":["completed"]},"document":{"type":"object"}},
            "required":["status","document"],"additionalProperties":false}}]},
        "cliInvocations":{"schemaVersion":2,"operations":[{"operationId":"query.run",
        "cliUsage":"query run < request.json","invocation":{"type":"CLI","command":["query","run"]}}]}}}
    """.trimIndent()

    private fun <T, E> Refinement<T, E>.refined(): T = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Rejected test fixture: $failure")
    }
}
