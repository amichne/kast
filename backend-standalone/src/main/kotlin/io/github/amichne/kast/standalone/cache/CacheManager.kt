package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.standalone.normalizeStandalonePath
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal const val defaultCacheWriteDelayMillis = 5_000L

internal class CacheManager(
    workspaceRoot: Path,
    envReader: (String) -> String? = System::getenv,
) : AutoCloseable {
    private val writeLock = Any()
    private val writeExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kast-cache-manager-write").apply {
            isDaemon = true
        }
    }
    private val cacheDirectory = kastCacheDirectory(normalizeStandalonePath(workspaceRoot))
    private val enabled = !isCacheDisabled(envReader)
    private val pendingWrites = linkedMapOf<String, PendingCacheWrite>()

    fun isEnabled(): Boolean = enabled

    fun runNow(action: () -> Unit) {
        if (!enabled) {
            return
        }
        runCatching(action)
    }

    fun schedule(
        key: String,
        delayMillis: Long = defaultCacheWriteDelayMillis,
        action: () -> Unit,
    ) {
        if (!enabled) {
            return
        }
        synchronized(writeLock) {
            pendingWrites.remove(key)?.future?.cancel(false)
            var future: ScheduledFuture<*> = writeExecutor.schedule(
                {
                    synchronized(writeLock) {
                        pendingWrites.remove(key)
                    }
                    runCatching(action)
                },
                delayMillis,
                TimeUnit.MILLISECONDS,
            )
            pendingWrites[key] = PendingCacheWrite(action = action, future = future)
        }
    }

    fun invalidateAll() {
        synchronized(writeLock) {
            pendingWrites.values.forEach { pendingWrite ->
                pendingWrite.future.cancel(false)
            }
            pendingWrites.clear()
        }
        cacheDirectory.toFile().deleteRecursively()
    }

    override fun close() {
        val pendingActions = synchronized(writeLock) {
            pendingWrites.values.map(PendingCacheWrite::action).also {
                pendingWrites.values.forEach { pendingWrite ->
                    pendingWrite.future.cancel(false)
                }
                pendingWrites.clear()
            }
        }
        pendingActions.forEach { action ->
            runCatching(action)
        }
        writeExecutor.shutdownNow()
    }
}

private data class PendingCacheWrite(
    val action: () -> Unit,
    val future: ScheduledFuture<*>,
)

internal fun isCacheDisabled(envReader: (String) -> String? = System::getenv): Boolean =
    envReader("KAST_CACHE_DISABLED").isTruthy()

private fun String?.isTruthy(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "true", "yes", "on" -> true
    else -> false
}
