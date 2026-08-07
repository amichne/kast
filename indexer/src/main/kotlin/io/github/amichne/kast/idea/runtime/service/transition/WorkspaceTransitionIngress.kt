package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.RuntimeProgressStage
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceTransitionRequest
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiter
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressAwaitFailure
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressAwaitOutcome
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressObservation
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressProbe
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitCompletion
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitCompletionProbe
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitLifecycle
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitLifecycleProbe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface WorkspaceTransitionRequester {
    suspend fun reconcile(request: WorkspaceTransitionRequest): PublishedWorkspaceGenerationManifest

    suspend fun <T> mutate(
        signal: WorkspaceSignal,
        detail: String,
        operation: suspend () -> T,
    ): T
}

internal fun routeWorkspaceSignal(
    lock: Any,
    signal: WorkspaceSignal,
    enqueue: (WorkspaceSignal) -> Unit,
    wake: (WorkspaceSignal) -> Unit,
) {
    routeWorkspaceRequest(
        lock = lock,
        request = WorkspaceTransitionRequest.Unkeyed(signal),
        enqueue = { observed -> enqueue(observed.signal) },
        wake = wake,
    )
}

internal fun routeWorkspaceRequest(
    lock: Any,
    request: WorkspaceTransitionRequest,
    enqueue: (WorkspaceTransitionRequest) -> Unit,
    wake: (WorkspaceSignal) -> Unit,
) {
    synchronized(lock) {
        enqueue(request)
        wake(request.signal)
    }
}

/**
 * Effect-boundary transition:
 * `(IdeaIndexSemanticAdmission, ProgressAwareFutureAwaiter) -> WorkspaceTransitionIngress`.
 *
 * Retains READY publication proof while exact covered requests join and all
 * other requests enqueue through the single transition publication lane.
 * Waiting consumes typed progress and deadline evidence; the ordinary RPC
 * request budget is not reused as an indexing deadline.
 */
internal class WorkspaceTransitionIngress(
    private val semanticAdmission: IdeaIndexSemanticAdmission,
    private val transitionAwaiter: ProgressAwareFutureAwaiter = ProgressAwareFutureAwaiter.standard(),
    private val indexingProgress: WorkspaceIndexingProgressProbe = WorkspaceIndexingProgressAuthority(),
) : WorkspaceTransitionRequester, AutoCloseable {
    private val lock = Any()
    private val waiters = linkedSetOf<TransitionWaiter>()
    private var observation: TransitionObservation = TransitionObservation.Unobserved
    private var binding: IngressBinding = IngressBinding.Unbound

    fun bind(request: (WorkspaceSignal) -> Unit) {
        bindRequest { transition -> request(transition.signal) }
    }

    fun bindRequest(request: (WorkspaceTransitionRequest) -> Unit) {
        synchronized(lock) {
            binding = when (binding) {
                IngressBinding.Unbound -> IngressBinding.Bound(request)
                is IngressBinding.Bound -> error("Workspace transition ingress is already bound")
                IngressBinding.Closed -> error("Workspace transition ingress is closed")
            }
        }
    }

    fun observe(snapshot: WorkspaceTransitionSnapshot) {
        val completions = synchronized(lock) {
            observation = TransitionObservation.Observed(snapshot)
            when (val completion = WorkspaceTransitionCompletion.derive(snapshot)) {
                is WorkspaceTransitionCompletion.Ready -> {
                    val published = PublishedWorkspaceGenerationState.Published(completion.manifest)
                    waiters.filter { waiter -> waiter.baseline != published }
                        .onEach(waiters::remove)
                        .map { waiter -> waiter to Result.success(completion.manifest) }
                }

                is WorkspaceTransitionCompletion.Blocked ->
                    waiters.toList().onEach(waiters::remove).map { waiter ->
                        waiter to Result.failure(
                            ConflictException(
                                message = "Workspace reconciliation is blocked: ${completion.blocker.detail}",
                                details = mapOf("phase" to completion.blocker.phase.name),
                            ),
                        )
                    }

                is WorkspaceTransitionCompletion.Invalid ->
                    waiters.toList().onEach(waiters::remove).map { waiter ->
                        waiter to Result.failure(
                            ConflictException(
                                message = "Workspace transition published an invalid completion state",
                                details = mapOf("lifecycle" to completion.lifecycle.name),
                            ),
                        )
                    }

                WorkspaceTransitionCompletion.InProgress -> emptyList()
            }
        }
        completions.forEach { (waiter, result) ->
            result.fold(waiter.result::complete, waiter.result::completeExceptionally)
        }
    }

    override suspend fun reconcile(
        request: WorkspaceTransitionRequest,
    ): PublishedWorkspaceGenerationManifest {
        val route = initialRoute(request)
        val waiter = when (route) {
            is WorkspaceTransitionRoute.Enqueue -> register(route.baseline)
            is WorkspaceTransitionRoute.Join -> register(route.baseline)
            is WorkspaceTransitionRoute.Rejected -> throw route.failure.toConflict()
        }
        if (route is WorkspaceTransitionRoute.Enqueue) request(request, waiter)
        return awaitStable(waiter)
    }

    override suspend fun <T> mutate(
        signal: WorkspaceSignal,
        detail: String,
        operation: suspend () -> T,
    ): T {
        val permit = try {
            semanticAdmission.beginMutation(detail)
        } catch (failure: IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionException) {
            throw mutationAdmissionConflict(failure)
        } catch (failure: Throwable) {
            throw failure
        }
        val waiter = try {
            registerAfter(permit.generation)
        } catch (failure: Throwable) {
            permit.close()
            throw failure
        }
        val result = try {
            operation()
        } catch (failure: Throwable) {
            permit.close()
            runCatching { request(WorkspaceTransitionRequest.Unkeyed(signal), waiter) }
            remove(waiter)
            throw failure
        }
        permit.close()
        request(WorkspaceTransitionRequest.Unkeyed(signal), waiter)
        awaitStable(waiter)
        return result
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (binding == IngressBinding.Closed) return
            binding = IngressBinding.Closed
            waiters.toList().also { waiters.clear() }
        }
        pending.forEach { waiter ->
            waiter.result.completeExceptionally(
                ConflictException("Workspace transition ingress closed before reconciliation completed"),
            )
        }
    }

    private fun initialRoute(request: WorkspaceTransitionRequest): WorkspaceTransitionRoute {
        val status = semanticAdmission.status()
        return synchronized(lock) {
            WorkspaceTransitionRoute.derive(status, observation, request)
        }
    }

    private fun registerAfter(baseline: PublishedWorkspaceGenerationManifest): TransitionWaiter {
        return register(PublishedWorkspaceGenerationState.Published(baseline))
    }

    private fun register(baseline: PublishedWorkspaceGenerationState): TransitionWaiter {
        val waiter = TransitionWaiter(baseline)
        synchronized(lock) {
            check(binding != IngressBinding.Closed) { "Workspace transition ingress is closed" }
            waiters += waiter
        }
        when (val current = semanticAdmission.status()) {
            is IdeaIndexSemanticAdmission.Status.Ready -> if (
                PublishedWorkspaceGenerationState.Published(current.generation) != baseline &&
                remove(waiter) == WaiterRemoval.Removed
            ) {
                waiter.result.complete(current.generation)
            }

            is IdeaIndexSemanticAdmission.Status.Pending,
            is IdeaIndexSemanticAdmission.Status.Failed,
            -> Unit
        }
        return waiter
    }

    private fun request(request: WorkspaceTransitionRequest, waiter: TransitionWaiter) {
        val dispatch = when (val current = synchronized(lock) { binding }) {
            is IngressBinding.Bound -> current.request
            IngressBinding.Unbound -> {
                remove(waiter)
                throw ConflictException("Workspace transition ingress is not attached to the indexer worker")
            }

            IngressBinding.Closed -> {
                remove(waiter)
                throw ConflictException("Workspace transition ingress closed before the request was routed")
            }
        }
        try {
            dispatch(request)
        } catch (failure: Throwable) {
            remove(waiter)
            throw failure
        }
    }

    private suspend fun awaitStable(initial: TransitionWaiter): PublishedWorkspaceGenerationManifest {
        var waiter = initial
        while (true) {
            val published = await(waiter)
            when (val current = semanticAdmission.status()) {
                is IdeaIndexSemanticAdmission.Status.Ready -> {
                    if (current.generation == published) return published
                    waiter = registerAfter(published)
                }

                is IdeaIndexSemanticAdmission.Status.Pending -> waiter = registerAfter(published)
                is IdeaIndexSemanticAdmission.Status.Failed -> throw ConflictException(
                    message = "Workspace semantic admission failed after reconciliation",
                    details = mapOf("detail" to current.detail),
                )
            }
        }
    }

    private suspend fun await(waiter: TransitionWaiter): PublishedWorkspaceGenerationManifest =
        withContext(Dispatchers.IO) {
            val outcome = transitionAwaiter.awaitCondition(
                stage = RuntimeProgressStage.SOURCE_INDEX,
                completion = RuntimeWaitCompletionProbe(waiter::completion),
                observation = RuntimeProgressProbe {
                    RuntimeProgressObservation.capture(
                        WorkspaceTransitionProgressObservation.derive(
                            transition = synchronized(lock) { observation },
                            indexing = indexingProgress.observe(),
                        ),
                    )
                },
                lifecycle = RuntimeWaitLifecycleProbe {
                    when (synchronized(lock) { binding }) {
                        IngressBinding.Unbound,
                        is IngressBinding.Bound,
                        -> RuntimeWaitLifecycle.Active

                        IngressBinding.Closed -> RuntimeWaitLifecycle.Disposed
                    }
                },
            )
            when (outcome) {
                is RuntimeProgressAwaitOutcome.Completed -> completedResult(waiter)
                is RuntimeProgressAwaitOutcome.Rejected -> {
                    remove(waiter)
                    throw transitionWaitConflict(outcome.failure)
                }
            }
        }

    private fun completedResult(waiter: TransitionWaiter): PublishedWorkspaceGenerationManifest = try {
        waiter.result.get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        throw failure
    }

    private fun remove(waiter: TransitionWaiter): WaiterRemoval = synchronized(lock) {
        if (waiters.remove(waiter)) WaiterRemoval.Removed else WaiterRemoval.AlreadyCompleted
    }

    private fun mutationAdmissionConflict(
        failure: IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionException,
    ): ConflictException {
        val details = when (failure) {
            is IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionUnavailableException -> mapOf(
                "admissionState" to when (failure.admissionStatus) {
                    is IdeaIndexSemanticAdmission.Status.Pending -> "PENDING"
                    is IdeaIndexSemanticAdmission.Status.Failed -> "FAILED"
                    is IdeaIndexSemanticAdmission.Status.Ready -> error("READY admission cannot be unavailable")
                },
            )

            is IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionInvalidatedException -> mapOf(
                "expectedAdmissionRevision" to failure.expectedRevision.toString(),
                "actualAdmissionRevision" to failure.actualRevision.toString(),
            )
        }
        return ConflictException(
            message = "Workspace changed before the mutation could begin",
            details = details,
        ).apply { initCause(failure) }
    }

    private fun transitionWaitConflict(failure: RuntimeProgressAwaitFailure): ConflictException {
        val reason = WorkspaceTransitionWaitFailureCode.derive(failure)
        return ConflictException(
            message = "Workspace reconciliation did not publish READY within the indexing wait policy",
            details = mapOf(
                "waitFailure" to reason.name,
                "stage" to failure.evidence.stage.name,
                "elapsedMillis" to failure.evidence.elapsed.toMillis().toString(),
                "noProgressMillis" to failure.evidence.noProgress.toMillis().toString(),
            ),
        ).apply {
            when (failure) {
                is RuntimeProgressAwaitFailure.FutureFailed -> initCause(failure.cause)
                is RuntimeProgressAwaitFailure.DeadlineExceeded,
                is RuntimeProgressAwaitFailure.ProjectDisposed,
                is RuntimeProgressAwaitFailure.Interrupted,
                is RuntimeProgressAwaitFailure.FutureCancelled,
                -> Unit
            }
        }
    }

    private class TransitionWaiter(
        val baseline: PublishedWorkspaceGenerationState,
        val result: CompletableFuture<PublishedWorkspaceGenerationManifest> = CompletableFuture(),
    ) {
        /**
         * Boundary transition: `CompletableFuture -> RuntimeWaitCompletion`.
         *
         * Confines the Java future's Boolean completion probe to the closed
         * wait protocol consumed by [ProgressAwareFutureAwaiter].
         */
        fun completion(): RuntimeWaitCompletion =
            if (result.isDone) RuntimeWaitCompletion.Completed else RuntimeWaitCompletion.Pending
    }

    private sealed interface IngressBinding {
        data object Unbound : IngressBinding

        data class Bound(val request: (WorkspaceTransitionRequest) -> Unit) : IngressBinding

        data object Closed : IngressBinding
    }

    private enum class WaiterRemoval {
        Removed,
        AlreadyCompleted,
    }

}
