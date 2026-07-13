package io.github.amichne.kast.server.mutation

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
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.ApiErrorResponse
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class MutationOperationRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val operationIdFactory: () -> KastMutationOperationId = KastMutationOperationId::random,
) {
    private val lock = Any()
    private val operationsById = mutableMapOf<KastMutationOperationId, OperationEntry>()
    private val operationsByKey = mutableMapOf<KastMutationIdempotencyKey, OperationEntry>()

    fun submit(
        mutation: KastSemanticMutation,
        fingerprint: MutationFingerprint,
        execute: suspend (MutationProgressReporter) -> ExecutionOutcome,
    ): KastMutationSubmissionReceipt {
        val existing = synchronized(lock) { operationsByKey[mutation.idempotencyKey] }
        if (existing != null) {
            if (existing.fingerprint != fingerprint) {
                throw ConflictException(
                    message = "Mutation idempotency key is already bound to another request",
                    details = mapOf(
                        "idempotencyKey" to mutation.idempotencyKey.value,
                        "operationId" to existing.operationId.value,
                    ),
                )
            }
            return synchronized(lock) {
                KastMutationSubmissionReceipt(operation = existing.snapshot(), deduplicated = true)
            }
        }

        val entry = synchronized(lock) {
            operationsByKey[mutation.idempotencyKey]?.let { raced ->
                if (raced.fingerprint != fingerprint) {
                    throw ConflictException(
                        message = "Mutation idempotency key is already bound to another request",
                        details = mapOf(
                            "idempotencyKey" to mutation.idempotencyKey.value,
                            "operationId" to raced.operationId.value,
                        ),
                    )
                }
                return KastMutationSubmissionReceipt(operation = raced.snapshot(), deduplicated = true)
            }
            OperationEntry(
                operationId = uniqueOperationId(),
                mutation = mutation,
                fingerprint = fingerprint,
            ).also { created ->
                operationsById[created.operationId] = created
                operationsByKey[mutation.idempotencyKey] = created
            }
        }

        val queuedReceipt = synchronized(lock) {
            KastMutationSubmissionReceipt(operation = entry.snapshot(), deduplicated = false)
        }
        val job = scope.launch {
            runOperation(entry, execute)
        }
        synchronized(lock) {
            entry.job = job
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                synchronized(lock) {
                    entry.transitionToCancelledAfterStop()
                }
            }
        }
        return queuedReceipt
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
        job?.cancel(CancellationException("Semantic mutation cancellation requested"))
        return snapshot
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
            }
        }
    }

    private fun reporterFor(entry: OperationEntry): MutationProgressReporter = MutationProgressReporter { event ->
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

    private fun requireEntry(selector: KastMutationOperationSelector): OperationEntry = when (selector) {
        is KastMutationOperationSelector.ByOperationId -> operationsById[selector.operationId]
        is KastMutationOperationSelector.ByIdempotencyKey -> operationsByKey[selector.idempotencyKey]
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

    private class OperationEntry(
        val operationId: KastMutationOperationId,
        val mutation: KastSemanticMutation,
        val fingerprint: MutationFingerprint,
        var state: KastMutationOperationState = KastMutationOperationState.Queued(),
        var job: Job? = null,
    ) {
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

    private companion object {
        const val MAX_OPERATION_ID_ATTEMPTS = 8
    }
}

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
