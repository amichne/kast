package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Serializes service admission, retirement, and login resumption for one installation. */
internal sealed interface BrokerServiceLockExecution<out T> {
    data class Executed<T>(val admission: T) : BrokerServiceLockExecution<T>

    data object Interrupted : BrokerServiceLockExecution<Nothing>

    data object Rejected : BrokerServiceLockExecution<Nothing>

    data object TimedOut : BrokerServiceLockExecution<Nothing>
}

private sealed interface BrokerServiceLockAttempt {
    data class Acquired(val lock: FileLock) : BrokerServiceLockAttempt

    data object Busy : BrokerServiceLockAttempt

    data object Rejected : BrokerServiceLockAttempt
}

internal object BrokerServiceStartLock {
    fun submitSemantic(
        command: SemanticServiceLaunch,
        submit: () -> Refinement<Unit, PersistentBrokerServiceFailure>,
    ): Refinement<Unit, PersistentBrokerServiceFailure> {
        if (!prepareSemanticServiceState(command))
            return Refinement.Rejected(PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED)
        return when (
            val locked =
                withAcquired(command.lock) {
                    if (
                        InstallationLifecycleFence.observe(command.installation) !=
                            InstallationLifecycleStartAdmission.AVAILABLE
                    )
                        Refinement.Rejected(PersistentBrokerServiceFailure.DISABLED)
                    else submit()
                }
        ) {
            is BrokerServiceLockExecution.Executed -> locked.admission
            BrokerServiceLockExecution.Interrupted -> Refinement.Rejected(PersistentBrokerServiceFailure.INTERRUPTED)
            BrokerServiceLockExecution.Rejected ->
                Refinement.Rejected(PersistentBrokerServiceFailure.SERVICE_LOCK_REJECTED)
            BrokerServiceLockExecution.TimedOut -> Refinement.Rejected(PersistentBrokerServiceFailure.STARTUP_TIMED_OUT)
        }
    }

    private fun prepareSemanticServiceState(command: SemanticServiceLaunch): Boolean {
        val directoryMode = PosixFilePermissions.fromString("rwx------")
        val fileMode = PosixFilePermissions.fromString("rw-------")
        val owner = Files.getOwner(command.installation, LinkOption.NOFOLLOW_LINKS)
        val dirs = listOf(command.state.parent.parent, command.state.parent, command.state)
        for (directory in dirs) {
            try {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(directoryMode))
            } catch (_: FileAlreadyExistsException) {
                // Existing state must pass the same filesystem admission.
            }
            if (
                !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
                    directory.toRealPath() != directory ||
                    Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS) != owner
            )
                return false
        }
        if (Files.getPosixFilePermissions(command.state, LinkOption.NOFOLLOW_LINKS) != directoryMode) return false
        try {
            Files.createFile(command.log, PosixFilePermissions.asFileAttribute(fileMode))
        } catch (_: FileAlreadyExistsException) {
            // A running service can already own the private log.
        }
        return Files.isRegularFile(command.log, LinkOption.NOFOLLOW_LINKS) &&
            Files.getOwner(command.log, LinkOption.NOFOLLOW_LINKS) == owner &&
            Files.getPosixFilePermissions(command.log, LinkOption.NOFOLLOW_LINKS) == fileMode
    }

    fun <T> withAcquired(
        path: Path,
        operation: () -> T,
    ): BrokerServiceLockExecution<T> {
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

    private fun <T> awaitAcquired(
        channel: FileChannel,
        operation: () -> T,
    ): BrokerServiceLockExecution<T> {
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
