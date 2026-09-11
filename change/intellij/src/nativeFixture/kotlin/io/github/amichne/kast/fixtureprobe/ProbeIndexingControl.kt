package io.github.amichne.kast.fixtureprobe

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbModeTask
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal class ProbeIndexingControl(private val project: Project, private val sandbox: ProbeSandbox) : Disposable {
    private val ownership = AtomicReference<ProbeIndexingOwnership>(ProbeIndexingOwnership.Available)

    fun execute(request: ProbeRequest, observeSource: () -> ProbeExecution): ProbeExecution =
        try {
            when (request.command) {
                ProbeCommand.HOLD_INDEXING -> hold(observeSource)
                ProbeCommand.RELEASE_INDEXING -> release(observeSource)
                else -> ProbeExecution.Rejected(ProbeFailure.UNKNOWN_COMMAND)
            }
        } catch (_: TimeoutException) {
            dispose()
            ProbeExecution.Rejected(ProbeFailure.INDEXING_START_TIMEOUT)
        } catch (_: InterruptedException) {
            dispose()
            Thread.currentThread().interrupt()
            ProbeExecution.Rejected(ProbeFailure.SETUP_CANCELLED)
        } catch (_: Exception) {
            dispose()
            ProbeExecution.Rejected(ProbeFailure.INDEXING_UNAVAILABLE)
        }

    private fun hold(observeSource: () -> ProbeExecution): ProbeExecution {
        val task = ProbeOwnedIndexingTask(project, sandbox)
        if (!ownership.compareAndSet(ProbeIndexingOwnership.Available, ProbeIndexingOwnership.Owned(task))) {
            task.release()
            return ProbeExecution.Rejected(ProbeFailure.INDEXING_ALREADY_REQUESTED)
        }
        val before = onEdt { queueObserved(task, observeSource) }
        if (before !is ProbeExecution.Completed) {
            task.release()
            return before
        }
        return when (val started = task.started.get(INDEXING_START_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            is ProbeResult.Accepted -> ProbeExecution.IndexingHeld(before.evidence)
            is ProbeResult.Rejected -> ProbeExecution.Rejected(started.failure)
        }
    }

    private fun queueObserved(task: ProbeOwnedIndexingTask, observeSource: () -> ProbeExecution): ProbeExecution {
        if (!sandbox.valid(project) || DumbService.getInstance(project).isDumb)
            return ProbeExecution.Rejected(ProbeFailure.INDEXING_UNAVAILABLE)
        return when (val source = observeSource()) {
            is ProbeExecution.Completed ->
                if (source.evidence.documentState == ProbeDocumentState.SAVED_COMMITTED) {
                    DumbService.getInstance(project).queueTask(task)
                    source
                } else ProbeExecution.Rejected(ProbeFailure.DOCUMENT_STATE_REJECTED)
            else -> source
        }
    }

    private fun release(observeSource: () -> ProbeExecution): ProbeExecution {
        val retained =
            ownership.get() as? ProbeIndexingOwnership.Owned
                ?: return ProbeExecution.Rejected(ProbeFailure.INDEXING_NOT_OWNED)
        retained.task.release()
        DumbService.getInstance(project).cancelTask(retained.task)
        if (!retained.task.finished.await(MAXIMUM_INDEXING_HOLD_MILLIS, TimeUnit.MILLISECONDS)) {
            return ProbeExecution.Rejected(ProbeFailure.INDEXING_RELEASE_TIMEOUT)
        }
        if (!DumbService.getInstance(project).waitForSmartMode(MAXIMUM_INDEXING_HOLD_MILLIS)) {
            return ProbeExecution.Rejected(ProbeFailure.INDEXING_RELEASE_TIMEOUT)
        }
        return onEdt {
            if (!sandbox.valid(project) || DumbService.getInstance(project).isDumb)
                ProbeExecution.Rejected(ProbeFailure.INDEXING_UNAVAILABLE)
            else
                when (val source = observeSource()) {
                    is ProbeExecution.Completed -> ProbeExecution.IndexingReleased(source.evidence)
                    else -> source
                }
        }
    }

    private fun <Value> onEdt(action: () -> Value): Value {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INDEXING_START_WAIT_MILLIS)
        return CompletableFuture.supplyAsync(
                {
                    if (System.nanoTime() >= deadline || ownership.get() == ProbeIndexingOwnership.Disposed)
                        throw TimeoutException()
                    action()
                },
                { runnable -> ApplicationManager.getApplication().invokeLater(runnable, ModalityState.nonModal()) },
            )
            .get(INDEXING_START_WAIT_MILLIS, TimeUnit.MILLISECONDS)
    }

    override fun dispose() {
        when (val retained = ownership.getAndSet(ProbeIndexingOwnership.Disposed)) {
            is ProbeIndexingOwnership.Owned -> {
                retained.task.release()
                if (!project.isDisposed) DumbService.getInstance(project).cancelTask(retained.task)
            }
            ProbeIndexingOwnership.Available,
            ProbeIndexingOwnership.Disposed -> Unit
        }
    }
}

private class ProbeOwnedIndexingTask(private val project: Project, private val sandbox: ProbeSandbox) : DumbModeTask() {
    val started = CompletableFuture<ProbeResult<Unit>>()
    val finished = CountDownLatch(1)
    private val released = CountDownLatch(1)

    override fun performInDumbMode(indicator: ProgressIndicator) {
        try {
            if (!sandbox.valid(project) || !DumbService.getInstance(project).isDumb || released.count == 0L) {
                started.complete(ProbeResult.Rejected(ProbeFailure.INDEXING_UNAVAILABLE))
                return
            }
            started.complete(ProbeResult.Accepted(Unit))
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAXIMUM_INDEXING_HOLD_MILLIS)
            while (System.nanoTime() < deadline && !released.await(INDEXING_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                indicator.checkCanceled()
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            release()
            finished.countDown()
        }
    }

    fun release() {
        released.countDown()
    }

    override fun dispose() {
        release()
        started.complete(ProbeResult.Rejected(ProbeFailure.INDEXING_UNAVAILABLE))
        finished.countDown()
    }
}

private sealed interface ProbeIndexingOwnership {
    data object Available : ProbeIndexingOwnership

    data object Disposed : ProbeIndexingOwnership

    data class Owned(val task: ProbeOwnedIndexingTask) : ProbeIndexingOwnership
}

internal const val MAXIMUM_INDEXING_HOLD_MILLIS = 10000L
private const val INDEXING_START_WAIT_MILLIS = 5000L
private const val INDEXING_POLL_MILLIS = 100L
