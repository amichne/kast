package io.github.amichne.kast.standalone.cache

import io.github.amichne.kast.api.client.KastConfig
import io.github.amichne.kast.indexstore.kastCacheDirectory
import io.github.amichne.kast.standalone.normalizeStandalonePath
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class CacheManager(
    workspaceRoot: Path,
    config: KastConfig = KastConfig.load(workspaceRoot),
) : AutoCloseable {
    private val writeLock = Any()
    private val writeExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kast-cache-manager-write").apply {
            isDaemon = true
        }
    }
    private val cacheDirectory = kastCacheDirectory(normalizeStandalonePath(workspaceRoot))
    private val enabled = config.cache.enabled
    private val defaultWriteDelayMillis = config.cache.writeDelayMillis
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
        delayMillis: Long = defaultWriteDelayMillis,
        action: () -> Unit,
    ) {
        if (!enabled) {
            return
        }
        synchronized(writeLock) {
            pendingWrites.remove(key)?.future?.cancel(false)
            val future: ScheduledFuture<*> = writeExecutor.schedule(
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
