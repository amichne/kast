package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import io.github.amichne.kast.api.contract.CloseableAnalysisBackend
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import io.github.amichne.kast.server.RunningAnalysisServer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RunningKastIdeaBackend internal constructor(
    val backend: CloseableAnalysisBackend,
    val server: RunningAnalysisServer,
    private val workspaceRoot: Path,
    private val projectIndexing: KastIdeaProjectIndexing?,
    private val sourceIndexStore: SqliteSourceIndexStore,
) : AutoCloseable, KastIdeaBackendHandle {
    private val closeCompletion = AtomicReference<CompletableFuture<Unit>?>(null)

    override fun close() {
        val completion = closeAsync()
        if (!ApplicationManager.getApplication().isDispatchThread) {
            try {
                completion.join()
            } catch (failure: CompletionException) {
                throw failure.cause ?: failure
            }
        }
    }

    override fun closeAsync(): CompletableFuture<Unit> {
        closeCompletion.get()?.let { return it }
        val completion = CompletableFuture<Unit>()
        if (!closeCompletion.compareAndSet(null, completion)) {
            return checkNotNull(closeCompletion.get())
        }
        val cancellationFailure = runCatching { projectIndexing?.cancel() }.exceptionOrNull()
        completeOnBackgroundThread(
            completion = completion,
            threadName = "kast-idea-backend-closer",
        ) {
            var firstFailure = cancellationFailure
            listOf<() -> Unit>(
                server::close,
                {
                    projectIndexing?.awaitTermination()
                    sourceIndexStore.close()
                },
            ).forEach { closePhase ->
                try {
                    closePhase()
                } catch (failure: Throwable) {
                    if (firstFailure == null) {
                        firstFailure = failure
                    } else {
                        firstFailure.addSuppressed(failure)
                    }
                }
            }
            firstFailure?.let { failure -> throw failure }
        }
        completion.whenComplete { _, failure ->
            if (failure != null) {
                LOG.warn("Error closing Kast IDEA backend", unwrapCloseFailure(failure))
            }
        }
        return completion
    }

    fun await() {
        server.await()
    }

    override fun startIndexing() {
        projectIndexing?.start()
    }

    override fun failIndexing(error: Throwable) {
        projectIndexing?.fail(error)
    }

    override fun explore(request: KastExplorerRequest): KastExplorerResult =
        sourceIndexStore.explore(workspaceRoot, request)

    private companion object {
        val LOG: Logger = Logger.getInstance(RunningKastIdeaBackend::class.java)
    }
}

internal fun closeSourceIndexStoreAfterIndexing(
    projectIndexing: KastIdeaProjectIndexing?,
    sourceIndexStore: SqliteSourceIndexStore,
    onAsyncFailure: (Throwable) -> Unit,
): Throwable? = closeAfterLeavingIdeaDispatchThread(
    threadName = "kast-idea-source-index-closer",
    onAsyncFailure = onAsyncFailure,
) {
    projectIndexing?.awaitTermination()
    sourceIndexStore.close()
}

internal fun closeAfterLeavingIdeaDispatchThread(
    threadName: String,
    onAsyncFailure: (Throwable) -> Unit,
    close: () -> Unit,
): Throwable? {
    if (!ApplicationManager.getApplication().isDispatchThread) {
        return runCatching(close).exceptionOrNull()
    }
    val completion = closeAfterLeavingIdeaDispatchThreadAsync(threadName, close)
    completion.whenComplete { _, failure ->
        if (failure != null) onAsyncFailure(unwrapCloseFailure(failure))
    }
    return null
}

internal fun closeAfterLeavingIdeaDispatchThreadAsync(
    threadName: String,
    close: () -> Unit,
): CompletableFuture<Unit> = CompletableFuture<Unit>().also { completion ->
    completeOnBackgroundThread(completion, threadName, close)
}

private fun completeOnBackgroundThread(
    completion: CompletableFuture<Unit>,
    threadName: String,
    close: () -> Unit,
) {
    try {
        thread(
            start = true,
            isDaemon = true,
            name = threadName,
        ) {
            completeNow(completion, close)
        }
    } catch (failure: Throwable) {
        completion.completeExceptionally(failure)
    }
}

private fun completeNow(
    completion: CompletableFuture<Unit>,
    close: () -> Unit,
) {
    try {
        close()
        completion.complete(Unit)
    } catch (failure: Throwable) {
        completion.completeExceptionally(failure)
    }
}

private fun unwrapCloseFailure(failure: Throwable): Throwable =
    if (failure is CompletionException && failure.cause != null) {
        checkNotNull(failure.cause)
    } else {
        failure
    }
