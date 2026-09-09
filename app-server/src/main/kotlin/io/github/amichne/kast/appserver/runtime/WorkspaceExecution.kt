package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal enum class WorkspaceExecutionPolicyFailure { QUEUED_LIMIT_REJECTED, WAIT_LIMIT_REJECTED, INTERACTION_LIMIT_REJECTED }

/** Queue allowance is part of one aggregate interaction allowance, never a renewed execution budget. */
internal class WorkspaceExecutionPolicy private constructor(
    val maximumQueued: Int,
    val queueWait: ElapsedTimeLimitMillis,
    val interaction: ElapsedTimeLimitMillis,
) {
    companion object {
        val Default = WorkspaceExecutionPolicy(BrokerOperationalLimits.defaultWorkspaceQueued, BrokerOperationalLimits.workspaceQueueWait, BrokerOperationalLimits.workspaceInteraction)

        fun admit(maximumQueued: Int, queueWaitMillis: Long, interactionMillis: Long): Refinement<WorkspaceExecutionPolicy, WorkspaceExecutionPolicyFailure> {
            if (maximumQueued !in 0..BrokerOperationalLimits.maximumWorkspaceQueued) return Refinement.Rejected(WorkspaceExecutionPolicyFailure.QUEUED_LIMIT_REJECTED)
            val queueWait = when (val admitted = ElapsedTimeLimitMillis.parse(queueWaitMillis)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(WorkspaceExecutionPolicyFailure.WAIT_LIMIT_REJECTED)
            }
            val interaction = when (val admitted = ElapsedTimeLimitMillis.parse(interactionMillis)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(WorkspaceExecutionPolicyFailure.INTERACTION_LIMIT_REJECTED)
            }
            if (queueWait.value > interaction.value) return Refinement.Rejected(WorkspaceExecutionPolicyFailure.WAIT_LIMIT_REJECTED)
            return Refinement.Refined(WorkspaceExecutionPolicy(maximumQueued, queueWait, interaction))
        }
    }
}

internal data class WorkspaceExecutionIdentity(
    val workspace: BrokerWorkspaceId,
    val connection: ClientConnectionId,
    val thread: BrokerThreadId,
    val turn: BrokerTurnId,
    val call: BrokerCallId,
)

internal enum class WorkspaceExecutionFailure(val certainty: InvocationCertainty) {
    WORKSPACE_QUEUE_CAPACITY_EXCEEDED(InvocationCertainty.KNOWN),
    WORKSPACE_QUEUE_TIMED_OUT(InvocationCertainty.KNOWN),
    CANCELLED_BEFORE_EXECUTION(InvocationCertainty.KNOWN),
    WORKSPACE_RECOVERY_REQUIRED(InvocationCertainty.KNOWN),
    WORKSPACE_INTERACTION_TIMED_OUT(InvocationCertainty.UNCERTAIN),
    WORKSPACE_OUTCOME_UNCERTAIN(InvocationCertainty.UNCERTAIN),
}

internal sealed interface WorkspaceExecutionResult {
    data class Completed(val routing: ProtocolRouting) : WorkspaceExecutionResult
    data class Rejected(val failure: WorkspaceExecutionFailure) : WorkspaceExecutionResult
}

internal enum class WorkspaceExecutionStage { ADMISSION, QUEUE, EXECUTION }
internal enum class WorkspaceExecutionOutcome { ACCEPTED, STARTED, COMPLETED, REJECTED, CANCELLED, TIMED_OUT, UNCERTAIN }
internal data class WorkspaceExecutionEvent(
    val request: WorkspaceExecutionIdentity,
    val stage: WorkspaceExecutionStage,
    val outcome: WorkspaceExecutionOutcome,
    val elapsed: Duration,
    val queued: Duration,
    val interactionLimit: ElapsedTimeLimitMillis,
    val failure: WorkspaceExecutionFailure? = null,
)

/** One semantic permit and a bounded waiting set per workspace. Locks only change in-memory ownership. */
internal class WorkspaceExecution(
    private val scope: CoroutineScope,
    private val policy: WorkspaceExecutionPolicy,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private enum class RequestPhase { QUEUED, GRANTED, EXECUTING, RETIRED }
    private class Request(
        val identity: WorkspaceExecutionIdentity,
        val interactionLimit: ElapsedTimeLimitMillis,
        timeSource: TimeSource,
    ) {
        val enqueuedAt: TimeMark = timeSource.markNow()
        val permission = CompletableDeferred<Unit>()
        val result = CompletableDeferred<WorkspaceExecutionResult>()
        var phase = RequestPhase.QUEUED
        var queued = Duration.ZERO
    }
    private data class Execution(val request: Request, val job: Job)
    private sealed interface Lane {
        data object Idle : Lane
        data class Busy(val active: Execution, val pending: ArrayDeque<Execution>) : Lane
        data class RecoveryRequired(val request: WorkspaceExecutionIdentity) : Lane
    }
    private val lanes = linkedMapOf<BrokerWorkspaceId, Lane>()
    private val events = ArrayDeque<WorkspaceExecutionEvent>()

    fun submit(identity: WorkspaceExecutionIdentity, interactionLimit: ElapsedTimeLimitMillis = policy.interaction, operation: suspend () -> ProtocolRouting): Deferred<WorkspaceExecutionResult> {
        val request = Request(
            identity,
            if (interactionLimit.value < policy.interaction.value) interactionLimit else policy.interaction,
            timeSource,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) { perform(request, operation) }
        val execution = Execution(request, job)
        val accepted = synchronized(this) {
            when (val lane = lanes[identity.workspace] ?: Lane.Idle) {
                Lane.Idle -> {
                    lanes[identity.workspace] = Lane.Busy(execution, ArrayDeque())
                    request.phase = RequestPhase.GRANTED
                    request.permission.complete(Unit)
                    publish(request, WorkspaceExecutionStage.ADMISSION, WorkspaceExecutionOutcome.ACCEPTED)
                    true
                }
                is Lane.Busy -> if (lane.pending.size >= policy.maximumQueued) {
                    rejectAdmission(request, WorkspaceExecutionFailure.WORKSPACE_QUEUE_CAPACITY_EXCEEDED)
                    false
                } else {
                    lane.pending.addLast(execution)
                    publish(request, WorkspaceExecutionStage.QUEUE, WorkspaceExecutionOutcome.ACCEPTED)
                    true
                }
                is Lane.RecoveryRequired -> {
                    rejectAdmission(request, WorkspaceExecutionFailure.WORKSPACE_RECOVERY_REQUIRED)
                    false
                }
            }
        }
        if (accepted) {
            // Completion also retires a lazy coroutine cancelled before its body gets to run.
            job.invokeOnCompletion { finish(request, interrupted(request)) }
            job.start()
        } else job.cancel()
        return request.result
    }

    /** Caller supplies a workspace and turn only after validating the current controller's authority. */
    fun cancel(workspace: BrokerWorkspaceId, thread: BrokerThreadId, turn: BrokerTurnId) {
        val matching = synchronized(this) {
            when (val lane = lanes[workspace]) {
                is Lane.Busy -> (listOf(lane.active) + lane.pending).filter {
                    it.request.identity.thread == thread && it.request.identity.turn == turn
                }
                Lane.Idle, is Lane.RecoveryRequired, null -> emptyList()
            }
        }
        matching.forEach { it.job.cancel() }
    }

    private suspend fun perform(request: Request, operation: suspend () -> ProtocolRouting) {
        try {
            val queueRemaining = minOf(remaining(request, policy.queueWait), remaining(request, request.interactionLimit))
            if (queueRemaining <= 0) {
                finish(request, WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_QUEUE_TIMED_OUT)); return
            }
            try {
                withTimeout(queueRemaining) { request.permission.await() }
            } catch (_: TimeoutCancellationException) {
                finish(request, WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_QUEUE_TIMED_OUT)); return
            }
            currentCoroutineContext().ensureActive()
            val allowance = remaining(request, request.interactionLimit)
            if (allowance <= 0) {
                finish(request, WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_QUEUE_TIMED_OUT)); return
            }
            if (!begin(request)) return
            val routing = try {
                withTimeout(allowance) {
                    operation().also { currentCoroutineContext().ensureActive() }
                }
            } catch (_: TimeoutCancellationException) {
                finish(request, WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_INTERACTION_TIMED_OUT)); return
            }
            finish(request, WorkspaceExecutionResult.Completed(routing))
        } catch (_: CancellationException) {
            finish(request, interrupted(request))
        } catch (_: Exception) {
            finish(request, interrupted(request))
        }
    }

    @Synchronized private fun begin(request: Request): Boolean {
        if (request.phase != RequestPhase.GRANTED) return false
        request.phase = RequestPhase.EXECUTING
        request.queued = request.enqueuedAt.elapsedNow()
        publish(request, WorkspaceExecutionStage.EXECUTION, WorkspaceExecutionOutcome.STARTED)
        return true
    }

    @Synchronized private fun interrupted(request: Request): WorkspaceExecutionResult.Rejected = WorkspaceExecutionResult.Rejected(
        if (request.phase == RequestPhase.EXECUTING) WorkspaceExecutionFailure.WORKSPACE_OUTCOME_UNCERTAIN
        else WorkspaceExecutionFailure.CANCELLED_BEFORE_EXECUTION,
    )

    @Synchronized private fun finish(request: Request, result: WorkspaceExecutionResult) {
        if (request.phase == RequestPhase.RETIRED) return
        val executing = request.phase == RequestPhase.EXECUTING
        if (!executing) request.queued = request.enqueuedAt.elapsedNow()
        val uncertain = when (result) {
            is WorkspaceExecutionResult.Completed -> result.routing !is ProtocolRouting.ReplyUpstream || result.routing.certainty == InvocationCertainty.UNCERTAIN
            is WorkspaceExecutionResult.Rejected -> result.failure.certainty == InvocationCertainty.UNCERTAIN
        }
        request.phase = RequestPhase.RETIRED
        val failure = (result as? WorkspaceExecutionResult.Rejected)?.failure
        publish(request, if (executing) WorkspaceExecutionStage.EXECUTION else WorkspaceExecutionStage.QUEUE, when {
            failure == WorkspaceExecutionFailure.WORKSPACE_INTERACTION_TIMED_OUT || failure == WorkspaceExecutionFailure.WORKSPACE_QUEUE_TIMED_OUT -> WorkspaceExecutionOutcome.TIMED_OUT
            uncertain -> WorkspaceExecutionOutcome.UNCERTAIN
            failure == WorkspaceExecutionFailure.CANCELLED_BEFORE_EXECUTION -> WorkspaceExecutionOutcome.CANCELLED
            failure != null -> WorkspaceExecutionOutcome.REJECTED
            else -> WorkspaceExecutionOutcome.COMPLETED
        }, failure)
        request.result.complete(result)
        val lane = lanes[request.identity.workspace] as? Lane.Busy ?: return
        if (lane.active.request !== request) {
            lane.pending.removeAll { it.request === request }
            return
        }
        if (uncertain) {
            lanes[request.identity.workspace] = Lane.RecoveryRequired(request.identity)
            lane.pending.forEach { waiting ->
                rejectAdmission(waiting.request, WorkspaceExecutionFailure.WORKSPACE_RECOVERY_REQUIRED)
                waiting.job.cancel()
            }
        } else if (lane.pending.isEmpty()) {
            lanes[request.identity.workspace] = Lane.Idle
        } else {
            val next = lane.pending.removeFirst()
            lanes[request.identity.workspace] = Lane.Busy(next, lane.pending)
            next.request.phase = RequestPhase.GRANTED
            next.request.permission.complete(Unit)
        }
    }

    private fun rejectAdmission(request: Request, failure: WorkspaceExecutionFailure) {
        request.queued = request.enqueuedAt.elapsedNow()
        request.phase = RequestPhase.RETIRED
        publish(request, WorkspaceExecutionStage.ADMISSION, WorkspaceExecutionOutcome.REJECTED, failure)
        request.result.complete(WorkspaceExecutionResult.Rejected(failure))
    }

    private fun remaining(request: Request, limit: ElapsedTimeLimitMillis): Long =
        (limit.value - request.enqueuedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)

    private fun publish(request: Request, stage: WorkspaceExecutionStage, outcome: WorkspaceExecutionOutcome, failure: WorkspaceExecutionFailure? = null) {
        if (events.size == BrokerOperationalLimits.maximumWorkspaceEvents) events.removeFirst()
        events.addLast(WorkspaceExecutionEvent(request.identity, stage, outcome, request.enqueuedAt.elapsedNow(), request.queued, request.interactionLimit, failure))
    }

    @Synchronized fun snapshot(): JsonObject = buildJsonObject {
        put("maximumQueuedPerWorkspace", policy.maximumQueued)
        put("queueWaitMillis", policy.queueWait.value)
        put("interactionLimitMillis", policy.interaction.value)
        put("lanes", buildJsonArray {
            lanes.forEach { (workspace, lane) -> add(buildJsonObject {
                put("workspaceId", workspace.value)
                put("state", when (lane) { Lane.Idle -> "idle"; is Lane.Busy -> "busy"; is Lane.RecoveryRequired -> "recovery_required" })
                put("queued", if (lane is Lane.Busy) lane.pending.size else 0)
            }) }
        })
        put("events", buildJsonArray {
            events.forEach { event -> add(buildJsonObject {
                put("workspaceId", event.request.workspace.value)
                put("connectionId", event.request.connection.value)
                put("threadId", event.request.thread.value)
                put("turnId", event.request.turn.value)
                put("callId", event.request.call.value)
                put("stage", event.stage.name.lowercase())
                put("outcome", event.outcome.name.lowercase())
                put("elapsedMillis", event.elapsed.inWholeMilliseconds)
                put("queueAgeMillis", event.queued.inWholeMilliseconds)
                put("interactionLimitMillis", event.interactionLimit.value)
                put("remainingMillis", (event.interactionLimit.value - event.elapsed.inWholeMilliseconds).coerceAtLeast(0))
                event.failure?.let { put("failure", it.name); put("certainty", it.certainty.name.lowercase()) }
            }) }
        })
    }
}
