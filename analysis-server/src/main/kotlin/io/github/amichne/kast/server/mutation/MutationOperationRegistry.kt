package io.github.amichne.kast.server.mutation

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.mutation.KastMutationExecutionTrace
import io.github.amichne.kast.api.contract.mutation.KastMutationFailure
import io.github.amichne.kast.api.contract.mutation.KastMutationIdempotencyKey
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationId
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSelector
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationSnapshot
import io.github.amichne.kast.api.contract.mutation.KastMutationOperationState
import io.github.amichne.kast.api.contract.mutation.KastMutationProgressStage
import io.github.amichne.kast.api.contract.mutation.KastMutationSubmissionReceipt
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutation
import io.github.amichne.kast.api.contract.mutation.KastSemanticMutationResult
import io.github.amichne.kast.api.contract.mutation.KastWorkspaceTaskId
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierRequest
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierResult
import io.github.amichne.kast.server.mutation.coordination.MutationFinishBarrierState
import io.github.amichne.kast.server.mutation.coordination.MutationFinishCoordinationToken
import io.github.amichne.kast.server.mutation.coordination.MutationPathScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable

internal class MutationOperationRegistry(
    private val scope: CoroutineScope,
    private val workspaceRoot: suspend () -> NormalizedPath,
    private val operationIdFactory: () -> KastMutationOperationId = KastMutationOperationId::random,
) : Closeable {
    private val lock = Any()
    private val operationsById = mutableMapOf<KastMutationOperationId, OperationEntry>()
    private val operationsByTaskKey = mutableMapOf<TaskIdempotencyKey, OperationEntry>()
    private val latestOperationsByKey = mutableMapOf<KastMutationIdempotencyKey, OperationEntry>()
    private var nextSequence = 0L
    private var finishBarrier: FinishBarrier? = null
    private val closedTaskTokens = mutableMapOf<KastWorkspaceTaskId, MutationFinishCoordinationToken>()
    private var closed = false

    fun submit(
        mutation: KastSemanticMutation,
        fingerprint: MutationFingerprint,
        execute: suspend (MutationProgressReporter) -> ExecutionOutcome,
    ): KastMutationSubmissionReceipt {
        val submission = synchronized(lock) {
            if (closed) {
                throw ConflictException("Mutation operation registry is shutting down")
            }
            val taskKey = TaskIdempotencyKey(mutation.workspaceTaskId, mutation.idempotencyKey)
            operationsByTaskKey[taskKey]?.let { existing ->
                if (existing.fingerprint != fingerprint) {
                    throw ConflictException(
                        message = "Mutation idempotency key is already bound to another request",
                        details = mapOf(
                            "idempotencyKey" to mutation.idempotencyKey.value,
                            "operationId" to existing.operationId.value,
                        ),
                    )
                }
                return@synchronized Submission.Existing(
                    KastMutationSubmissionReceipt(operation = existing.snapshot(), deduplicated = true),
                )
            }
            if (mutation.workspaceTaskId in closedTaskTokens) {
                throw WorkspaceTaskClosedException(mutation.workspaceTaskId.value)
            }
            finishBarrier?.let { barrier ->
                throw TaskFinishInProgressException(barrier.request.workspaceTaskId.value)
            }

            lateinit var entry: OperationEntry
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runOperation(entry, execute)
            }
            entry = OperationEntry(
                sequence = nextSequence++,
                operationId = uniqueOperationId(),
                mutation = mutation,
                fingerprint = fingerprint,
                job = job,
            )
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    synchronized(lock) {
                        entry.transitionToCancelledAfterStop()
                        admitReadyOperations()
                        completeFinishBarrierIfDrained()
                    }
                }
            }
            operationsById[entry.operationId] = entry
            operationsByTaskKey[taskKey] = entry
            latestOperationsByKey[mutation.idempotencyKey] = entry
            Submission.New(
                receipt = KastMutationSubmissionReceipt(operation = entry.snapshot(), deduplicated = false),
                job = job,
            )
        }

        if (submission is Submission.New) {
            submission.job.start()
        }
        return submission.receipt
    }

    fun status(selector: KastMutationOperationSelector): KastMutationOperationSnapshot =
        synchronized(lock) { requireEntry(selector).snapshot() }

    fun cancel(selector: KastMutationOperationSelector): KastMutationOperationSnapshot {
        val (snapshot, job) = synchronized(lock) {
            val entry = requireEntry(selector)
            if (!entry.state.isTerminal()) {
                entry.state = entry.state.withCancellationRequested()
            }
            entry.snapshot() to entry.job
        }
        job.cancel(CancellationException("Semantic mutation cancellation requested"))
        return snapshot
    }

    suspend fun acquireFinishBarrier(request: MutationFinishBarrierRequest): MutationFinishBarrierResult {
        val drained = synchronized(lock) {
            val existing = finishBarrier
            if (existing != null) {
                if (existing.request != request) {
                    throw ConflictException("Another finish barrier is already active")
                }
                return@synchronized existing.drained
            }
            if (request.workspaceTaskId in closedTaskTokens) {
                return MutationFinishBarrierResult(
                    workspaceTaskId = request.workspaceTaskId,
                    coordinationToken = request.coordinationToken,
                    state = MutationFinishBarrierState.COMPLETE,
                )
            }
            if (operationsById.values.any {
                    !it.state.isTerminal() && it.mutation.workspaceTaskId != request.workspaceTaskId
                }
            ) {
                throw ConflictException("A nonterminal mutation belongs to another workspace task")
            }
            FinishBarrier(request).also { barrier ->
                finishBarrier = barrier
                completeFinishBarrierIfDrained()
            }.drained
        }
        drained.await()
        return MutationFinishBarrierResult(
            workspaceTaskId = request.workspaceTaskId,
            coordinationToken = request.coordinationToken,
            state = MutationFinishBarrierState.DRAINED,
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
                workspaceTaskId = request.workspaceTaskId,
                coordinationToken = request.coordinationToken,
                state = if (reopened) MutationFinishBarrierState.REOPENED else MutationFinishBarrierState.ABSENT,
            )
        }
        if (barrier.request != request) {
            throw ConflictException("Finish barrier token does not name the active barrier")
        }
        finishBarrier = null
        barrier.drained.complete(Unit)
        MutationFinishBarrierResult(
            workspaceTaskId = request.workspaceTaskId,
            coordinationToken = request.coordinationToken,
            state = MutationFinishBarrierState.REOPENED,
        )
    }

    fun completeAfterFinish(request: MutationFinishBarrierRequest): MutationFinishBarrierResult =
        releaseFinishBarrier(request, completed = true)

    override fun close() {
        val jobs = synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            operationsById.values
                .filterNot { it.state.isTerminal() }
                .onEach { entry ->
                    entry.state = entry.state.withCancellationRequested()
                }
                .map(OperationEntry::job)
        }
        jobs.forEach { job ->
            job.cancel(CancellationException("Semantic mutation server is shutting down"))
        }
        runBlocking {
            jobs.joinAll()
        }
    }

    private suspend fun runOperation(
        entry: OperationEntry,
        execute: suspend (MutationProgressReporter) -> ExecutionOutcome,
    ) {
        try {
            val outcome = execute(reporterFor(entry))
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                val trace = entry.state.trace
                val cancellationRequested = entry.state.cancellationRequested
                entry.state = when (outcome) {
                    is ExecutionOutcome.Succeeded -> KastMutationOperationState.Completed(
                        result = outcome.result,
                        trace = trace,
                        cancellationRequested = cancellationRequested,
                    )

                    is ExecutionOutcome.Failed -> KastMutationOperationState.Failed(
                        failure = outcome.failure,
                        trace = trace,
                        cancellationRequested = cancellationRequested,
                    )
                }
                admitReadyOperations()
                completeFinishBarrierIfDrained()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            synchronized(lock) {
                entry.state = KastMutationOperationState.Failed(
                    failure = KastMutationFailure.Thrown(exception.toApiError(entry.operationId)),
                    trace = entry.state.trace,
                    cancellationRequested = entry.state.cancellationRequested,
                )
                admitReadyOperations()
                completeFinishBarrierIfDrained()
            }
        }
    }

    private fun reporterFor(entry: OperationEntry): MutationProgressReporter = object : MutationProgressReporter {
        override fun report(event: MutationProgressEvent) {
            synchronized(lock) {
                if (entry.state.isTerminal()) {
                    return@synchronized
                }
                entry.state = when (event) {
                    is MutationProgressEvent.StageEntered -> entry.state.entering(event.stage)
                    MutationProgressEvent.EditApplicationCompleted -> entry.state.editApplicationCompleted()
                }
            }
        }

        override suspend fun awaitPathAdmission(paths: Collection<String>) {
            if (paths.isEmpty()) {
                return
            }
            val scope = MutationPathScope.parse(workspaceRoot(), paths)
            val admission = synchronized(lock) {
                entry.resolvePathScope(scope)
                admitReadyOperations()
                entry.pathAdmission
            }
            admission.await()
        }
    }

    private fun admitReadyOperations() {
        operationsById.values
            .asSequence()
            .filterNot { it.state.isTerminal() || it.pathScope == null || it.pathAdmitted }
            .sortedBy(OperationEntry::sequence)
            .forEach { candidate ->
                val candidateScope = candidate.pathScope ?: return@forEach
                val blocked = operationsById.values.any { earlier ->
                    val earlierScope = earlier.pathScope
                    earlier.sequence < candidate.sequence &&
                        !earlier.state.isTerminal() &&
                        (earlierScope == null || earlierScope.overlaps(candidateScope))
                }
                if (!blocked) {
                    candidate.pathAdmitted = true
                    candidate.pathAdmission.complete(Unit)
                }
            }
    }

    private fun completeFinishBarrierIfDrained() {
        val barrier = finishBarrier ?: return
        if (operationsById.values.none { !it.state.isTerminal() }) {
            barrier.drained.complete(Unit)
        }
    }

    private fun releaseFinishBarrier(
        request: MutationFinishBarrierRequest,
        completed: Boolean,
    ): MutationFinishBarrierResult = synchronized(lock) {
        val barrier = finishBarrier
            ?: throw ConflictException("No finish barrier is active")
        if (barrier.request != request || !barrier.drained.isCompleted) {
            throw ConflictException("Finish barrier token does not name the drained barrier")
        }
        if (completed) {
            closedTaskTokens[request.workspaceTaskId] = request.coordinationToken
        }
        finishBarrier = null
        MutationFinishBarrierResult(
            workspaceTaskId = request.workspaceTaskId,
            coordinationToken = request.coordinationToken,
            state = if (completed) MutationFinishBarrierState.COMPLETE else MutationFinishBarrierState.REOPENED,
        )
    }

    private fun requireEntry(selector: KastMutationOperationSelector): OperationEntry = when (selector) {
        is KastMutationOperationSelector.ByOperationId -> operationsById[selector.operationId]
        is KastMutationOperationSelector.ByIdempotencyKey -> latestOperationsByKey[selector.idempotencyKey]
    } ?: throw NotFoundException(
        message = "Mutation operation was not found",
        details = when (selector) {
            is KastMutationOperationSelector.ByOperationId -> mapOf("operationId" to selector.operationId.value)
            is KastMutationOperationSelector.ByIdempotencyKey -> mapOf("idempotencyKey" to selector.idempotencyKey.value)
        },
    )

    private fun uniqueOperationId(): KastMutationOperationId {
        repeat(MAX_OPERATION_ID_ATTEMPTS) {
            val candidate = operationIdFactory()
            if (candidate !in operationsById) {
                return candidate
            }
        }
        error("Mutation operation ID factory produced repeated collisions")
    }

    internal sealed interface ExecutionOutcome {
        data class Succeeded(
            val result: KastSemanticMutationResult,
        ) : ExecutionOutcome

        data class Failed(
            val failure: KastMutationFailure,
        ) : ExecutionOutcome
    }

    private sealed interface Submission {
        val receipt: KastMutationSubmissionReceipt

        data class Existing(
            override val receipt: KastMutationSubmissionReceipt,
        ) : Submission

        data class New(
            override val receipt: KastMutationSubmissionReceipt,
            val job: Job,
        ) : Submission
    }

    private class OperationEntry(
        val sequence: Long,
        val operationId: KastMutationOperationId,
        val mutation: KastSemanticMutation,
        val fingerprint: MutationFingerprint,
        var state: KastMutationOperationState = KastMutationOperationState.Queued(),
        val job: Job,
    ) {
        var pathScope: MutationPathScope? = null
            private set
        var pathAdmitted: Boolean = false
        val pathAdmission = CompletableDeferred<Unit>()

        fun resolvePathScope(scope: MutationPathScope) {
            val existing = pathScope
            check(existing == null || existing == scope) {
                "Mutation operation path scope was resolved more than once"
            }
            pathScope = scope
        }

        fun snapshot(): KastMutationOperationSnapshot = KastMutationOperationSnapshot(
            operationId = operationId,
            idempotencyKey = mutation.idempotencyKey,
            mutationKind = mutation.kind,
            state = state,
        )

        fun transitionToCancelledAfterStop() {
            if (state.isTerminal()) {
                return
            }
            state = KastMutationOperationState.Cancelled(
                message = "Cancellation acknowledged after semantic mutation execution stopped.",
                trace = state.trace,
                cancellationRequested = true,
            )
        }
    }

    private class FinishBarrier(
        val request: MutationFinishBarrierRequest,
        val drained: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private data class TaskIdempotencyKey(
        val workspaceTaskId: KastWorkspaceTaskId,
        val idempotencyKey: KastMutationIdempotencyKey,
    )

    private companion object {
        const val MAX_OPERATION_ID_ATTEMPTS = 8
    }
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

private fun KastMutationOperationState.isTerminal(): Boolean =
    this is KastMutationOperationState.Completed ||
        this is KastMutationOperationState.Failed ||
        this is KastMutationOperationState.Cancelled

private fun KastMutationOperationState.withCancellationRequested(): KastMutationOperationState = when (this) {
    is KastMutationOperationState.Queued -> copy(cancellationRequested = true)
    is KastMutationOperationState.Applying -> copy(cancellationRequested = true)
    is KastMutationOperationState.Validating -> copy(cancellationRequested = true)
    is KastMutationOperationState.Completed,
    is KastMutationOperationState.Failed,
    is KastMutationOperationState.Cancelled,
    -> this
}

private fun KastMutationOperationState.entering(stage: KastMutationProgressStage): KastMutationOperationState {
    val nextTrace = trace.entering(stage)
    return if (stage <= KastMutationProgressStage.EDIT_APPLICATION) {
        KastMutationOperationState.Applying(
            stage = stage,
            trace = nextTrace,
            cancellationRequested = cancellationRequested,
        )
    } else {
        KastMutationOperationState.Validating(
            stage = stage,
            trace = nextTrace,
            cancellationRequested = cancellationRequested,
        )
    }
}

private fun KastMutationOperationState.editApplicationCompleted(): KastMutationOperationState {
    val nextTrace = trace.editApplicationCompleted()
    return when (this) {
        is KastMutationOperationState.Applying -> copy(trace = nextTrace)
        else -> error("Mutation edit completion was reported outside the applying state")
    }
}

private fun Throwable.toApiError(operationId: KastMutationOperationId): ApiErrorResponse = when (this) {
    is AnalysisException -> ApiErrorResponse(
        requestId = operationId.value,
        code = errorCode,
        message = message,
        retryable = retryable,
        details = details,
    )

    else -> ApiErrorResponse(
        requestId = operationId.value,
        code = "MUTATION_EXECUTION_FAILED",
        message = message ?: this::class.java.simpleName,
        retryable = false,
    )
}
