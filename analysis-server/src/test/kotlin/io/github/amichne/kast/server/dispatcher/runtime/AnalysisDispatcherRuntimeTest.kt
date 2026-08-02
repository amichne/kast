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
    fun `capabilities advertise the effective server request deadline`() {
        val config = AnalysisServerConfig(
            requestTimeoutMillis = 30_000,
            workspaceFileCount = 16_813,
        )
        val dispatcher = RpcAnalysisDispatcher(
            backend = FakeAnalysisBackend.sample(tempDir),
            config = config,
        )

        val response = runBlocking {
            dispatcher.dispatch(JsonRpcRequest(id = JsonPrimitive(1), method = "capabilities"))
        }
        val result = json.decodeFromJsonElement(
            BackendCapabilities.serializer(),
            json.decodeFromString(JsonRpcSuccessResponse.serializer(), response).result,
        )

        assertEquals(config.effectiveRequestTimeoutMillis, result.limits.requestTimeoutMillis)
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

        val dispatchResult = runBlocking {
            dispatcher.dispatchForTransport(JsonRpcRequest(id = JsonPrimitive(1), method = "runtime/restart"))
        }
        val response = json.decodeFromString(JsonRpcSuccessResponse.serializer(), dispatchResult.response)
        val result = json.decodeFromJsonElement(
            RuntimeLifecycleResponse.serializer(),
            response.result,
        )

        assertEquals(RuntimeLifecycleAction.RESTART, result.action)
        assertTrue(result.accepted)
        assertTrue(actions.isEmpty(), "Lifecycle action must wait until the transport flushes the response")

        assertTrue(dispatchResult.runAfterFlushAction())
        assertEquals(listOf(RuntimeLifecycleAction.RESTART), actions)
        assertFalse(dispatchResult.runAfterFlushAction())
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
