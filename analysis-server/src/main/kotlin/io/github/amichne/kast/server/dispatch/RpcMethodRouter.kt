package io.github.amichne.kast.server.dispatch

import io.github.amichne.kast.api.contract.AnalysisBackend
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.HealthResponse
import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import io.github.amichne.kast.api.contract.RuntimeLifecycleAction
import io.github.amichne.kast.api.contract.RuntimeLifecycleResponse
import io.github.amichne.kast.api.contract.RuntimeOpenProjectRequest
import io.github.amichne.kast.api.contract.RuntimeOpenProjectResponse
import io.github.amichne.kast.api.contract.RuntimeStatusResponse
import io.github.amichne.kast.api.protocol.CapabilityNotSupportedException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.server.AnalysisServerConfig
import io.github.amichne.kast.server.RuntimeLifecycleController
import io.github.amichne.kast.server.RuntimeProjectOpenController
import io.github.amichne.kast.server.SkillRpcOrchestrator
import io.github.amichne.kast.server.WorkspaceFilesContinuationService
import io.github.amichne.kast.server.mutation.MutationExecutionService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

internal class RpcMethodRouter(
    internal val backend: AnalysisBackend,
    internal val config: AnalysisServerConfig,
    private val lifecycleController: RuntimeLifecycleController,
    private val projectOpenController: RuntimeProjectOpenController,
    internal val json: Json,
) : Closeable {
    internal val skillRpc = SkillRpcOrchestrator(backend, config, json)
    internal val mutationRpc = MutationExecutionService(skillRpc)
    internal val workspaceFilesContinuation = WorkspaceFilesContinuationService(
        capacity = config.typedContinuationCapacity,
        timeToLive = config.typedContinuationTtl,
    )
    private val afterResponseActions = ConcurrentLinkedQueue<() -> Unit>()

    suspend fun dispatch(
        method: String,
        params: JsonElement?,
    ): JsonElement = when {
        method == "health" -> encode(HealthResponse.serializer(), backend.health())
        method == "runtime/status" -> encode(RuntimeStatusResponse.serializer(), backend.runtimeStatus())
        method == "runtime/shutdown" -> encode(
            RuntimeLifecycleResponse.serializer(),
            requestLifecycle(RuntimeLifecycleAction.SHUTDOWN),
        )
        method == "runtime/restart" -> encode(
            RuntimeLifecycleResponse.serializer(),
            requestLifecycle(RuntimeLifecycleAction.RESTART),
        )
        method == "runtime/open-project" -> encode(
            RuntimeOpenProjectResponse.serializer(),
            requestProjectOpen(decodeParams(RuntimeOpenProjectRequest.serializer(), params)),
        )
        method == "capabilities" -> encode(BackendCapabilities.serializer(), backend.capabilities())
        else -> dispatchRawMethod(method, params)
            ?: dispatchSkillMethod(method, params)
            ?: throw UnknownRpcMethodException(method)
    }

    internal fun runAfterResponseActions(): Boolean {
        var ranAction = false
        while (true) {
            val action = afterResponseActions.poll() ?: return ranAction
            ranAction = true
            runCatching(action)
        }
    }

    override fun close() {
        var firstFailure: Throwable? = null
        listOf<() -> Unit>(workspaceFilesContinuation::close).forEach { closePhase ->
            try {
                closePhase()
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else {
                    firstFailure.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { throw it }
    }

    internal suspend fun requireReadCapability(capability: ReadCapability) {
        val capabilities = backend.capabilities()
        if (!capabilities.readCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "The backend does not advertise $capability",
            )
        }
    }

    internal suspend fun requireMutationCapability(capability: MutationCapability) {
        val capabilities = backend.capabilities()
        if (!capabilities.mutationCapabilities.contains(capability)) {
            throw CapabilityNotSupportedException(
                capability = capability.name,
                message = "The backend does not advertise $capability",
            )
        }
    }

    internal fun <T> decodeParams(
        serializer: KSerializer<T>,
        params: JsonElement?,
    ): T = params?.let { json.decodeFromJsonElement(serializer, it) }
        ?: throw ValidationException("The JSON-RPC request is missing params")

    internal fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): JsonElement = json.encodeToJsonElement(serializer, value)

    private suspend fun requestLifecycle(action: RuntimeLifecycleAction): RuntimeLifecycleResponse {
        val afterResponseAction = lifecycleController.afterResponseAction(action)
            ?: throw CapabilityNotSupportedException(
                capability = "RUNTIME_LIFECYCLE",
                message = "Runtime lifecycle actions are not available for this backend host",
            )
        val capabilities = backend.capabilities()
        afterResponseActions += afterResponseAction
        return RuntimeLifecycleResponse(
            accepted = true,
            action = action,
            backendName = capabilities.backendName,
            backendVersion = capabilities.backendVersion,
            workspaceRoot = capabilities.workspaceRoot,
            message = "Runtime ${action.name.lowercase()} accepted; action will run after this response is flushed.",
        )
    }

    private fun requestProjectOpen(request: RuntimeOpenProjectRequest): RuntimeOpenProjectResponse {
        val plan = projectOpenController.openProject(request)
        afterResponseActions += plan.afterResponseAction
        return plan.response
    }
}

internal class UnknownRpcMethodException(
    method: String,
) : RuntimeException("Unknown JSON-RPC method: $method")
