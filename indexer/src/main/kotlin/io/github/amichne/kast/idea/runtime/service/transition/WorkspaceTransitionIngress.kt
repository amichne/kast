package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.RuntimeProgressStage
import io.github.amichne.kast.indexer.gradle.settlement.ProgressAwareFutureAwaiter
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressAwaitOutcome
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressObservation
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeProgressProbe
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitCompletion
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitCompletionProbe
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitLifecycle
import io.github.amichne.kast.indexer.gradle.settlement.RuntimeWaitLifecycleProbe
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGeneration
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionRequest
import io.github.amichne.kast.workspace.contract.WorkspaceTransitionSnapshot
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceMutationTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionFailure
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionOutcome
import io.github.amichne.kast.workspace.spi.WorkspaceTransitionPort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * `(IdeaIndexSemanticAdmission, WorkspaceTransitionAdmissionObservation,
 * ProgressAwareFutureAwaiter) -> WorkspaceTransitionPort`.
 *
 * Retains READY publication proof while exact covered requests join and all other requests enqueue
 * through the single transition publication lane. Expected admission, lifecycle, dispatch, and wait
 * failures remain finite [WorkspaceTransitionFailure] data until an outer transport boundary chooses
 * a protocol representation.
 */
internal class WorkspaceTransitionIngress(
    private val semanticAdmission: IdeaIndexSemanticAdmission,
    private val transitionAwaiter: ProgressAwareFutureAwaiter = ProgressAwareFutureAwaiter.standard(),
    private val indexingProgress: WorkspaceIndexingProgressProbe = WorkspaceIndexingProgressAuthority(),
    private val admissionObservation: WorkspaceTransitionAdmissionObservation =
        WorkspaceTransitionAdmissionObservation(semanticAdmission::status),
) : WorkspaceTransitionPort, AutoCloseable {
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
                    val published = PublishedWorkspaceGenerationState.Published(completion.publication)
                    waiters.filter { waiter -> waiter.baseline != published }
                        .onEach(waiters::remove)
                        .map { waiter -> waiter to WaiterResolution.Published(completion.publication) }
                }

                is WorkspaceTransitionCompletion.Blocked ->
                    rejectAll(WorkspaceTransitionFailure.Blocked(completion.blocker))

                is WorkspaceTransitionCompletion.Invalid ->
                    rejectAll(WorkspaceTransitionFailure.InvalidCompletion(completion.lifecycle))

                WorkspaceTransitionCompletion.InProgress -> emptyList()
            }
        }
        completions.forEach { (waiter, resolution) -> waiter.result.complete(resolution) }
    }

    override suspend fun reconcile(
        request: WorkspaceTransitionRequest,
    ): WorkspaceTransitionOutcome = when (val route = initialRoute(request)) {
        is WorkspaceTransitionRoute.Enqueue -> when (val dispatch = dispatch(request)) {
            WorkspaceTransitionDispatch.Dispatched ->
                awaitStable(register(WorkspaceTransitionRoute.Join.Awaiting(route.baseline)))

            is WorkspaceTransitionDispatch.Rejected -> WorkspaceTransitionOutcome.Rejected(dispatch.failure)
        }

        is WorkspaceTransitionRoute.Join.Awaiting -> awaitStable(register(route))
        is WorkspaceTransitionRoute.Join.Published ->
            WorkspaceTransitionOutcome.Published(route.publication)

        is WorkspaceTransitionRoute.Rejected ->
            WorkspaceTransitionOutcome.Rejected(route.failure)
    }

    override suspend fun <Value> mutate(
        signal: WorkspaceSignal,
        detail: String,
        operation: suspend () -> Value,
    ): WorkspaceMutationTransitionOutcome<Value> {
        val permit = try {
            semanticAdmission.beginMutation(detail)
        } catch (failure: IdeaIndexSemanticAdmission.WorkspaceMutationAdmissionException) {
            return WorkspaceMutationTransitionOutcome.Rejected(failure.toTransitionFailure())
        }
        val waiter = registerAfter(permit.generation)
        waiter.resolutionOrNull()?.let { resolution ->
            permit.close()
            return WorkspaceMutationTransitionOutcome.Rejected(
                WorkspaceMutationTransitionFailure.ReconciliationRejected(resolution.failure()),
            )
        }
        val value = try {
            operation()
        } catch (failure: Throwable) {
            permit.close()
            runCatching { dispatch(WorkspaceTransitionRequest.Unkeyed(signal), waiter) }
            remove(waiter)
            throw failure
        }
        permit.close()
        when (val dispatch = dispatch(WorkspaceTransitionRequest.Unkeyed(signal), waiter)) {
            WorkspaceTransitionDispatch.Dispatched -> Unit
            is WorkspaceTransitionDispatch.Rejected -> return WorkspaceMutationTransitionOutcome.Rejected(
                WorkspaceMutationTransitionFailure.ReconciliationRejected(dispatch.failure),
            )
        }
        return when (val outcome = awaitStable(waiter)) {
            is WorkspaceTransitionOutcome.Published ->
                WorkspaceMutationTransitionOutcome.Completed(value, outcome.publication)

            is WorkspaceTransitionOutcome.Rejected ->
                WorkspaceMutationTransitionOutcome.Rejected(
                    WorkspaceMutationTransitionFailure.ReconciliationRejected(outcome.failure),
                )
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (binding == IngressBinding.Closed) return
            binding = IngressBinding.Closed
            waiters.toList().also { waiters.clear() }
        }
        pending.forEach { waiter ->
            waiter.result.complete(WaiterResolution.Rejected(WorkspaceTransitionFailure.Closed))
        }
    }

    private fun rejectAll(
        failure: WorkspaceTransitionFailure,
    ): List<Pair<TransitionWaiter, WaiterResolution>> =
        waiters.toList().onEach(waiters::remove).map { waiter ->
            waiter to WaiterResolution.Rejected(failure)
        }

    private fun initialRoute(request: WorkspaceTransitionRequest): WorkspaceTransitionRoute =
        synchronized(lock) {
            WorkspaceTransitionRoute.derive(admissionObservation.status(), observation, request)
        }

    private fun registerAfter(baseline: PublishedWorkspaceGeneration): TransitionWaiter =
        register(PublishedWorkspaceGenerationState.Published(baseline))

    private fun register(join: WorkspaceTransitionRoute.Join.Awaiting): TransitionWaiter {
        val waiter = TransitionWaiter(join.baseline)
        synchronized(lock) {
            if (binding == IngressBinding.Closed) {
                waiter.result.complete(WaiterResolution.Rejected(WorkspaceTransitionFailure.Closed))
            } else {
                when (val registration = WorkspaceTransitionJoinRegistration.derive(join, observation)) {
                    WorkspaceTransitionJoinRegistration.Awaiting -> waiters += waiter
                    is WorkspaceTransitionJoinRegistration.Published ->
                        waiter.result.complete(WaiterResolution.Published(registration.publication))

                    is WorkspaceTransitionJoinRegistration.Blocked ->
                        waiter.result.complete(
                            WaiterResolution.Rejected(WorkspaceTransitionFailure.Blocked(registration.blocker)),
                        )

                    is WorkspaceTransitionJoinRegistration.Invalid ->
                        waiter.result.complete(
                            WaiterResolution.Rejected(
                                WorkspaceTransitionFailure.InvalidCompletion(registration.lifecycle),
                            ),
                        )
                }
            }
        }
        reconcileReadyAdmission(waiter)
        return waiter
    }

    private fun register(baseline: PublishedWorkspaceGenerationState): TransitionWaiter {
        val waiter = TransitionWaiter(baseline)
        synchronized(lock) {
            if (binding == IngressBinding.Closed) {
                waiter.result.complete(WaiterResolution.Rejected(WorkspaceTransitionFailure.Closed))
            } else {
                waiters += waiter
            }
        }
        reconcileReadyAdmission(waiter)
        return waiter
    }

    private fun reconcileReadyAdmission(waiter: TransitionWaiter) {
        when (val current = admissionObservation.status()) {
            is IdeaIndexSemanticAdmission.Status.Ready -> {
                val publication = current.generation
                if (
                    PublishedWorkspaceGenerationState.Published(publication) != waiter.baseline &&
                    remove(waiter) == WaiterRemoval.Removed
                ) {
                    waiter.result.complete(WaiterResolution.Published(publication))
                }
            }

            is IdeaIndexSemanticAdmission.Status.Pending,
            is IdeaIndexSemanticAdmission.Status.Failed,
                -> Unit
        }
    }

    private fun dispatch(
        request: WorkspaceTransitionRequest,
        waiter: TransitionWaiter,
    ): WorkspaceTransitionDispatch = try {
        dispatch(request).also { outcome ->
            if (outcome is WorkspaceTransitionDispatch.Rejected) remove(waiter)
        }
    } catch (failure: Throwable) {
        remove(waiter)
        throw failure
    }

    private fun dispatch(request: WorkspaceTransitionRequest): WorkspaceTransitionDispatch {
        val boundRequest = when (val current = synchronized(lock) { binding }) {
            is IngressBinding.Bound -> current.request
            IngressBinding.Unbound -> return WorkspaceTransitionDispatch.Rejected(
                WorkspaceTransitionFailure.NotAttached,
            )
            IngressBinding.Closed -> return WorkspaceTransitionDispatch.Rejected(WorkspaceTransitionFailure.Closed)
        }
        boundRequest(request)
        return WorkspaceTransitionDispatch.Dispatched
    }

    private suspend fun awaitStable(initial: TransitionWaiter): WorkspaceTransitionOutcome {
        var waiter = initial
        while (true) {
            when (val resolution = await(waiter)) {
                is WaiterResolution.Rejected ->
                    return WorkspaceTransitionOutcome.Rejected(resolution.failure)

                is WaiterResolution.Published -> when (val current = admissionObservation.status()) {
                    is IdeaIndexSemanticAdmission.Status.Ready -> {
                        val admitted = current.generation
                        if (admitted == resolution.publication) {
                            return WorkspaceTransitionOutcome.Published(resolution.publication)
                        }
                        waiter = registerAfter(resolution.publication)
                    }

                    is IdeaIndexSemanticAdmission.Status.Pending ->
                        waiter = registerAfter(resolution.publication)

                    is IdeaIndexSemanticAdmission.Status.Failed ->
                        return WorkspaceTransitionOutcome.Rejected(
                            WorkspaceTransitionFailure.SemanticAdmissionFailed(current.detail),
                        )
                }
            }
        }
    }

    private suspend fun await(waiter: TransitionWaiter): WaiterResolution =
        withContext(Dispatchers.IO) {
            when (
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
            ) {
                is RuntimeProgressAwaitOutcome.Completed -> completedResult(waiter)
                is RuntimeProgressAwaitOutcome.Rejected -> {
                    remove(waiter)
                    WaiterResolution.Rejected(outcome.failure.toTransitionFailure())
                }
            }
        }

    private fun completedResult(waiter: TransitionWaiter): WaiterResolution = try {
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

    private class TransitionWaiter(
        val baseline: PublishedWorkspaceGenerationState,
        val result: CompletableFuture<WaiterResolution> = CompletableFuture(),
    ) {
        fun completion(): RuntimeWaitCompletion =
            if (result.isDone) RuntimeWaitCompletion.Completed else RuntimeWaitCompletion.Pending

        fun resolutionOrNull(): WaiterResolution? = result.getNow(null)
    }

    private sealed interface WaiterResolution {
        data class Published(
            val publication: PublishedWorkspaceGeneration,
        ) : WaiterResolution

        data class Rejected(
            val failure: WorkspaceTransitionFailure,
        ) : WaiterResolution

        fun failure(): WorkspaceTransitionFailure = when (this) {
            is Published -> error("Published transition resolution cannot be represented as failure")
            is Rejected -> failure
        }
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
