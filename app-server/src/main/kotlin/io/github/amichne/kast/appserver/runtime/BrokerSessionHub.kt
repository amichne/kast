package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.AppServerAction
import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.protocol.ThreadStoreRead
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolAdapter
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Service-owned upstream sessions. Frontends are bounded subscriptions, not owners of work. */
internal class BrokerSessionHub(
    private val options: KtorBrokerServerOptions,
    private val executionPolicy: WorkspaceExecutionPolicy = WorkspaceExecutionPolicy.Default,
    override val upgrades: DaemonUpgradeGate = DaemonUpgradeGate(),
) : DaemonSessions {
    private val messages = BrokerSessionMessages(options.maximumMessageBytes)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<ClientConnectionId, Session>()
    val tasks = SharedTaskSessions()
    private val activity = SessionActivityJournal(options.sessionActivitySink)
    private val lifecycleApprovals =
        BrokerLifecycleApprovals(scope, tasks, options) { client, message ->
            sessions[client]?.emitApproval(message) ?: PlanApprovalSend.UNAVAILABLE
        }

    private fun respondApproval(id: ClientConnectionId, doc: JsonObject): BrokerPlanApprovalReply =
        lifecycleApprovals.respond(id, doc)

    private val fence = InvocationFence(options.invocationJournal)
    private val invocations = InvocationResponses(fence)

    fun initialization(): InvocationAdmission = fence.initialization()

    private val transitions = upgrades.transition
    private val registration = Any()
    private val closed = AtomicBoolean(false)
    private val admission = kotlinx.coroutines.sync.Semaphore(options.maximumConnections)
    private val workspaceExecution = WorkspaceExecution(scope, executionPolicy)

    private enum class RequestPhase {
        AWAITING_CLIENT,
        ANSWER_SENT,
    }

    private data class ServerRequest(
        val source: Session,
        val recipient: ClientConnectionId,
        val originalId: JsonElement,
        val thread: BrokerThreadId?,
        var phase: RequestPhase = RequestPhase.AWAITING_CLIENT,
    )

    private val upgradeUncertainty = ConcurrentHashMap.newKeySet<UpgradeBlocker>()
    private val serverRequests = ConcurrentHashMap<String, ServerRequest>()

    inner class Session
    internal constructor(
        val id: ClientConnectionId,
        private val upstream: BrokerUpstreamConnection,
        private val initializeId: JsonElement,
        val clientName: String,
    ) {
        val output = Channel<String>(BrokerOperationalLimits.sessionChannelCapacity)
        private val outgoing = Channel<String>(BrokerOperationalLimits.sessionChannelCapacity)
        private val adapter =
            CodexProtocolAdapter(
                options.broker,
                options.contracts,
                options.threadStore,
                options.activitySink,
                sessionBootstrap = options.sessionBootstrap,
                enrollment = options.enrollment,
                bindingOwner = options.bindingOwner,
            )
        @Volatile private var handshake = Handshake.RESPONSE_PENDING
        @Volatile private var attached = true
        private val calls = AtomicInteger()
        private val controlCommands = ConcurrentHashMap<String, Pair<BrokerThreadId, String>>()
        private val pendingThreads = ConcurrentHashMap<String, String>()
        private val requests = SessionRequests()

        internal fun upgradeBlockers(): Set<UpgradeBlocker> = buildSet {
            if (handshake != Handshake.READY) add(UpgradeBlocker.SESSION_INITIALIZING)
            if (calls.get() != 0) add(UpgradeBlocker.REQUEST_PENDING)
            addAll(requests.upgradeBlockers())
        }

        private fun awaitInvocationResponse(response: Deferred<ProtocolRouting>, doc: JsonObject) {
            calls.incrementAndGet()
            scope.launch {
                try {
                    val result = response.await()
                    route(withResponseId(result, doc["id"]))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    val rejected =
                        ProtocolRouting.ReplyUpstream(
                            toolFailure(doc, "OUTCOME_UNCERTAIN"),
                            io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty.UNCERTAIN,
                        )
                    route(rejected)
                } finally {
                    calls.decrementAndGet()
                    retireIfIdle()
                }
            }
        }

        suspend fun start(message: String) {
            if (requests.admit(null) is io.github.amichne.kast.kernel.Refinement.Rejected) return
            activity.publish(SessionActivity(id, SessionStage.ADMISSION, SessionOutcome.STARTED))
            scope.launch {
                try {
                    for (next in outgoing) {
                        if (
                            withTimeoutOrNull(BrokerOperationalLimits.sessionSend.value) { upstream.send(next) } !=
                                BrokerUpstreamSend.SENT
                        )
                            break
                    }
                } finally {
                    close()
                }
            }
            route(adapter.fromDownstream(message))
            scope.launch {
                try {
                    while (isActive) {
                        when (val frame = upstream.receive()) {
                            is BrokerUpstreamFrame.Text -> receive(frame.message)
                            BrokerUpstreamFrame.Closed,
                            BrokerUpstreamFrame.Rejected -> break
                        }
                    }
                } finally {
                    close()
                }
            }
            scope.launch {
                delay(options.connectionInitializationTimeoutMillis)
                if (handshake != Handshake.READY) close()
            }
        }

        suspend fun accept(message: String) = transitions.withLock { acceptLocked(message) }

        private suspend fun acceptLocked(message: String) {
            val doc = messages.parse(message) ?: return close()
            val method = doc.text("method")
            if (
                method != "kast/appServer/status" &&
                    upgrades.admitWork() is io.github.amichne.kast.kernel.Refinement.Rejected
            ) {
                emit(messages.rejection(doc, DaemonUpgradeFailure.ADMISSION_SEALED.name))
                return
            }
            if (method == "initialize" || handshake == Handshake.RESPONSE_PENDING || handshake == Handshake.CLOSED)
                return close()
            if (handshake == Handshake.INITIALIZED_PENDING) {
                if (method != "initialized" || doc.containsKey("id")) return close()
                handshake = Handshake.READY
                activity.publish(SessionActivity(id, SessionStage.HANDSHAKE, SessionOutcome.READY))
                sendUpstream(message)
                return
            }
            if (method == "initialized") return close()
            if (method?.startsWith("kast/appServer/") == true) {
                emit(control(doc))
                return
            }
            val params = doc["params"] as? JsonObject
            val thread = params?.text("threadId")?.let(BrokerThreadId::admit)
            if (
                thread != null &&
                    !tasks.contains(thread) &&
                    options.threadStore.read(thread.value) is ThreadStoreRead.Found
            ) {
                if (method != "thread/resume") {
                    emit(messages.rejection(doc, "TASK_ATTACHMENT_REQUIRED"))
                    return
                }
            }
            if (thread != null && tasks.contains(thread)) {
                if (method == "thread/unsubscribe") {
                    tasks.unsubscribe(thread, id)
                    emit(messages.unsubscribed(doc))
                    return
                }
                if (method !in BrokerSessionMethodPolicy.readMethods && method != null) {
                    val admission = tasks.authorize(thread, id)
                    if (admission is ControlResult.Rejected) {
                        emit(messages.rejection(doc, admission.failure.name))
                        return
                    }
                    val requestKey = doc["id"]?.toString() ?: return close()
                }
            }
            if (method == null) {
                when (val approvalReply = respondApproval(id, doc)) {
                    BrokerPlanApprovalReply.Unowned -> Unit
                    BrokerPlanApprovalReply.Handled -> return
                    is BrokerPlanApprovalReply.Rejected -> {
                        emit(messages.rejection(doc, approvalReply.failure.name))
                        return
                    }
                }
                val key = doc["id"]?.toString() ?: return close()
                val pending = serverRequests[key]
                if (pending != null) {
                    if (pending.recipient != id) {
                        emit(messages.rejection(doc, "NOT_RESPONSIBLE_CLIENT"))
                        return
                    }
                    if (pending.phase != RequestPhase.AWAITING_CLIENT) {
                        emit(messages.rejection(doc, "REQUEST_ALREADY_ANSWERED"))
                        return
                    }
                    pending.phase = RequestPhase.ANSWER_SENT
                    pending.source.sendUpstream(JsonObject(doc + ("id" to pending.originalId)).toString())
                    return
                }
            }
            val admittedRequest =
                when (val admission = requests.admit(if (method == null) null else doc["id"] ?: JsonNull)) {
                    is io.github.amichne.kast.kernel.Refinement.Rejected -> {
                        emit(messages.rejection(doc, admission.failure.name))
                        return
                    }
                    is io.github.amichne.kast.kernel.Refinement.Refined -> admission.value
                }
            if (method in setOf("thread/start", "thread/resume", "thread/fork")) {
                doc["id"]?.toString()?.let { pendingThreads[it] = method!! }
            }
            val routing = adapter.fromDownstream(message)
            if (routing is ProtocolRouting.ForwardUpstream && method == "turn/interrupt" && thread != null) {
                // Schema validation above and shared-task authorization precede cancellation.
                val turn = params?.text("turnId")?.let(io.github.amichne.kast.appserver.core.BrokerTurnId::admit)
                if (turn != null && tasks.authorize(thread, id) == ControlResult.Accepted) {
                    lifecycleApprovals.cancel(thread, turn)
                    when (val bound = adapter.boundWorkspace(thread)) {
                        is io.github.amichne.kast.kernel.Refinement.Refined ->
                            workspaceExecution.cancel(bound.value.id, thread, turn)
                        is io.github.amichne.kast.kernel.Refinement.Rejected -> {
                            requests.abandon(admittedRequest)
                            emit(messages.rejection(doc, bound.failure.code))
                            return
                        }
                    }
                }
            }
            if (routing is ProtocolRouting.ForwardUpstream && thread != null && method != null) {
                if (tasks.contains(thread) && method !in BrokerSessionMethodPolicy.readMethods) {
                    val requestKey = doc["id"]?.toString() ?: return close()
                    tasks.pending(thread, requestKey)
                    controlCommands[requestKey] = thread to method
                }
            }
            if (routing !is ProtocolRouting.ForwardUpstream) requests.abandon(admittedRequest)
            route(routing)
        }

        private suspend fun receive(message: String) = transitions.withLock { receiveLocked(message) }

        private suspend fun receiveLocked(message: String) {
            val doc = messages.parse(message) ?: return close()
            if (upgrades.admitWork() is io.github.amichne.kast.kernel.Refinement.Rejected) {
                if (doc.text("method") == "item/tool/call")
                    sendUpstream(toolFailure(doc, DaemonUpgradeFailure.ADMISSION_SEALED.name))
                else close()
                return
            }
            if (handshake == Handshake.RESPONSE_PENDING && doc["id"] == initializeId && doc.text("method") == null) {
                if (doc.containsKey("error")) {
                    emit(message)
                    return close()
                }
                handshake = Handshake.INITIALIZED_PENDING
            }
            val method = doc.text("method")
            val params = doc["params"] as? JsonObject
            val thread = params?.text("threadId")?.let(BrokerThreadId::admit)
            if (
                method == "item/tool/call" &&
                    params?.text("namespace") in options.broker.catalog.namespaces.map { it.name.value }
            ) {
                val interactionLimit =
                    when (val bootstrap = options.sessionBootstrap) {
                        null -> executionPolicy.interaction
                        else ->
                            bootstrap.tools.definitions
                                .singleOrNull { it.name.value == params?.text("tool") }
                                ?.executionBudget
                                ?.invocation
                                ?: run {
                                    sendUpstream(toolFailure(doc, "UNQUALIFIED_OPERATION_BUDGET"))
                                    return
                                }
                    }
                val identity =
                    InvocationIdentity(
                        thread ?: return close(),
                        params.text("turnId")?.let(io.github.amichne.kast.appserver.core.BrokerTurnId::admit)
                            ?: return close(),
                        params.text("callId")?.let(io.github.amichne.kast.appserver.core.BrokerCallId::admit)
                            ?: return close(),
                    )
                val fingerprint =
                    InvocationFingerprint.of(io.github.amichne.kast.appserver.schema.canonicalJson(params))
                val admission =
                    invocations.begin(
                        identity,
                        fingerprint,
                        reject = { failure -> messages.rejectedInvocation(doc, failure) },
                        settled = { certainty ->
                            activity.publish(
                                SessionActivity(
                                    id,
                                    SessionStage.INVOCATION,
                                    if (
                                        certainty ==
                                            io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty.KNOWN
                                    )
                                        SessionOutcome.COMPLETED
                                    else SessionOutcome.UNCERTAIN,
                                )
                            )
                        },
                    )
                val response =
                    when (admission) {
                        is InvocationResponseAdmission.Rejected -> {
                            activity.publish(SessionActivity(id, SessionStage.INVOCATION, SessionOutcome.REJECTED))
                            sendUpstream(messages.rejectedInvocation(doc, admission.failure).message)
                            return
                        }
                        is InvocationResponseAdmission.Existing -> admission.result
                        is InvocationResponseAdmission.Started -> {
                            val invocation = admission.invocation
                            activity.publish(SessionActivity(id, SessionStage.INVOCATION, SessionOutcome.STARTED))
                            val execution =
                                try {
                                    when (val bound = adapter.boundWorkspace(identity.thread)) {
                                        is io.github.amichne.kast.kernel.Refinement.Rejected ->
                                            CompletableDeferred<WorkspaceExecutionResult>(
                                                WorkspaceExecutionResult.Completed(
                                                    ProtocolRouting.ReplyUpstream(toolFailure(doc, bound.failure.code))
                                                )
                                            )
                                        is io.github.amichne.kast.kernel.Refinement.Refined -> {
                                            val submit:
                                                (BrokerInvocationApproval) -> Deferred<WorkspaceExecutionResult> =
                                                { approval ->
                                                    workspaceExecution.submit(
                                                        WorkspaceExecutionIdentity(
                                                            bound.value.id,
                                                            id,
                                                            identity.thread,
                                                            identity.turn,
                                                            identity.call,
                                                        ),
                                                        interactionLimit,
                                                    ) {
                                                        try {
                                                            val dispatched = adapter.fromUpstream(message, approval)
                                                            currentCoroutineContext().ensureActive()
                                                            invocation.settle(dispatched)
                                                        } catch (failure: Exception) {
                                                            invocation.settle(
                                                                messages.rejectedInvocation(
                                                                    doc,
                                                                    InvocationFenceFailure.OUTCOME_UNCERTAIN,
                                                                )
                                                            )
                                                            throw failure
                                                        }
                                                    }
                                                }
                                            lifecycleApprovals.submit(
                                                id,
                                                WorkspaceInvocationBinding(bound.value, identity),
                                                params,
                                                doc,
                                                submit,
                                            ) ?: submit(BrokerInvocationApproval.Absent)
                                        }
                                    }
                                } catch (_: Exception) {
                                    CompletableDeferred(
                                        WorkspaceExecutionResult.Completed(
                                            messages.rejectedInvocation(doc, InvocationFenceFailure.OUTCOME_UNCERTAIN)
                                        )
                                    )
                                }
                            scope.launch {
                                try {
                                    val result =
                                        when (val completed = execution.await()) {
                                            is WorkspaceExecutionResult.Completed -> completed.routing
                                            is WorkspaceExecutionResult.Rejected ->
                                                ProtocolRouting.ReplyUpstream(
                                                    toolFailure(doc, completed.failure.name),
                                                    completed.failure.certainty,
                                                )
                                        }
                                    invocation.complete(result)
                                } catch (failure: CancellationException) {
                                    invocation.complete(
                                        messages.rejectedInvocation(doc, InvocationFenceFailure.OUTCOME_UNCERTAIN)
                                    )
                                    throw failure
                                } catch (_: Exception) {
                                    invocation.complete(
                                        messages.rejectedInvocation(doc, InvocationFenceFailure.OUTCOME_UNCERTAIN)
                                    )
                                }
                            }
                            invocation.result
                        }
                    }
                awaitInvocationResponse(response, doc)
                return
            }
            if (method != null && doc.containsKey("id")) {
                val recipientId =
                    if (method in BrokerSessionMethodPolicy.approvalMethods && thread != null && tasks.contains(thread))
                        tasks.controller(thread)
                    else id
                val recipient = recipientId?.let(sessions::get)
                if (recipient == null || !recipient.attached) {
                    sendUpstream(messages.rejection(doc, "RESPONDER_DISCONNECTED"))
                    return
                }
                if (serverRequests.size >= BrokerOperationalLimits.maximumServerRequests) {
                    sendUpstream(messages.rejection(doc, "REQUEST_CAPACITY_EXCEEDED"))
                    return
                }
                val routedId = JsonPrimitive("kast-request-${java.util.UUID.randomUUID()}")
                val key = routedId.toString()
                serverRequests[key] = ServerRequest(this, recipient.id, doc.getValue("id"), thread)
                thread?.let { tasks.pending(it, key) }
                recipient.emit(JsonObject(doc + ("id" to routedId)).toString())
                return
            }
            if (method == "serverRequest/resolved") {
                val original = params?.get("requestId")
                val matching =
                    serverRequests.entries.filter { it.value.source == this && it.value.originalId == original }
                matching.forEach { (key, request) ->
                    serverRequests.remove(key)
                    request.thread?.let { tasks.resolved(it, key) }
                    sessions[request.recipient]?.emit(
                        JsonObject(
                                doc +
                                    ("params" to
                                        JsonObject(params.orEmpty() + ("requestId" to Json.parseToJsonElement(key))))
                            )
                            .toString()
                    )
                }
                if (matching.isNotEmpty()) {
                    sessions.values.toList().forEach { it.retireIfIdle() }
                    return
                }
            }
            val authoritative = thread != null && tasks.controller(thread) == id
            if (thread != null && tasks.contains(thread) && authoritative) {
                if (method == "turn/started") {
                    val turn = (params["turn"] as? JsonObject)?.text("id") ?: params.text("turnId")
                    if (turn != null) tasks.begin(thread, turn)
                }
                if (method == "turn/completed") {
                    val completedTurn = (params["turn"] as? JsonObject)?.text("id") ?: params.text("turnId")
                    if (completedTurn != null) tasks.finish(thread, completedTurn)
                }
            }
            if (method == null)
                doc["id"]?.toString()?.let { key ->
                    controlCommands.remove(key)?.let { (ownedThread, command) ->
                        val returnedTurn = ((doc["result"] as? JsonObject)?.get("turn") as? JsonObject)?.text("id")
                        if (returnedTurn != null) tasks.begin(ownedThread, returnedTurn)
                        if (
                            doc.containsKey("error") ||
                                returnedTurn != null ||
                                command !in setOf("turn/start", "thread/queue/start", "review/start")
                        )
                            tasks.resolved(ownedThread, key)
                    }
                }
            val routing = adapter.fromUpstream(message)
            requests.resolveReply(doc, routing)
            val lifecycleItem = params?.get("item") as? JsonObject
            if (
                method in setOf("item/started", "item/completed") &&
                    lifecycleItem?.text("type") == "dynamicToolCall" &&
                    lifecycleItem.text("namespace") in options.broker.catalog.namespaces.map { it.name.value }
            ) {
                when (routing) {
                    is ProtocolRouting.ForwardDownstream ->
                        activity.publish(SessionActivity(id, SessionStage.TOOL_DISPLAY, SessionOutcome.COMPLETED))
                    is ProtocolRouting.Close ->
                        activity.publish(
                            SessionActivity(id, SessionStage.TOOL_DISPLAY, SessionOutcome.REJECTED, routing.failure)
                        )
                    else -> Unit
                }
            }
            val pending = doc["id"]?.toString()?.let(pendingThreads::remove)
            if (pending != null && !doc.containsKey("error")) {
                val result = doc["result"] as? JsonObject
                val returned = (result?.get("thread") as? JsonObject)?.text("id")?.let(BrokerThreadId::admit)
                if (returned != null && options.threadStore.read(returned.value) is ThreadStoreRead.Found) {
                    val newTask = !tasks.contains(returned)
                    val attachment = tasks.attach(returned, id)
                    if (attachment is ControlResult.Rejected) {
                        emit(messages.rejection(doc, attachment.failure.name))
                        return
                    }
                    val turns = ((result?.get("thread") as? JsonObject)?.get("turns") as? JsonArray).orEmpty()
                    if (newTask && pending == "thread/resume") {
                        val current = (result?.get("thread") as? JsonObject)
                        val activeTurn =
                            turns
                                .mapNotNull { it as? JsonObject }
                                .lastOrNull { it.text("status") == "inProgress" }
                                ?.text("id")
                        val state = (current?.get("status") as? JsonObject)?.text("type")
                        when {
                            activeTurn != null -> tasks.begin(returned, activeTurn)
                            state == "idle" -> Unit
                            options.enrollment ==
                                io.github.amichne.kast.appserver.WorkspaceEnrollment.ProtocolFixture &&
                                turns.isEmpty() -> Unit
                            else -> tasks.requireReconciliation(returned)
                        }
                    }
                    if (
                        (((result?.get("thread") as? JsonObject)?.get("status") as? JsonObject)?.text("type")) == "idle"
                    )
                        tasks.reconcileIdle(returned)
                    turns.forEach { value ->
                        (value as? JsonObject)?.let { turn ->
                            turn.text("id")?.let {
                                tasks.reconcile(
                                    returned,
                                    it,
                                    turn.text("status") in setOf("completed", "interrupted", "failed"),
                                )
                            }
                        }
                    }
                }
            }
            // Only the controller's upstream stream is authoritative for a shared task.
            if (
                thread != null &&
                    tasks.contains(thread) &&
                    method != null &&
                    !doc.containsKey("id") &&
                    routing is ProtocolRouting.ForwardDownstream
            ) {
                if (authoritative) {
                    tasks.observers(thread).forEach { sessions[it]?.emit(routing.message) }
                }
            } else route(routing)
            sessions.values.toList().forEach { it.retireIfIdle() }
        }

        private suspend fun route(routing: ProtocolRouting) {
            when (routing) {
                is ProtocolRouting.ForwardUpstream -> sendUpstream(routing.message)
                is ProtocolRouting.ReplyUpstream -> sendUpstream(routing.message)
                is ProtocolRouting.ForwardDownstream -> emit(routing.message)
                is ProtocolRouting.ReplyDownstream -> emit(routing.message)
                is ProtocolRouting.Close -> {
                    activity.publish(
                        SessionActivity(id, SessionStage.TRANSPORT, SessionOutcome.REJECTED, routing.failure)
                    )
                    close()
                }
            }
        }

        private suspend fun sendUpstream(message: String) {
            if (outgoing.trySend(message).isFailure) close()
        }

        internal fun emit(message: String) {
            if (attached && output.trySend(message).isFailure) output.close()
        }

        internal fun emitApproval(message: String): PlanApprovalSend =
            if (attached && output.trySend(message).isSuccess) PlanApprovalSend.SENT else PlanApprovalSend.UNAVAILABLE

        suspend fun detach() = transitions.withLock {
            activity.publish(SessionActivity(id, SessionStage.SUBSCRIPTION, SessionOutcome.DETACHED))
            attached = false
            lifecycleApprovals.disconnect(id)
            output.close()
            serverRequests.entries
                .filter { it.value.recipient == id && it.value.phase == RequestPhase.AWAITING_CLIENT }
                .forEach { (key, request) ->
                    request.source.sendUpstream(
                        messages.rejection(buildJsonObject { put("id", request.originalId) }, "RESPONDER_DISCONNECTED")
                    )
                    request.phase = RequestPhase.ANSWER_SENT
                }
            tasks.disconnect(id)
            retireIfIdle()
        }

        private suspend fun retireIfIdle() {
            if (requests.upgradeBlockers().isNotEmpty()) return
            val ownsPendingRequest = serverRequests.values.any { it.source == this }
            if (!attached && calls.get() == 0 && !tasks.hasWork(id) && !ownsPendingRequest) close()
        }

        suspend fun close() {
            when (requests.retire()) {
                SessionRetirement.ALREADY_RETIRED -> return
                SessionRetirement.UNCERTAIN -> upgradeUncertainty.add(UpgradeBlocker.RECONCILIATION_REQUIRED)
                SessionRetirement.IDLE -> Unit
            }
            lifecycleApprovals.disconnect(id)
            activity.publish(SessionActivity(id, SessionStage.TRANSPORT, SessionOutcome.RETIRED))
            serverRequests.entries
                .filter { it.value.source == this }
                .forEach { (key, request) ->
                    serverRequests.remove(key)
                    request.thread?.let { thread ->
                        tasks.requireReconciliation(thread)
                        sessions[request.recipient]?.emit(
                            buildJsonObject {
                                put("method", "serverRequest/resolved")
                                put(
                                    "params",
                                    buildJsonObject {
                                        put("threadId", thread.value)
                                        put("requestId", Json.parseToJsonElement(key))
                                    },
                                )
                            }
                                .toString()
                        )
                        activity.publish(SessionActivity(id, SessionStage.RECONCILIATION, SessionOutcome.UNCERTAIN))
                    }
                }
            handshake = Handshake.CLOSED
            attached = false
            output.close()
            outgoing.close()
            tasks.upstreamLost(id)
            try {
                adapter.closeAndJoin()
                withContext(NonCancellable) {
                    withTimeoutOrNull(BrokerOperationalLimits.sessionClose.value) { upstream.close() }
                }
            } finally {
                sessions.remove(id)
                admission.release()
            }
        }
    }

    suspend fun attach(initialize: String): Session? = transitions.withLock { attachLocked(initialize) }

    private suspend fun attachLocked(initialize: String): Session? {
        if (upgrades.admitWork() is io.github.amichne.kast.kernel.Refinement.Rejected) return null
        if (closed.get() || !admission.tryAcquire()) return null
        val doc =
            messages.parse(initialize)
                ?: run {
                    admission.release()
                    return null
                }
        val id =
            doc["id"]
                ?: run {
                    admission.release()
                    return null
                }
        val upstream =
            when (val result = options.upstream.connect()) {
                is BrokerUpstreamConnectionAdmission.Connected -> result.connection
                BrokerUpstreamConnectionAdmission.Rejected -> {
                    admission.release()
                    return null
                }
            }
        val clientName =
            ((doc["params"] as? JsonObject)?.get("clientInfo") as? JsonObject)?.text("name")?.take(128).orEmpty()
        val session = Session(ClientConnectionId.fresh(), upstream, id, clientName)
        val registered =
            synchronized(registration) {
                if (closed.get()) false
                else {
                    sessions[session.id] = session
                    tasks.connect(session.id)
                    true
                }
            }
        if (!registered) {
            upstream.close()
            admission.release()
            return null
        }
        try {
            session.start(initialize)
        } catch (failure: Exception) {
            session.close()
            throw failure
        }
        return session
    }

    suspend fun close() {
        val retiring =
            synchronized(registration) {
                if (!closed.compareAndSet(false, true)) return
                sessions.values.toList()
            }
        try {
            retiring.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                    /* Retire all owned sessions. */
                }
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    override fun upgradeBlockers(): Set<UpgradeBlocker> = buildSet {
        sessions.values.forEach { addAll(it.upgradeBlockers()) }
        addAll(tasks.upgradeBlockers())
        if (serverRequests.isNotEmpty()) add(UpgradeBlocker.REQUEST_PENDING)
        addAll(invocations.upgradeBlockers())
        addAll(workspaceExecution.upgradeBlockers())
        addAll(upgradeUncertainty)
    }

    override fun inspectSessions(): DaemonSessionInspection =
        synchronized(registration) {
            if (closed.get()) DaemonSessionInspection.Closed
            else
                DaemonSessionInspection.Prepared(
                    sessions.values.map { DaemonConnectionDocument(it.id.value, it.clientName) }.sortedBy { it.id },
                    tasks.views().map { it.document() }.sortedBy { it.threadId },
                )
        }

    override fun controlSession(action: AppServerAction.Control): ControlResult =
        synchronized(registration) {
            if (closed.get()) ControlResult.Rejected(ControlFailure.HOST_UNAVAILABLE) else tasks.control(action)
        }

    private fun control(doc: JsonObject): String {
        val method = doc.text("method")
        val result =
            if (method == "kast/appServer/status")
                buildJsonObject {
                    put("qualification", options.qualification?.document() ?: JsonNull)
                    put("activity", JsonArray(activity.snapshot().map { it.document() }))
                    put("workspaceExecution", workspaceExecution.snapshot())
                    put(
                        "connections",
                        buildJsonArray {
                            sessions.values.forEach { session ->
                                add(
                                    buildJsonObject {
                                        put("id", session.id.value)
                                        put("client", session.clientName)
                                    }
                                )
                            }
                        },
                    )
                    put(
                        "tasks",
                        buildJsonArray {
                            tasks.views().forEach { task ->
                                add(
                                    buildJsonObject {
                                        put("threadId", task.thread.value)
                                        put("controller", task.controller?.value?.let(::JsonPrimitive) ?: JsonNull)
                                        put("controllerLease", task.lease?.value?.let(::JsonPrimitive) ?: JsonNull)
                                        put(
                                            "activity",
                                            when (task.activity) {
                                                TaskActivity.Idle -> "idle"
                                                is TaskActivity.Active -> "active"
                                                is TaskActivity.ReconciliationRequired -> "reconciliation_required"
                                            },
                                        )
                                        put("pendingRequests", task.pendingRequests)
                                    }
                                )
                            }
                        },
                    )
                }
            else return messages.rejection(doc, ControlFailure.UNSUPPORTED_OPERATION.name)
        return buildJsonObject {
            put("id", doc["id"] ?: JsonNull)
            put("result", result)
        }
            .toString()
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun withResponseId(result: ProtocolRouting, id: JsonElement?): ProtocolRouting =
        if (result is ProtocolRouting.ReplyUpstream && id != null) {
            val doc = messages.parse(result.message)
            if (doc == null) result else result.copy(message = JsonObject(doc + ("id" to id)).toString())
        } else result
}
