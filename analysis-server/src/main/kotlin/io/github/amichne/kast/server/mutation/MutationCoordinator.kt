package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.mutation.KastMutationExecutionResult
import io.github.amichne.kast.api.contract.mutation.KastMutationFailure
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutationResult
import io.github.amichne.kast.api.contract.mutation.KastWorkspaceTaskId
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierRequest
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierResult
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierState
import io.github.amichne.kast.server.mutation.coordination.MutationFinishCoordinationToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import java.io.Closeable

internal class MutationCoordinator(
    private val scope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val lane = Mutex()
    private val executions = mutableMapOf<TaskIdempotencyKey, ExecutionEntry>()
    private val workers = mutableSetOf<Job>()
    private var activeWorkerCount = 0
    private var finishBarrier: FinishBarrier? = null
    private val closedTaskTokens = mutableMapOf<KastWorkspaceTaskId, MutationFinishCoordinationToken>()
    private var closed = false

    suspend fun execute(
        taskId: KastWorkspaceTaskId,
        idempotencyKey: KastMutationIdempotencyKey,
        fingerprint: MutationFingerprint,
        block: suspend () -> ExecutionOutcome,
    ): KastMutationExecutionResult {
        val taskKey = TaskIdempotencyKey(taskId, idempotencyKey)
        val submission = synchronized(lock) {
            executions[taskKey]?.let { existing ->
                if (existing.fingerprint != fingerprint) {
                    throw ConflictException(
                        message = "Mutation idempotency key is already bound to another request",
                        details = mapOf("idempotencyKey" to idempotencyKey.value),
                    )
                }
                return@synchronized Submission(existing, deduplicated = true)
            }
            if (closed) throw ConflictException("Mutation coordinator is shutting down")
            if (taskId in closedTaskTokens) throw WorkspaceTaskClosedException(taskId.value)
            finishBarrier?.let { throw TaskFinishInProgressException(it.request.workspaceTaskId.value) }

            val entry = ExecutionEntry(fingerprint)
            executions[taskKey] = entry
            activeWorkerCount++
            val worker = scope.launch(start = CoroutineStart.LAZY) {
                val outcome = try {
                    lane.withLock { block() }
                } catch (exception: Throwable) {
                    entry.result.completeExceptionally(exception)
                    return@launch
                }
                entry.result.complete(outcome)
            }
            worker.invokeOnCompletion { synchronized(lock) { workerCompleted(worker) } }
            workers += worker
            worker.start()
            Submission(entry, deduplicated = false)
        }
        return submission.entry.result.await().toResult(submission.deduplicated)
    }

    suspend fun acquireFinishBarrier(request: MutationFinishBarrierRequest): MutationFinishBarrierResult {
        val drained = synchronized(lock) {
            finishBarrier?.let { existing ->
                if (existing.request != request) throw ConflictException("Another finish barrier is already active")
                return@synchronized existing.drained
            }
            if (request.workspaceTaskId in closedTaskTokens) {
                return MutationFinishBarrierResult(
                    request.workspaceTaskId,
                    request.coordinationToken,
                    MutationFinishBarrierState.COMPLETE,
                )
            }
            FinishBarrier(request).also {
                finishBarrier = it
                completeFinishBarrierIfDrained()
            }.drained
        }
        drained.await()
        return MutationFinishBarrierResult(
            request.workspaceTaskId,
            request.coordinationToken,
            MutationFinishBarrierState.DRAINED,
        )
    }

    fun reopenAfterFinish(request: MutationFinishBarrierRequest): MutationFinishBarrierResult =
        releaseFinishBarrier(request, completed = false)

    fun repairAfterInterruptedFinish(request: MutationFinishBarrierRequest): MutationFinishBarrierResult = synchronized(lock) {
        val barrier = finishBarrier
        if (barrier == null) {
            val closedToken = closedTaskTokens[request.workspaceTaskId]
            if (closedToken != null && closedToken != request.coordinationToken) {
                throw ConflictException("Finish barrier token does not name the closed task")
            }
            val reopened = closedTaskTokens.remove(request.workspaceTaskId, request.coordinationToken)
            return@synchronized MutationFinishBarrierResult(
                request.workspaceTaskId,
                request.coordinationToken,
                if (reopened) MutationFinishBarrierState.REOPENED else MutationFinishBarrierState.ABSENT,
            )
        }
        if (barrier.request != request) throw ConflictException("Finish barrier token does not name the active barrier")
        finishBarrier = null
        barrier.drained.complete(Unit)
        MutationFinishBarrierResult(request.workspaceTaskId, request.coordinationToken, MutationFinishBarrierState.REOPENED)
    }

    fun completeAfterFinish(request: MutationFinishBarrierRequest): MutationFinishBarrierResult =
        releaseFinishBarrier(request, completed = true)

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            workers.toList()
        }
        runBlocking { active.forEach { it.cancelAndJoin() } }
    }

    private fun workerCompleted(worker: Job) {
        workers -= worker
        activeWorkerCount--
        completeFinishBarrierIfDrained()
    }

    private fun completeFinishBarrierIfDrained() {
        if (activeWorkerCount == 0) finishBarrier?.drained?.complete(Unit)
    }

    private fun releaseFinishBarrier(
        request: MutationFinishBarrierRequest,
        completed: Boolean,
    ): MutationFinishBarrierResult = synchronized(lock) {
        val barrier = finishBarrier ?: throw ConflictException("No finish barrier is active")
        if (barrier.request != request || !barrier.drained.isCompleted) {
            throw ConflictException("Finish barrier token does not name the drained barrier")
        }
        if (completed) closedTaskTokens[request.workspaceTaskId] = request.coordinationToken
        finishBarrier = null
        MutationFinishBarrierResult(
            request.workspaceTaskId,
            request.coordinationToken,
            if (completed) MutationFinishBarrierState.COMPLETE else MutationFinishBarrierState.REOPENED,
        )
    }

    internal sealed interface ExecutionOutcome {
        data class Succeeded(val result: KastSemanticMutationResult) : ExecutionOutcome
        data class Failed(val failure: KastMutationFailure) : ExecutionOutcome
    }

    private fun ExecutionOutcome.toResult(deduplicated: Boolean): KastMutationExecutionResult = when (this) {
        is ExecutionOutcome.Succeeded -> KastMutationExecutionResult.Succeeded(result, deduplicated)
        is ExecutionOutcome.Failed -> KastMutationExecutionResult.Failed(failure, deduplicated)
    }

    private data class TaskIdempotencyKey(
        val taskId: KastWorkspaceTaskId,
        val idempotencyKey: KastMutationIdempotencyKey,
    )

    private class ExecutionEntry(
        val fingerprint: MutationFingerprint,
        val result: CompletableDeferred<ExecutionOutcome> = CompletableDeferred(),
    )

    private data class Submission(val entry: ExecutionEntry, val deduplicated: Boolean)
    private class FinishBarrier(
        val request: MutationFinishBarrierRequest,
        val drained: CompletableDeferred<Unit> = CompletableDeferred(),
    )
}

private class TaskFinishInProgressException(taskId: String) : AnalysisException(
    statusCode = 409,
    errorCode = "TASK_FINISH_IN_PROGRESS",
    message = "The shared workspace task is finishing; retry after it completes or reopens.",
    retryable = true,
    details = mapOf("workspaceTaskId" to taskId),
)

private class WorkspaceTaskClosedException(taskId: String) : AnalysisException(
    statusCode = 409,
    errorCode = "AGENT_TASK_CLOSED",
    message = "The shared workspace task is complete; begin a new task before mutating.",
    details = mapOf("workspaceTaskId" to taskId),
)
