package io.github.amichne.kast.idea

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.idea.transition.WorkspaceLifecycle
import io.github.amichne.kast.idea.transition.WorkspaceSignal
import io.github.amichne.kast.idea.transition.WorkspaceTransitionSnapshot
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationManifest
import io.github.amichne.kast.indexstore.snapshot.PublishedWorkspaceGenerationState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface WorkspaceTransitionRequester {
    suspend fun reconcile(signal: WorkspaceSignal): PublishedWorkspaceGenerationManifest

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
    synchronized(lock) {
        enqueue(signal)
        wake(signal)
    }
}

internal class WorkspaceTransitionIngress(
    private val semanticAdmission: IdeaIndexSemanticAdmission,
    private val waitTimeoutMillis: Long,
) : WorkspaceTransitionRequester, AutoCloseable {
    private val lock = Any()
    private val waiters = linkedSetOf<TransitionWaiter>()
    private var requestTransition: ((WorkspaceSignal) -> Unit)? = null
    private var closed = false

    init {
        require(waitTimeoutMillis > 0) { "Workspace transition wait timeout must be positive" }
    }

    fun bind(request: (WorkspaceSignal) -> Unit) {
        synchronized(lock) {
            check(!closed) { "Workspace transition ingress is closed" }
            check(requestTransition == null) { "Workspace transition ingress is already bound" }
            requestTransition = request
        }
    }

    fun observe(snapshot: WorkspaceTransitionSnapshot) {
        val completions = synchronized(lock) {
            when {
                snapshot.lifecycle == WorkspaceLifecycle.Ready &&
                    snapshot.published is PublishedWorkspaceGenerationState.Published -> {
                    val manifest = snapshot.published.manifest
                    waiters.filter { waiter -> waiter.baseline != manifest }
                        .onEach(waiters::remove)
                        .map { waiter -> waiter to Result.success(manifest) }
                }

                snapshot.lifecycle == WorkspaceLifecycle.Blocked && snapshot.blocker != null ->
                    waiters.toList().onEach(waiters::remove).map { waiter ->
                        waiter to Result.failure(
                            ConflictException(
                                message = "Workspace reconciliation is blocked: ${snapshot.blocker.detail}",
                                details = mapOf("phase" to snapshot.blocker.phase.name),
                            ),
                        )
                    }

                else -> emptyList()
            }
        }
        completions.forEach { (waiter, result) ->
            result.fold(waiter.result::complete, waiter.result::completeExceptionally)
        }
    }

    override suspend fun reconcile(signal: WorkspaceSignal): PublishedWorkspaceGenerationManifest {
        val waiter = registerInitialWaiter()
        request(signal, waiter)
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
            runCatching { request(signal, waiter) }
            remove(waiter)
            throw failure
        }
        permit.close()
        request(signal, waiter)
        awaitStable(waiter)
        return result
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            requestTransition = null
            waiters.toList().also { waiters.clear() }
        }
        pending.forEach { waiter ->
            waiter.result.completeExceptionally(
                ConflictException("Workspace transition ingress closed before reconciliation completed"),
            )
        }
    }

    private fun registerInitialWaiter(): TransitionWaiter {
        val ready = semanticAdmission.status() as? IdeaIndexSemanticAdmission.Status.Ready
            ?: throw ConflictException("Workspace transition request requires READY semantic admission")
        return registerAfter(ready.generation)
    }

    private fun registerAfter(baseline: PublishedWorkspaceGenerationManifest): TransitionWaiter {
        val waiter = TransitionWaiter(baseline)
        synchronized(lock) {
            check(!closed) { "Workspace transition ingress is closed" }
            waiters += waiter
        }
        val current = semanticAdmission.status() as? IdeaIndexSemanticAdmission.Status.Ready
        if (current != null && current.generation != baseline && remove(waiter)) {
            waiter.result.complete(current.generation)
        }
        return waiter
    }

    private fun request(signal: WorkspaceSignal, waiter: TransitionWaiter) {
        val request = synchronized(lock) { requestTransition }
            ?: run {
                remove(waiter)
                throw ConflictException("Workspace transition ingress is not attached to the indexer worker")
            }
        try {
            request(signal)
        } catch (failure: Throwable) {
            remove(waiter)
            throw failure
        }
    }

    private suspend fun awaitStable(initial: TransitionWaiter): PublishedWorkspaceGenerationManifest {
        var waiter = initial
        while (true) {
            val published = await(waiter)
            val current = semanticAdmission.status() as? IdeaIndexSemanticAdmission.Status.Ready
            if (current?.generation == published) return published
            waiter = registerAfter(published)
        }
    }

    private suspend fun await(waiter: TransitionWaiter): PublishedWorkspaceGenerationManifest =
        withContext(Dispatchers.IO) {
            try {
                waiter.result.get(waitTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (failure: TimeoutException) {
                remove(waiter)
                throw ConflictException("Workspace reconciliation did not publish READY before the request timeout")
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
        }

    private fun remove(waiter: TransitionWaiter): Boolean = synchronized(lock) { waiters.remove(waiter) }

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

    private class TransitionWaiter(
        val baseline: PublishedWorkspaceGenerationManifest,
        val result: CompletableFuture<PublishedWorkspaceGenerationManifest> = CompletableFuture(),
    )
}
