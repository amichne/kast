package io.github.amichne.kast.idea.transition

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class WorkspaceEventWakeup(
    private val nanoTime: () -> Long = System::nanoTime,
    private val awaitCondition: (Condition, Long) -> Long = Condition::awaitNanos,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var pending = false
    private var lastSignalNanos = 0L
    private val signals = linkedSetOf<WorkspaceSignal>()

    fun signal(signal: WorkspaceSignal) {
        lock.withLock {
            pending = true
            signals += signal
            lastSignalNanos = nanoTime()
            changed.signalAll()
        }
    }

    fun drainSignals(): Set<WorkspaceSignal> = lock.withLock {
        val drained = signals.toSet()
        signals.clear()
        drained
    }

    fun awaitSignalOrAudit(auditMillis: Long): Boolean = try {
        lock.withLock {
            if (!pending) changed.await(auditMillis, TimeUnit.MILLISECONDS)
            pending = false
        }
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    fun awaitWakeup(auditMillis: Long): WorkspaceWakeup = try {
        lock.withLock {
            val deadlineNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(auditMillis)
            while (!pending) {
                val remainingNanos = deadlineNanos - nanoTime()
                if (remainingNanos <= 0L) return@withLock WorkspaceWakeup.RecoveryAudit
                awaitCondition(changed, remainingNanos)
            }
            pending = false
            WorkspaceWakeup.Signal
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        WorkspaceWakeup.Interrupted
    }

    fun awaitQuiescence(quiescenceMillis: Long): Boolean = try {
        lock.withLock {
            while (true) {
                val remainingNanos = TimeUnit.MILLISECONDS.toNanos(quiescenceMillis) -
                    (nanoTime() - lastSignalNanos).coerceAtLeast(0L)
                if (remainingNanos <= 0) return@withLock
                awaitCondition(changed, remainingNanos)
            }
        }
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

internal enum class WorkspaceWakeup {
    Signal,
    RecoveryAudit,
    Interrupted,
}
