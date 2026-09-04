package io.github.amichne.kast.cli.broker.protocol.codex

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerCallId
import io.github.amichne.kast.cli.broker.core.BrokerDispatch
import io.github.amichne.kast.cli.broker.core.BrokerDispatchRequest
import io.github.amichne.kast.cli.broker.core.BrokerFailure
import io.github.amichne.kast.cli.broker.core.BrokerInvocationContext
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivity
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivityPublication
import io.github.amichne.kast.cli.broker.core.BrokerInvocationActivitySink
import io.github.amichne.kast.cli.broker.core.BrokerInvocationCompletion
import io.github.amichne.kast.cli.broker.core.BrokerLimit
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ObserverPresentation
import io.github.amichne.kast.cli.broker.core.ToolAddress
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.cli.broker.core.ToolPresentation
import io.github.amichne.kast.cli.broker.protocol.ThreadCatalogBinding
import io.github.amichne.kast.cli.broker.protocol.ThreadCatalogStore
import io.github.amichne.kast.cli.broker.protocol.ThreadStoreRead
import io.github.amichne.kast.cli.broker.protocol.ThreadStoreWrite
import io.github.amichne.kast.cli.broker.schema.canonicalJson
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal sealed interface ProtocolCloseFailure {
    data object MalformedDownstream : ProtocolCloseFailure
    data object MalformedUpstream : ProtocolCloseFailure
    data class OwnedSchemaRejected(val schema: CodexOwnedSchema) : ProtocolCloseFailure
    data object OwnedFieldsMissing : ProtocolCloseFailure
    data object OwnedRequestIdMissing : ProtocolCloseFailure
    data object ThreadBindingRejected : ProtocolCloseFailure
    data object ThreadStoreRejected : ProtocolCloseFailure
    data object ResponseSchemaRejected : ProtocolCloseFailure
    data class ToolCallProjectionRejected(
        val failure: CodexToolCallProjectionFailure,
    ) : ProtocolCloseFailure
    data class ThreadHistoryProjectionRejected(
        val failure: CodexThreadHistoryProjectionFailure,
    ) : ProtocolCloseFailure
}

internal sealed interface ProtocolRouting {
    data class ForwardUpstream(val message: String) : ProtocolRouting
    data class ForwardDownstream(val message: String) : ProtocolRouting
    data class ForwardDownstreamBatch(
        val messages: NonEmptyProtocolMessages,
    ) : ProtocolRouting
    data class ReplyUpstream(val message: String) : ProtocolRouting
    data class ReplyDownstream(val message: String) : ProtocolRouting
    data class Close(val failure: ProtocolCloseFailure) : ProtocolRouting
}

internal class NonEmptyProtocolMessages private constructor(
    private val first: String,
    private val second: String,
) {
    internal fun inOrder(): List<String> = listOf(first, second)

    companion object {
        internal fun pair(first: String, second: String): NonEmptyProtocolMessages =
            NonEmptyProtocolMessages(first, second)
    }
}

private enum class PendingThreadOperationType { START, RESUME, FORK }

private data class PendingThreadOperation(
    val type: PendingThreadOperationType,
    val sourceThreadId: String? = null,
)

private sealed interface PendingResponse {
    data class Thread(val operation: PendingThreadOperation) : PendingResponse
    data class ItemContainer(val route: CodexItemResponseRoute) : PendingResponse
}

private data class ActiveInvocation(
    val threadId: String,
    val turnId: String,
    val job: Job,
)

internal class CodexProtocolAdapter(
    private val broker: Broker,
    private val contracts: CodexProtocolContracts,
    private val threadStore: ThreadCatalogStore,
    private val activitySink: BrokerInvocationActivitySink = BrokerInvocationActivitySink.Disabled,
    private val pendingObserverPresentations: PendingObserverPresentations =
        PendingObserverPresentations.withCapacity(broker.limits.inFlightCallsPerConnection),
) : AutoCloseable {
    private val pendingResponses = ConcurrentHashMap<String, PendingResponse>()
    private val activeInvocations = ConcurrentHashMap<String, ActiveInvocation>()
    private val invocationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val invocationCapacity = Semaphore(broker.limits.inFlightCallsPerConnection)
    private val ownedNamespaces = broker.catalog.namespaces.map { namespace -> namespace.name }.toSet()

    internal suspend fun fromDownstream(message: String): ProtocolRouting {
        val document = parseObject(message) ?: return ProtocolRouting.Close(
            ProtocolCloseFailure.MalformedDownstream,
        )
        val method = document.string("method")
        return when (method) {
            "initialize" -> initialize(document)
            "thread/start" -> threadStart(document)
            "thread/resume" -> threadResume(document, message)
            "thread/fork" -> threadFork(document, message)
            "turn/interrupt" -> turnInterrupt(document, message)
            else -> CodexItemResponseRoute.forMethod(method ?: "")
                ?.let { route -> itemContainerRequest(document, message, route) }
                ?: ProtocolRouting.ForwardUpstream(message)
        }
    }

    internal suspend fun fromUpstream(message: String): ProtocolRouting {
        val document = parseObject(message) ?: return ProtocolRouting.Close(
            ProtocolCloseFailure.MalformedUpstream,
        )
        val envelope = UpstreamEnvelope.classify(document)
        if (envelope is UpstreamEnvelope.Response) {
            when (val pending = pendingResponses.remove(envelope.id.key)) {
                is PendingResponse.Thread -> return recordThreadOperation(
                    pending.operation,
                    document,
                    message,
                )
                is PendingResponse.ItemContainer -> return projectItemContainerResponse(
                    pending.route,
                    document,
                    message,
                )
                null -> Unit
            }
        }
        if (envelope is UpstreamEnvelope.Request) {
            when (envelope.method) {
                "item/started" -> return projectToolLifecycle(
                    document,
                    message,
                    CodexToolCallLifecycle.STARTED,
                )
                "item/completed" -> return projectToolLifecycle(
                    document,
                    message,
                    CodexToolCallLifecycle.COMPLETED,
                )
            }
            CodexItemNotificationRoute.forMethod(envelope.method)?.let { route ->
                return projectItemContainerNotification(document, message, route)
            }
        }
        if (envelope !is UpstreamEnvelope.Request || envelope.method != "item/tool/call") {
            return ProtocolRouting.ForwardDownstream(message)
        }
        val params = document["params"] as? JsonObject
            ?: return ProtocolRouting.ForwardDownstream(message)
        val namespace = params.string("namespace")?.let { raw -> refined(ProviderNamespace.admit(raw)) }
            ?: return ProtocolRouting.ForwardDownstream(message)
        if (namespace !in ownedNamespaces) return ProtocolRouting.ForwardDownstream(message)
        if (!contracts.admits(CodexOwnedSchema.DYNAMIC_TOOL_CALL_PARAMS, params)) {
            return ProtocolRouting.Close(
                ProtocolCloseFailure.OwnedSchemaRejected(
                    CodexOwnedSchema.DYNAMIC_TOOL_CALL_PARAMS,
                ),
            )
        }
        val id = envelope.id ?: return ProtocolRouting.Close(
            ProtocolCloseFailure.OwnedRequestIdMissing,
        )
        val threadId = params.string("threadId")
        val turnId = params.string("turnId")
        val callId = params.string("callId")
        val tool = params.string("tool")?.let { raw -> refined(ToolName.admit(raw)) }
        val arguments = params["arguments"]
        if (
            threadId == null || turnId == null || callId == null ||
            tool == null || arguments == null
        ) {
            return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        }
        val binding = when (val stored = threadStore.read(threadId)) {
            is ThreadStoreRead.Found -> stored.binding.takeIf {
                it.catalogDigest == broker.catalog.digest
            }
            ThreadStoreRead.Missing -> null
            ThreadStoreRead.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ThreadStoreRejected,
            )
        } ?: return dynamicToolFailure(id, "CATALOG_INCOMPATIBLE")
        val context = when (
            val admission = BrokerInvocationContext.admit(
                threadId,
                turnId,
                callId,
                binding.workingDirectory.path,
            )
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ThreadBindingRejected,
            )
        }
        val invocationId = context.invocationId
        if (!invocationCapacity.tryAcquire()) {
            return dynamicToolFailure(id, "BROKER_OVERLOADED_IN_FLIGHT_CALLS_PER_CONNECTION")
        }
        val address = ToolAddress(namespace, tool)
        val operation = invocationScope.async(start = CoroutineStart.LAZY) {
            broker.dispatch(
                BrokerDispatchRequest(
                    address,
                    arguments,
                    context,
                ),
            )
        }
        val existing = activeInvocations.putIfAbsent(
            invocationId,
            ActiveInvocation(threadId, turnId, operation),
        )
        if (existing != null) {
            operation.cancel()
            invocationCapacity.release()
            return dynamicToolFailure(id, "DUPLICATE_INVOCATION")
        }
        if (
            publishActivity(BrokerInvocationActivity.Started(context, address)) ==
            BrokerInvocationActivityPublication.REJECTED
        ) {
            activeInvocations.remove(invocationId)
            operation.cancel()
            invocationCapacity.release()
            return dynamicToolFailure(id, "BROKER_ACTIVITY_UNAVAILABLE")
        }
        operation.start()
        val dispatch = try {
            operation.await()
        } catch (_: CancellationException) {
            null
        } finally {
            activeInvocations.remove(invocationId)
            invocationCapacity.release()
        }
        val completion = when (dispatch) {
            is BrokerDispatch.Completed -> BrokerInvocationCompletion.COMPLETED
            is BrokerDispatch.Rejected -> BrokerInvocationCompletion.REJECTED
            null -> BrokerInvocationCompletion.CANCELLED
        }
        if (
            publishActivity(
                BrokerInvocationActivity.Finished(context, address, completion),
            ) == BrokerInvocationActivityPublication.REJECTED
        ) {
            return dynamicToolFailure(id, "BROKER_ACTIVITY_UNAVAILABLE")
        }
        val presentation = when (dispatch) {
            is BrokerDispatch.Completed -> dispatch.presentation
            is BrokerDispatch.Rejected -> failurePresentation(dispatch.failure)
            null -> ToolPresentation.text("Invocation cancelled.", success = false)
        }
        val reply = dynamicToolReply(id, presentation)
        if (
            dispatch is BrokerDispatch.Completed && presentation.success &&
            reply is ProtocolRouting.ReplyUpstream
        ) {
            rememberObserver(context.callId, presentation.observer)
        }
        return reply
    }

    private fun projectToolLifecycle(
        document: JsonObject,
        message: String,
        lifecycle: CodexToolCallLifecycle,
    ): ProtocolRouting {
        val params = document["params"] as? JsonObject
            ?: return ProtocolRouting.ForwardDownstream(message)
        val item = params["item"] as? JsonObject
            ?: return ProtocolRouting.ForwardDownstream(message)
        if (item.string("type") != "dynamicToolCall") {
            return ProtocolRouting.ForwardDownstream(message)
        }
        val namespace = item.string("namespace")
            ?.let { raw -> refined(ProviderNamespace.admit(raw)) }
            ?: return ProtocolRouting.ForwardDownstream(message)
        if (namespace !in ownedNamespaces) return ProtocolRouting.ForwardDownstream(message)
        val admittedParams = when (val admission = contracts.admit(lifecycle.schema, params)) {
            is Validation.Validated -> admission.value.element as? JsonObject
                ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
            is Validation.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.OwnedSchemaRejected(lifecycle.schema),
            )
        }
        val admittedItem = admittedParams["item"] as? JsonObject
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        val projectedItem = when (val projection = when (lifecycle) {
            CodexToolCallLifecycle.STARTED -> CodexToolCallProjector.projectStarted(admittedItem)
            CodexToolCallLifecycle.COMPLETED -> CodexToolCallProjector.projectCompleted(admittedItem)
        }) {
            is CodexToolCallProjection.Projected -> projection.item
            is CodexToolCallProjection.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ToolCallProjectionRejected(projection.failure),
            )
        }
        val projected = JsonObject(admittedParams + ("item" to projectedItem))
        if (!contracts.admits(lifecycle.schema, projected)) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ResponseSchemaRejected)
        }
        val ordinaryMessage = JsonObject(document + ("params" to projected)).toString()
        if (lifecycle != CodexToolCallLifecycle.COMPLETED) {
            return ProtocolRouting.ForwardDownstream(ordinaryMessage)
        }
        val callId = admittedItem.string("id")?.let(BrokerCallId::admit)
            ?: return ProtocolRouting.ForwardDownstream(ordinaryMessage)
        val observer = when (val pending = takeObserver(callId)) {
            is PendingObserverPresentationTake.Found -> pending.presentation
            PendingObserverPresentationTake.Missing ->
                return ProtocolRouting.ForwardDownstream(ordinaryMessage)
        }
        val compactItem = when (
            val compact = CodexToolCallProjector.projectCompleted(
                admittedItem,
                CodexToolCallResultProjection.COMPACT_FOR_OBSERVER_COMPANION,
            )
        ) {
            is CodexToolCallProjection.Projected -> compact.item
            is CodexToolCallProjection.Rejected ->
                return ProtocolRouting.ForwardDownstream(ordinaryMessage)
        }
        val compactParams = JsonObject(admittedParams + ("item" to compactItem))
        if (!contracts.admits(lifecycle.schema, compactParams)) {
            return ProtocolRouting.ForwardDownstream(ordinaryMessage)
        }
        val compactMessage = JsonObject(document + ("params" to compactParams)).toString()
        val commentaryParams = CodexObserverMessageProjector.projectCompleted(
            admittedParams,
            callId,
            observer,
        )
        if (!contracts.admits(CodexOwnedSchema.ITEM_COMPLETED_NOTIFICATION, commentaryParams)) {
            return ProtocolRouting.ForwardDownstream(compactMessage)
        }
        val commentaryMessage = JsonObject(document + ("params" to commentaryParams)).toString()
        return ProtocolRouting.ForwardDownstreamBatch(
            NonEmptyProtocolMessages.pair(compactMessage, commentaryMessage),
        )
    }

    private fun projectItemContainerNotification(
        document: JsonObject,
        message: String,
        route: CodexItemNotificationRoute,
    ): ProtocolRouting {
        val params = document["params"] as? JsonObject
            ?: return ProtocolRouting.ForwardDownstream(message)
        if (
            !CodexThreadHistoryProjector.containsOwnedCall(
                route.shape,
                params,
                ownedNamespaces,
            )
        ) {
            return ProtocolRouting.ForwardDownstream(message)
        }
        val admittedParams = when (val admission = contracts.admit(route.schema, params)) {
            is Validation.Validated -> admission.value.element as? JsonObject
                ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
            is Validation.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.OwnedSchemaRejected(route.schema),
            )
        }
        val projected = when (
            val projection = CodexThreadHistoryProjector.projectContainer(
                route.shape,
                admittedParams,
                ownedNamespaces,
            )
        ) {
            CodexThreadHistoryProjection.Unchanged ->
                return ProtocolRouting.ForwardDownstream(message)
            is CodexThreadHistoryProjection.Projected -> projection.result
            is CodexThreadHistoryProjection.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ThreadHistoryProjectionRejected(projection.failure),
            )
        }
        if (!contracts.admits(route.schema, projected)) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ResponseSchemaRejected)
        }
        return ProtocolRouting.ForwardDownstream(
            JsonObject(document + ("params" to projected)).toString(),
        )
    }

    override fun close() {
        invocationScope.cancel()
        activeInvocations.clear()
        pendingResponses.clear()
        try {
            pendingObserverPresentations.clear()
        } catch (_: RuntimeException) {
            // Ephemeral observer state must never affect protocol shutdown.
        }
    }

    private fun rememberObserver(callId: BrokerCallId, observer: ObserverPresentation) {
        val markdown = observer as? ObserverPresentation.Markdown ?: return
        try {
            pendingObserverPresentations.put(callId, markdown)
        } catch (_: RuntimeException) {
            // Observer presentation is explicitly best-effort.
        }
    }

    private fun takeObserver(callId: BrokerCallId): PendingObserverPresentationTake = try {
        pendingObserverPresentations.take(callId)
    } catch (_: RuntimeException) {
        PendingObserverPresentationTake.Missing
    }

    private fun publishActivity(
        activity: BrokerInvocationActivity,
    ): BrokerInvocationActivityPublication = try {
        activitySink.publish(activity)
    } catch (_: RuntimeException) {
        BrokerInvocationActivityPublication.REJECTED
    }

    private fun initialize(document: JsonObject): ProtocolRouting {
        val params = document["params"] ?: JsonObject(emptyMap())
        if (!contracts.admits(CodexOwnedSchema.INITIALIZE_PARAMS, params)) {
            return ownedRequestFailure(document, "INITIALIZE_SCHEMA_REJECTED")
        }
        val paramsObject = params as? JsonObject
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        val capabilities = when (val current = paramsObject["capabilities"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> current
            else -> return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        }
        val refinedCapabilities = JsonObject(capabilities + ("experimentalApi" to JsonPrimitive(true)))
        val refinedParams = JsonObject(paramsObject + ("capabilities" to refinedCapabilities))
        if (!contracts.admits(CodexOwnedSchema.INITIALIZE_PARAMS, refinedParams)) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ResponseSchemaRejected)
        }
        return ProtocolRouting.ForwardUpstream(
            JsonObject(document + ("params" to refinedParams)).toString(),
        )
    }

    private fun threadStart(document: JsonObject): ProtocolRouting {
        val params = document["params"]
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        if (!contracts.admits(CodexOwnedSchema.THREAD_START_PARAMS, params)) {
            return ownedRequestFailure(document, "THREAD_START_SCHEMA_REJECTED")
        }
        val paramsObject = params as? JsonObject
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        val existing = when (val dynamic = paramsObject["dynamicTools"]) {
            null, JsonNull -> JsonArray(emptyList())
            is JsonArray -> dynamic
            else -> return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        }
        val conflict = existing.any { tool ->
            val objectValue = tool as? JsonObject ?: return@any false
            if (objectValue.string("type") != "namespace") return@any false
            val name = objectValue.string("name") ?: return@any false
            refined(ProviderNamespace.admit(name)) in ownedNamespaces
        }
        if (conflict) return ownedRequestFailure(document, "DYNAMIC_NAMESPACE_CONFLICT")
        val refinedDynamicTools = JsonArray(existing + broker.catalog.dynamicNamespaceDocuments())
        val refinedParams = JsonObject(paramsObject + ("dynamicTools" to refinedDynamicTools))
        if (!contracts.admits(CodexOwnedSchema.THREAD_START_PARAMS, refinedParams)) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ResponseSchemaRejected)
        }
        val id = RpcId.admit(document["id"])
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedRequestIdMissing)
        if (
            pendingResponses.putIfAbsent(
                id.key,
                PendingResponse.Thread(PendingThreadOperation(PendingThreadOperationType.START)),
            ) != null
        ) {
            return ownedRequestFailure(document, "DUPLICATE_REQUEST_ID")
        }
        return ProtocolRouting.ForwardUpstream(
            JsonObject(document + ("params" to refinedParams)).toString(),
        )
    }

    private suspend fun threadResume(document: JsonObject, message: String): ProtocolRouting =
        bindExistingThread(
            document,
            message,
            CodexOwnedSchema.THREAD_RESUME_PARAMS,
            PendingThreadOperationType.RESUME,
            rejectHistory = true,
        )

    private suspend fun threadFork(document: JsonObject, message: String): ProtocolRouting =
        bindExistingThread(
            document,
            message,
            CodexOwnedSchema.THREAD_FORK_PARAMS,
            PendingThreadOperationType.FORK,
            rejectHistory = false,
        )

    private suspend fun bindExistingThread(
        document: JsonObject,
        message: String,
        schema: CodexOwnedSchema,
        operation: PendingThreadOperationType,
        rejectHistory: Boolean,
    ): ProtocolRouting {
        val params = document["params"]
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        if (!contracts.admits(schema, params)) {
            return ownedRequestFailure(document, "THREAD_BINDING_SCHEMA_REJECTED")
        }
        val paramsObject = params as? JsonObject
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        val threadId = paramsObject.string("threadId")
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        if (!isAbsentProtocolOverride(paramsObject["path"])) {
            return ownedRequestFailure(document, "CATALOG_INCOMPATIBLE")
        }
        if (rejectHistory && paramsObject["history"] !in setOf(null, JsonNull)) {
            return ownedRequestFailure(document, "CATALOG_INCOMPATIBLE")
        }
        when (val stored = threadStore.read(threadId)) {
            is ThreadStoreRead.Found -> if (stored.binding.catalogDigest != broker.catalog.digest) {
                return ownedRequestFailure(document, "CATALOG_INCOMPATIBLE")
            }
            ThreadStoreRead.Missing -> return ownedRequestFailure(
                document,
                "CATALOG_INCOMPATIBLE",
            )
            ThreadStoreRead.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ThreadStoreRejected,
            )
        }
        val id = RpcId.admit(document["id"])
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedRequestIdMissing)
        if (
            pendingResponses.putIfAbsent(
                id.key,
                PendingResponse.Thread(PendingThreadOperation(operation, threadId)),
            ) != null
        ) {
            return ownedRequestFailure(document, "DUPLICATE_REQUEST_ID")
        }
        return ProtocolRouting.ForwardUpstream(message)
    }

    private fun itemContainerRequest(
        document: JsonObject,
        message: String,
        route: CodexItemResponseRoute,
    ): ProtocolRouting {
        val params = document["params"] ?: JsonObject(emptyMap())
        if (!contracts.admits(route.requestSchema, params)) {
            return ProtocolRouting.ForwardUpstream(message)
        }
        val id = RpcId.admit(document["id"]) ?: return ProtocolRouting.ForwardUpstream(message)
        if (
            pendingResponses.putIfAbsent(id.key, PendingResponse.ItemContainer(route)) != null
        ) {
            return ownedRequestFailure(document, "DUPLICATE_REQUEST_ID")
        }
        return ProtocolRouting.ForwardUpstream(message)
    }

    private fun turnInterrupt(document: JsonObject, message: String): ProtocolRouting {
        val params = document["params"]
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        if (!contracts.admits(CodexOwnedSchema.TURN_INTERRUPT_PARAMS, params)) {
            return ownedRequestFailure(document, "TURN_INTERRUPT_SCHEMA_REJECTED")
        }
        val paramsObject = params as? JsonObject
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        val threadId = paramsObject.string("threadId")
        val turnId = paramsObject.string("turnId")
        if (threadId == null || turnId == null) {
            return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        }
        activeInvocations.values.filter { active ->
            active.threadId == threadId && active.turnId == turnId
        }.forEach { active -> active.job.cancel() }
        return ProtocolRouting.ForwardUpstream(message)
    }

    private suspend fun recordThreadOperation(
        pending: PendingThreadOperation,
        document: JsonObject,
        message: String,
    ): ProtocolRouting {
        if (document.containsKey("error")) return ProtocolRouting.ForwardDownstream(message)
        val result = document["result"]
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        val responseSchema = when (pending.type) {
            PendingThreadOperationType.START -> CodexOwnedSchema.THREAD_START_RESPONSE
            PendingThreadOperationType.RESUME -> CodexOwnedSchema.THREAD_RESUME_RESPONSE
            PendingThreadOperationType.FORK -> CodexOwnedSchema.THREAD_FORK_RESPONSE
        }
        val admittedResult = when (val admission = contracts.admit(responseSchema, result)) {
            is Validation.Validated -> admission.value.element
            is Validation.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ResponseSchemaRejected,
            )
        }
        val resultObject = admittedResult as? JsonObject
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        val historyProjection = when (
            val projection = CodexThreadHistoryProjector.project(resultObject, ownedNamespaces)
        ) {
            CodexThreadHistoryProjection.Unchanged -> projection
            is CodexThreadHistoryProjection.Projected -> projection
            is CodexThreadHistoryProjection.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ThreadHistoryProjectionRejected(projection.failure),
            )
        }
        if (
            historyProjection is CodexThreadHistoryProjection.Projected &&
            !contracts.admits(responseSchema, historyProjection.result)
        ) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ResponseSchemaRejected)
        }
        val threadId = (resultObject["thread"] as? JsonObject)?.string("id")
        val cwd = resultObject.string("cwd")
        if (threadId == null || cwd == null) {
            return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        }
        if (
            pending.type == PendingThreadOperationType.RESUME &&
            threadId != pending.sourceThreadId
        ) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ThreadBindingRejected)
        }
        val cwdPath = try {
            Path.of(cwd)
        } catch (_: InvalidPathException) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ThreadBindingRejected)
        }
        val binding = when (
            val admission = ThreadCatalogBinding.admit(
                threadId,
                broker.catalog.digest,
                cwdPath,
            )
        ) {
            is Refinement.Refined -> admission.value
            is Refinement.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ThreadBindingRejected,
            )
        }
        if (threadStore.write(binding) != ThreadStoreWrite.WRITTEN) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ThreadStoreRejected)
        }
        return when (historyProjection) {
            CodexThreadHistoryProjection.Unchanged -> ProtocolRouting.ForwardDownstream(message)
            is CodexThreadHistoryProjection.Projected -> ProtocolRouting.ForwardDownstream(
                JsonObject(document + ("result" to historyProjection.result)).toString(),
            )
            is CodexThreadHistoryProjection.Rejected -> ProtocolRouting.Close(
                ProtocolCloseFailure.ThreadHistoryProjectionRejected(historyProjection.failure),
            )
        }
    }

    private fun projectItemContainerResponse(
        route: CodexItemResponseRoute,
        document: JsonObject,
        message: String,
    ): ProtocolRouting {
        if (document.containsKey("error")) return ProtocolRouting.ForwardDownstream(message)
        val result = document["result"]
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
        val admittedResult = when (
            val admission = contracts.admit(route.responseSchema, result)
        ) {
            is Validation.Validated -> admission.value.element as? JsonObject
                ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedFieldsMissing)
            is Validation.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ResponseSchemaRejected,
            )
        }
        val projection = when (
            val projected = CodexThreadHistoryProjector.projectContainer(
                route.shape,
                admittedResult,
                ownedNamespaces,
            )
        ) {
            CodexThreadHistoryProjection.Unchanged -> return ProtocolRouting.ForwardDownstream(message)
            is CodexThreadHistoryProjection.Projected -> projected.result
            is CodexThreadHistoryProjection.Rejected -> return ProtocolRouting.Close(
                ProtocolCloseFailure.ThreadHistoryProjectionRejected(projected.failure),
            )
        }
        if (!contracts.admits(route.responseSchema, projection)) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ResponseSchemaRejected)
        }
        return ProtocolRouting.ForwardDownstream(
            JsonObject(document + ("result" to projection)).toString(),
        )
    }

    private fun dynamicToolReply(id: RpcId, presentation: ToolPresentation): ProtocolRouting {
        var result = presentation.dynamicToolResult()
        if (
            canonicalJson(result).toByteArray(Charsets.UTF_8).size >
            broker.limits.maximumToolResultBytes
        ) {
            result = ToolPresentation.text(
                "BROKER_OVERLOADED_MAXIMUM_TOOL_RESULT_BYTES",
                success = false,
            ).dynamicToolResult()
        }
        if (!contracts.admits(CodexOwnedSchema.DYNAMIC_TOOL_CALL_RESPONSE, result)) {
            return ProtocolRouting.Close(ProtocolCloseFailure.ResponseSchemaRejected)
        }
        return ProtocolRouting.ReplyUpstream(
            buildJsonObject {
                put("id", id.value)
                put("result", result)
            }.toString(),
        )
    }

    private fun ToolPresentation.dynamicToolResult(): JsonObject = buildJsonObject {
        put("success", success)
        put("contentItems", buildJsonArray {
            content.forEach { item ->
                add(buildJsonObject {
                    put("type", "inputText")
                    put("text", item.text)
                })
            }
        })
    }

    private fun dynamicToolFailure(id: RpcId, code: String): ProtocolRouting =
        dynamicToolReply(id, ToolPresentation.text(code, success = false))

    private fun ownedRequestFailure(document: JsonObject, code: String): ProtocolRouting {
        val id = RpcId.admit(document["id"])
            ?: return ProtocolRouting.Close(ProtocolCloseFailure.OwnedRequestIdMissing)
        return ProtocolRouting.ReplyDownstream(
            buildJsonObject {
                put("id", id.value)
                put("error", buildJsonObject {
                    put("code", -32040)
                    put("message", "Broker rejected the request.")
                    put("data", buildJsonObject { put("failure", code) })
                })
            }.toString(),
        )
    }

    private fun failurePresentation(failure: BrokerFailure): ToolPresentation =
        ToolPresentation.text(
            canonicalJson(buildJsonObject { put("failure", failure.code()) }),
            success = false,
        )

    private fun BrokerFailure.code(): String = when (this) {
        is BrokerFailure.UnknownNamespace -> "UNKNOWN_NAMESPACE"
        is BrokerFailure.UnknownTool -> "UNKNOWN_TOOL"
        is BrokerFailure.InvalidArguments -> "INVALID_ARGUMENTS"
        is BrokerFailure.ProviderStartupRejected -> code.value
        is BrokerFailure.ProviderInvocationRejected -> code.value
        is BrokerFailure.OutputContractRejected -> "OUTPUT_CONTRACT_REJECTED"
        is BrokerFailure.InvocationCancelled -> "INVOCATION_CANCELLED"
        is BrokerFailure.Overloaded -> when (limit) {
            BrokerLimit.IN_FLIGHT_CALLS_PER_CONNECTION ->
                "BROKER_OVERLOADED_IN_FLIGHT_CALLS_PER_CONNECTION"
            BrokerLimit.IN_FLIGHT_CALLS_PER_PROVIDER ->
                "BROKER_OVERLOADED_IN_FLIGHT_CALLS_PER_PROVIDER"
            BrokerLimit.MAXIMUM_TOOL_ARGUMENT_BYTES ->
                "BROKER_OVERLOADED_MAXIMUM_TOOL_ARGUMENT_BYTES"
            BrokerLimit.MAXIMUM_TOOL_RESULT_BYTES ->
                "BROKER_OVERLOADED_MAXIMUM_TOOL_RESULT_BYTES"
        }
    }

    private fun CodexProtocolContracts.admits(
        schema: CodexOwnedSchema,
        value: JsonElement,
    ): Boolean = admit(schema, value) is Validation.Validated

    private fun io.github.amichne.kast.cli.broker.core.BrokerCatalog.dynamicNamespaceDocuments(): List<JsonObject> =
        namespaces.map { namespace ->
            buildJsonObject {
                put("type", "namespace")
                put("name", namespace.name.value)
                put("description", namespace.description.value)
                put("tools", buildJsonArray {
                    namespace.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name.value)
                            put("description", tool.description.value)
                            put("inputSchema", tool.inputSchema)
                            put("deferLoading", tool.loading ==
                                io.github.amichne.kast.cli.broker.core.ToolLoading.DEFERRED)
                        })
                    }
                })
            }
        }

    private fun parseObject(message: String): JsonObject? = try {
        Json.parseToJsonElement(message) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull

    private fun isAbsentProtocolOverride(value: JsonElement?): Boolean =
        value == null || value == JsonNull || (value as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()

    private fun <Strong, Failure> refined(value: Refinement<Strong, Failure>): Strong? = when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> null
    }
}

private sealed interface UpstreamEnvelope {
    data class Request(val method: String, val id: RpcId?) : UpstreamEnvelope
    data class Response(val id: RpcId) : UpstreamEnvelope
    data object Other : UpstreamEnvelope

    companion object {
        fun classify(document: JsonObject): UpstreamEnvelope {
            val method = document.string("method")
            if (method != null) return Request(method, RpcId.admit(document["id"]))
            val id = RpcId.admit(document["id"])
            return if (
                id != null && (document.containsKey("result") || document.containsKey("error"))
            ) {
                Response(id)
            } else {
                Other
            }
        }

        private fun JsonObject.string(name: String): String? =
            (get(name) as? JsonPrimitive)?.contentOrNull
    }
}

private class RpcId private constructor(
    val value: JsonPrimitive,
    val key: String,
) {
    companion object {
        fun admit(candidate: JsonElement?): RpcId? {
            val primitive = candidate as? JsonPrimitive ?: return null
            if (primitive.isString) return RpcId(primitive, "string:${primitive.content}")
            val numeric = primitive.content.toBigDecimalOrNull() ?: return null
            return RpcId(primitive, "number:${numeric.toPlainString()}")
        }
    }
}
