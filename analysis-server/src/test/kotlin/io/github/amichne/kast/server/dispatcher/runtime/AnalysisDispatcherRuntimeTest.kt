package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.*
import io.github.amichne.kast.api.contract.query.*
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.contract.selector.*
import io.github.amichne.kast.api.contract.skill.*
import io.github.amichne.kast.api.protocol.*
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AnalysisDispatcherRuntimeTest : AnalysisDispatcherTestSupport() {
    @Test
    fun `runtime status dispatches without HTTP`() {
        val result = dispatchSuccess<RuntimeStatusResponse>("runtime/status")

        assertEquals(RuntimeState.READY, result.state)
        assertEquals("fake", result.backendName)
    }

    @Test
    fun `capabilities dispatches without HTTP`() {
        val result = dispatchSuccess<BackendCapabilities>("capabilities")

        assertTrue(result.readCapabilities.contains(ReadCapability.RESOLVE_SYMBOL))
        assertEquals("fake", result.backendName)
    }

    @Test
    fun `runtime restart schedules lifecycle action after response`() {
        val actions = mutableListOf<RuntimeLifecycleAction>()
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(),
            lifecycleController = RuntimeLifecycleController { action ->
                { actions += action }
            },
        )

        val raw = runBlocking {
            dispatcher.dispatch(JsonRpcRequest(id = JsonPrimitive(1), method = "runtime/restart"))
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(
            RuntimeLifecycleResponse.serializer(),
            response.result,
        )

        assertEquals(RuntimeLifecycleAction.RESTART, result.action)
        assertTrue(result.accepted)
        assertTrue(actions.isEmpty(), "Lifecycle action must wait until the transport flushes the response")

        assertTrue(dispatcher.runAfterResponseActions())
        assertEquals(listOf(RuntimeLifecycleAction.RESTART), actions)
        assertFalse(dispatcher.runAfterResponseActions())
    }

    @Test
    fun `runtime open project forwards the authenticated exact-root request`() {
        var received: RuntimeOpenProjectRequest? = null
        var opened = false
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = AnalysisServerConfig(),
            projectOpenController = RuntimeProjectOpenController { request ->
                received = request
                RuntimeProjectOpenPlan(
                    response = RuntimeOpenProjectResponse(RuntimeOpenProjectResult.OPENED_NEW_PROJECT),
                    afterResponseAction = { opened = true },
                )
            },
        )
        val request = RuntimeOpenProjectRequest(
            canonicalRoot = RuntimeOpenProjectRoot.parse(tempDir.toRealPath().toString()),
            requestId = RuntimeOpenProjectRequestId.parse("a7370b30-7ca5-4fa5-93c0-e59d30aa6157"),
        )

        val raw = runBlocking {
            dispatcher.dispatch(
                JsonRpcRequest(
                    id = JsonPrimitive(1),
                    method = "runtime/open-project",
                    params = json.encodeToJsonElement(RuntimeOpenProjectRequest.serializer(), request),
                ),
            )
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), raw)
        val result = json.decodeFromJsonElement(RuntimeOpenProjectResponse.serializer(), response.result)

        assertEquals(request, received)
        assertEquals(RuntimeOpenProjectResult.OPENED_NEW_PROJECT, result.result)
        assertFalse(opened, "Project opening must wait until the transport flushes the response")
        assertTrue(dispatcher.runAfterResponseActions())
        assertTrue(opened)
    }

    @Test
    fun `dispatcher bytecode avoids kotlin Duration ABI coupling`() {
        val classFileText = classFileText(RpcAnalysisDispatcher::class.java)

        assertFalse(classFileText.contains("kotlin/time/Duration"))
        assertFalse(classFileText.contains("fromRawValue-UwyO8pc"))
    }

    @Test
    fun `dispatcher maps request timeout to timeout api error`() {
        val dispatcher = RpcAnalysisDispatcher(
            backend = DispatcherTimeoutHealthBackend(FakeAnalysisBackend.sample(tempDir), delayMillis = 100),
            config = AnalysisServerConfig(requestTimeoutMillis = 1),
        )
        val raw = runBlocking {
            dispatcher.dispatch(JsonRpcRequest(id = JsonPrimitive(1), method = "health"))
        }

        val response = json.parseToJsonElement(raw).jsonObject
        val error = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), response)

        assertEquals("TIMEOUT", error.error.data?.code)
        assertEquals(true, error.error.data?.retryable)
        assertEquals("health", error.error.data?.details?.get("method"))
        assertEquals("1", error.error.data?.details?.get("timeoutMillis"))
    }

    @Test
    fun `dispatcher maps backend cancellation to timeout api error`() {
        val dispatcher = RpcAnalysisDispatcher(
            backend = DispatcherCancellationHealthBackend(FakeAnalysisBackend.sample(tempDir)),
            config = AnalysisServerConfig(requestTimeoutMillis = 1),
        )
        val raw = runBlocking {
            dispatcher.dispatch(JsonRpcRequest(id = JsonPrimitive(1), method = "health"))
        }

        val response = json.parseToJsonElement(raw).jsonObject
        val error = json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), response)

        assertEquals("TIMEOUT", error.error.data?.code)
        assertEquals(true, error.error.data?.retryable)
        assertEquals("health", error.error.data?.details?.get("method"))
    }
}
