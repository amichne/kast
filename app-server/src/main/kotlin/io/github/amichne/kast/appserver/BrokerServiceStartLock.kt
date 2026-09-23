package io.github.amichne.kast.appserver

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Serializes launchd admission, retirement, and login resumption for one installation. */
internal sealed interface BrokerServiceLockExecution {
    data class Executed(val admission: PersistentBrokerServiceAdmission) : BrokerServiceLockExecution

    data object Interrupted : BrokerServiceLockExecution

    data object Rejected : BrokerServiceLockExecution

    data object TimedOut : BrokerServiceLockExecution
}

private sealed interface BrokerServiceLockAttempt {
    data class Acquired(val lock: FileLock) : BrokerServiceLockAttempt

    data object Busy : BrokerServiceLockAttempt

    data object Rejected : BrokerServiceLockAttempt
}

internal object BrokerServiceStartLock {
    fun withAcquired(
        path: Path,
        operation: () -> PersistentBrokerServiceAdmission,
    ): BrokerServiceLockExecution {
        if (Files.isSymbolicLink(path)) return BrokerServiceLockExecution.Rejected
        val channel =
            try {
                FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: IOException) {
                return BrokerServiceLockExecution.Rejected
            } catch (_: SecurityException) {
                return BrokerServiceLockExecution.Rejected
            }
        return channel.use { opened -> awaitAcquired(opened, operation) }
    }

    private fun awaitAcquired(
        channel: FileChannel,
        operation: () -> PersistentBrokerServiceAdmission,
    ): BrokerServiceLockExecution {
        val deadline = System.nanoTime() + LOCK_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            when (val attempt = tryAcquire(channel)) {
                is BrokerServiceLockAttempt.Acquired ->
                    return attempt.lock.use { BrokerServiceLockExecution.Executed(operation()) }
                BrokerServiceLockAttempt.Rejected -> return BrokerServiceLockExecution.Rejected
                BrokerServiceLockAttempt.Busy -> Unit
            }
            try {
                Thread.sleep(LOCK_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return BrokerServiceLockExecution.Interrupted
            }
        }
        return BrokerServiceLockExecution.TimedOut
    }

    private fun tryAcquire(channel: FileChannel): BrokerServiceLockAttempt =
        try {
            channel.tryLock()?.let { BrokerServiceLockAttempt.Acquired(it) } ?: BrokerServiceLockAttempt.Busy
        } catch (_: OverlappingFileLockException) {
            BrokerServiceLockAttempt.Busy
        } catch (_: IOException) {
            BrokerServiceLockAttempt.Rejected
        }

    private val LOCK_TIMEOUT_NANOS = BrokerServiceStartupBudgets.lockTimeoutNanos
    private val LOCK_POLL_MILLIS = BrokerOperationalLimits.serviceLockPoll.value
}
