package io.github.amichne.kast.appserver.ide

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One frame owns its timer; cancelling a frame never cancels the application-owned native operation. */
internal fun interface LifecycleExchangeTimer {
    fun schedule(expire: () -> Unit): AutoCloseable

    data object Native : LifecycleExchangeTimer {
        override fun schedule(expire: () -> Unit): AutoCloseable {
            val executor = Executors.newSingleThreadScheduledExecutor()
            val future = executor.schedule(expire, LIMIT_MILLIS, TimeUnit.MILLISECONDS)
            return AutoCloseable {
                future.cancel(false)
                executor.shutdownNow()
            }
        }
    }

    companion object {
        const val LIMIT_MILLIS = 5_000L
    }
}
