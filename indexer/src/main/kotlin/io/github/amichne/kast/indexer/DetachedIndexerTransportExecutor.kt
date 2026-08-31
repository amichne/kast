package io.github.amichne.kast.indexer

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Closed observation of one operation executed on the long-lived transport thread. */
internal sealed interface IndexerTransportExecution<out Value> {
    data class Completed<Value>(
        val value: Value,
    ) : IndexerTransportExecution<Value>

    data object Failed : IndexerTransportExecution<Nothing>

    data object Interrupted : IndexerTransportExecution<Nothing>
}

/**
 * Detaches the installed transport from IntelliJ's finite application-startup coroutine.
 *
 * The platform invokes [KastIndexerApplicationStarter] with startup cancellation state installed
 * on its worker thread. A long-lived request must not inherit that completed job: synchronous
 * IntelliJ effects would otherwise observe cancellation after startup and abort valid requests.
 */
internal data object DetachedIndexerTransportExecutor {
    /**
     * Proof transition: `operation -> IndexerTransportExecution<Value>`.
     *
     * Establishes execution on one named platform thread with inherited thread-local state
     * disabled. Expected thread creation, execution, and interruption failures remain finite data.
     */
    fun <Value> execute(operation: () -> Value): IndexerTransportExecution<Value> {
        val task = FutureTask(operation)
        val thread = try {
            Thread.ofPlatform()
                .name(TRANSPORT_THREAD_NAME)
                .inheritInheritableThreadLocals(false)
                .unstarted(task)
        } catch (_: SecurityException) {
            return IndexerTransportExecution.Failed
        }
        try {
            thread.start()
        } catch (_: IllegalThreadStateException) {
            return IndexerTransportExecution.Failed
        } catch (_: SecurityException) {
            return IndexerTransportExecution.Failed
        }
        return try {
            IndexerTransportExecution.Completed(task.get())
        } catch (_: ExecutionException) {
            IndexerTransportExecution.Failed
        } catch (_: InterruptedException) {
            thread.interrupt()
            Thread.currentThread().interrupt()
            IndexerTransportExecution.Interrupted
        }
    }
}

private const val TRANSPORT_THREAD_NAME = "kast-indexer-transport"
