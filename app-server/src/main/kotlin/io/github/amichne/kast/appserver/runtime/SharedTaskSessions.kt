package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerThreadId
import java.util.UUID

@JvmInline
internal value class ClientConnectionId private constructor(val value: String) {
    companion object {
        fun fresh() = ClientConnectionId(UUID.randomUUID().toString())
        fun admit(raw: String): ClientConnectionId? = raw.takeIf { it.length == 36 && runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }?.let(::ClientConnectionId)
    }
}

@JvmInline
internal value class ControllerLeaseId private constructor(val value: String) {
    companion object { fun fresh() = ControllerLeaseId(UUID.randomUUID().toString()) }
}
private sealed interface TaskController {
    data object Unclaimed : TaskController
    data class Leased(val owner: ClientConnectionId, val lease: ControllerLeaseId) : TaskController
}

internal enum class ControlFailure { INVALID_ID, TASK_UNKNOWN, CONNECTION_UNKNOWN, CONTROLLER_CONFLICT, TASK_BUSY, NOT_CONTROLLER, CAPACITY_EXCEEDED, RECONCILIATION_REQUIRED }
internal sealed interface ControlResult {
    data object Accepted : ControlResult
    data class Rejected(val failure: ControlFailure) : ControlResult
}
internal sealed interface TaskActivity {
    data object Idle : TaskActivity
    data class Active(val turnId: String) : TaskActivity
    data class ReconciliationRequired(val turnId: String?) : TaskActivity
}
internal data class TaskSessionView(val thread: BrokerThreadId, val controller: ClientConnectionId?, val observers: Set<ClientConnectionId>, val activity: TaskActivity, val pendingRequests: Int, val lease: ControllerLeaseId?)

/** Service-confined controller and subscription state, independent of frontend lifetimes. */
internal class SharedTaskSessions(private val capacity: Int = 4_096) {
    private class Session(val thread: BrokerThreadId, initial: ClientConnectionId?) {
        private var control: TaskController = initial?.let { TaskController.Leased(it,ControllerLeaseId.fresh()) } ?: TaskController.Unclaimed
        var controller: ClientConnectionId?
            get() = (control as? TaskController.Leased)?.owner
            set(owner) { control = owner?.let { TaskController.Leased(it,ControllerLeaseId.fresh()) } ?: TaskController.Unclaimed }
        val lease: ControllerLeaseId? get() = (control as? TaskController.Leased)?.lease
        val observers = linkedSetOf<ClientConnectionId>()
        var activity: TaskActivity = TaskActivity.Idle
        val pending = linkedSetOf<String>()
    }
    private val sessions = linkedMapOf<BrokerThreadId, Session>()
    private val connections = linkedSetOf<ClientConnectionId>()

    @Synchronized fun connect(id: ClientConnectionId) { connections += id }
    @Synchronized fun contains(thread: BrokerThreadId) = thread in sessions
    @Synchronized fun attach(thread: BrokerThreadId, client: ClientConnectionId): ControlResult {
        if (client !in connections) return ControlResult.Rejected(ControlFailure.CONNECTION_UNKNOWN)
        val current = sessions[thread] ?: run {
            if (sessions.size >= capacity) return ControlResult.Rejected(ControlFailure.CAPACITY_EXCEEDED)
            Session(thread, client).also { sessions[thread] = it }
        }
        current.observers += client
        return ControlResult.Accepted
    }
    @Synchronized fun claim(thread: BrokerThreadId, client: ClientConnectionId): ControlResult {
        val current = sessions[thread] ?: return ControlResult.Rejected(ControlFailure.TASK_UNKNOWN)
        if (client !in connections || client !in current.observers) return ControlResult.Rejected(ControlFailure.CONNECTION_UNKNOWN)
        if (current.activity is TaskActivity.ReconciliationRequired) return ControlResult.Rejected(ControlFailure.RECONCILIATION_REQUIRED)
        if (current.controller == client) return ControlResult.Accepted
        if (current.activity != TaskActivity.Idle || current.pending.isNotEmpty()) return ControlResult.Rejected(ControlFailure.TASK_BUSY)
        if (current.controller != null) return ControlResult.Rejected(ControlFailure.CONTROLLER_CONFLICT)
        current.controller = client
        return ControlResult.Accepted
    }
    @Synchronized fun release(thread: BrokerThreadId, client: ClientConnectionId): ControlResult {
        val current = sessions[thread] ?: return ControlResult.Rejected(ControlFailure.TASK_UNKNOWN)
        if (current.controller != client) return ControlResult.Rejected(ControlFailure.NOT_CONTROLLER)
        if (current.activity != TaskActivity.Idle || current.pending.isNotEmpty()) return ControlResult.Rejected(ControlFailure.TASK_BUSY)
        current.controller = null
        return ControlResult.Accepted
    }
    @Synchronized fun authorize(thread: BrokerThreadId, client: ClientConnectionId): ControlResult =
        when (val current = sessions[thread]) {
            null -> ControlResult.Rejected(ControlFailure.TASK_UNKNOWN)
            else -> if (current.activity is TaskActivity.ReconciliationRequired) ControlResult.Rejected(ControlFailure.RECONCILIATION_REQUIRED) else if (current.controller == client) ControlResult.Accepted else ControlResult.Rejected(ControlFailure.NOT_CONTROLLER)
        }
    @Synchronized fun begin(thread: BrokerThreadId, turn: String) { sessions[thread]?.activity = TaskActivity.Active(turn) }
    @Synchronized fun finish(thread: BrokerThreadId, turn: String? = null) {
        val activity = sessions[thread]?.activity
        if (activity is TaskActivity.ReconciliationRequired) return
        if (turn != null && activity is TaskActivity.Active && activity.turnId != turn) return
        sessions[thread]?.let {
            it.activity = TaskActivity.Idle
            if (it.pending.isEmpty() && it.controller !in connections) it.controller = null
        }
    }
    @Synchronized fun pending(thread: BrokerThreadId, request: String) { sessions[thread]?.pending?.add(request) }
    @Synchronized fun resolved(thread: BrokerThreadId, request: String) {
        sessions[thread]?.let {
            it.pending.remove(request)
            if (it.activity == TaskActivity.Idle && it.pending.isEmpty() && it.controller !in connections) {
                it.controller = null
            }
        }
    }
    @Synchronized fun unsubscribe(thread: BrokerThreadId, client: ClientConnectionId) { sessions[thread]?.observers?.remove(client) }
    @Synchronized fun disconnect(client: ClientConnectionId) {
        connections -= client
        sessions.values.forEach {
            it.observers -= client
            if (it.controller == client && it.activity == TaskActivity.Idle && it.pending.isEmpty()) it.controller = null
        }
    }
    @Synchronized fun reconcileIdle(thread: BrokerThreadId) {
        sessions[thread]?.let {
            if (it.activity is TaskActivity.ReconciliationRequired) {
                it.activity = TaskActivity.Idle
                it.pending.clear()
                if (it.controller !in connections) it.controller = null
            }
        }
    }
    @Synchronized fun requireReconciliation(thread: BrokerThreadId) {
        sessions[thread]?.let {
            it.activity = TaskActivity.ReconciliationRequired((it.activity as? TaskActivity.Active)?.turnId)
        }
    }
    @Synchronized fun upstreamLost(client: ClientConnectionId) {
        disconnect(client)
        sessions.values.filter { it.controller == client }.forEach {
            it.activity = TaskActivity.ReconciliationRequired((it.activity as? TaskActivity.Active)?.turnId)
            it.controller = null
        }
    }
    /** Only the matching terminal turn from a successful Codex history read can discharge uncertainty. */
    @Synchronized fun reconcile(thread: BrokerThreadId, turn: String, terminal: Boolean) {
        val current = sessions[thread] ?: return
        val uncertain = current.activity as? TaskActivity.ReconciliationRequired ?: return
        if (terminal && uncertain.turnId == turn) {
            current.activity = TaskActivity.Idle
            current.pending.clear()
        }
    }
    @Synchronized fun hasWork(client: ClientConnectionId) = sessions.values.any { it.controller == client && (it.activity != TaskActivity.Idle || it.pending.isNotEmpty()) }
    @Synchronized fun observers(thread: BrokerThreadId): Set<ClientConnectionId> = sessions[thread]?.observers?.toSet().orEmpty()
    @Synchronized fun controller(thread: BrokerThreadId): ClientConnectionId? = sessions[thread]?.controller
    @Synchronized fun views(): List<TaskSessionView> = sessions.values.map { TaskSessionView(it.thread, it.controller, it.observers.toSet(), it.activity, it.pending.size, it.lease) }
}
