package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.UnixSocketOwnershipLeaseAcquisition
import io.github.amichne.kast.appserver.runtime.UnixSocketPathOwnership
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlinx.serialization.SerializationException

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
    /**
     * Submits only when no owned generation is live or starting. An owned stale publication is retired first; a fence
     * or ambiguous ownership rejects before any supervisor effect.
     */
    fun submitSemantic(
        command: SemanticServiceLaunch,
        submit: () -> Refinement<Unit, PersistentBrokerServiceFailure>,
    ): Refinement<Unit, PersistentBrokerServiceFailure> {
        if (InstallationLifecycleFence.observe(command.installation) != InstallationLifecycleStartAdmission.AVAILABLE)
            return Refinement.Rejected(PersistentBrokerServiceFailure.DISABLED)
        if (!prepareSemanticServiceState(command))
            return Refinement.Rejected(PersistentBrokerServiceFailure.STATE_DIRECTORY_REJECTED)
        return when (
            val locked =
                withAcquired(command.lock) {
                    when (
                        val state =
                            SemanticPublicationState.inspect(
                                command.installation,
                                command.readiness,
                                command.executable,
                            )
                    ) {
                        SemanticPublicationState.Missing -> submit()
                        is SemanticPublicationState.Stale ->
                            if (retireStalePublication(state)) submit()
                            else Refinement.Rejected(PersistentBrokerServiceFailure.READINESS_REJECTED)
                        is SemanticPublicationState.Starting,
                        is SemanticPublicationState.Published -> Refinement.Refined(Unit)
                        is SemanticPublicationState.Rejected -> Refinement.Rejected(state.rejection.serviceFailure())
                    }
                }
        ) {
            is BrokerServiceLockExecution.Executed -> locked.admission
            BrokerServiceLockExecution.Interrupted -> Refinement.Rejected(PersistentBrokerServiceFailure.INTERRUPTED)
            BrokerServiceLockExecution.Rejected ->
                Refinement.Rejected(PersistentBrokerServiceFailure.SERVICE_LOCK_REJECTED)
            BrokerServiceLockExecution.TimedOut -> Refinement.Rejected(PersistentBrokerServiceFailure.STARTUP_TIMED_OUT)
        }
    }

    private fun retireStalePublication(state: SemanticPublicationState.Stale): Boolean {
        return try {
            val key = admittedServiceStateFileKey(state.path, state.document) ?: return false
            if (!retireStaleSocket(state.installation.resolve("state/run/c.sock"), state.peer.socketKey)) return false
            if (admittedServiceStateFileKey(state.path, state.document) != key) return false
            Files.delete(state.path)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun retireStaleSocket(socket: Path, expectedKey: String): Boolean =
        when (val lease = UnixSocketPathOwnership.acquireLease(socket)) {
            is UnixSocketOwnershipLeaseAcquisition.Acquired ->
                lease.lease.use {
                    if (Files.notExists(socket, NOFOLLOW_LINKS)) return true
                    val attributes = Files.readAttributes(socket, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (!attributes.isOther || attributes.fileKey()?.toString() != expectedKey) return false
                    Files.delete(socket)
                    true
                }
            UnixSocketOwnershipLeaseAcquisition.Owned,
            UnixSocketOwnershipLeaseAcquisition.Rejected,
            UnixSocketOwnershipLeaseAcquisition.ParentRejected -> false
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

    /** Consume only the stopped private semantic service's files under the installation fence. */
    internal fun removeRetiredSemanticState(installation: Path, home: Path): Boolean {
        return try {
            if (!semanticStateFenced(installation)) return false
            val command = SemanticServiceLaunch.admit(installation, home, emptyMap()) ?: return false
            val owner = Files.getOwner(installation, NOFOLLOW_LINKS)
            if (Files.notExists(command.state, NOFOLLOW_LINKS)) return true
            if (!ownedDirectory(command.state, owner) || !ownedDirectory(command.state.parent, owner)) return false
            val empty = Files.newDirectoryStream(command.state).use { !it.iterator().hasNext() }
            if (empty) return removeSemanticProfile(command.state)
            if (Files.notExists(command.log, NOFOLLOW_LINKS)) return true
            removeSemanticProfileState(command, owner)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun removeSemanticProfileState(command: SemanticServiceLaunch, owner: UserPrincipal): Boolean {
        val entries = Files.newDirectoryStream(command.state).use { it.take(semanticStateNames.size + 1) }
        if (entries.any { it.fileName.toString() !in semanticStateNames || !ownedFile(it, owner) }) return false
        val locked =
            withAcquired(command.lock) {
                removeLockedSemanticState(command, owner)
            }
        if (locked != BrokerServiceLockExecution.Executed(true)) return false
        if (!ownedDirectory(command.state, owner) || !ownedFile(command.lock, owner)) return false
        Files.delete(command.lock)
        Files.delete(command.log)
        return removeSemanticProfile(command.state)
    }

    private fun removeSemanticProfile(profile: Path): Boolean {
        Files.delete(profile)
        if (Files.newDirectoryStream(profile.parent).use { !it.iterator().hasNext() }) Files.delete(profile.parent)
        return true
    }

    private fun removeLockedSemanticState(command: SemanticServiceLaunch, owner: UserPrincipal): Boolean {
        if (!semanticStateFenced(command.installation)) return false
        val publication =
            SemanticPublicationState.inspectOwned(command.installation, command.readiness, command.executable)
        val stopped =
            when (val state = publication) {
                SemanticPublicationState.Missing -> true
                is SemanticPublicationState.Stale -> retireStalePublication(state)
                else -> false
            }
        if (!stopped) return false
        if (System.getProperty("os.name") == "Mac OS X" && !withdrawSemanticLaunchdJob(command)) return false
        if (!removeSemanticSocketState(command.installation.resolve("state/run/c.sock"), owner)) return false
        val plist = command.state.resolve("semantic-service.plist")
        if (Files.notExists(plist, NOFOLLOW_LINKS)) return true
        if (!ownedFile(plist, owner)) return false
        Files.delete(plist)
        return true
    }

    private fun removeSemanticSocketState(socket: Path, owner: UserPrincipal): Boolean {
        val run = socket.parent
        if (Files.notExists(run, NOFOLLOW_LINKS)) return true
        if (!ownedDirectory(run, owner) || !Files.notExists(socket, NOFOLLOW_LINKS)) return false
        val lock = run.resolve("c.sock.lock")
        if (Files.notExists(lock, NOFOLLOW_LINKS)) return BrokerEndpointAliases.retire(socket) is Validation.Validated
        if (!ownedFile(lock, owner)) return false
        val acquired = UnixSocketPathOwnership.acquireLease(socket)
        if (acquired !is UnixSocketOwnershipLeaseAcquisition.Acquired) return false
        return acquired.lease.use {
            if (!Files.notExists(socket, NOFOLLOW_LINKS) || !ownedFile(lock, owner)) return false
            if (BrokerEndpointAliases.retire(socket) !is Validation.Validated) return false
            Files.delete(lock)
            true
        }
    }

    private fun semanticStateFenced(installation: Path): Boolean =
        InstallationLifecycleFence.observe(installation) == InstallationLifecycleStartAdmission.TRANSITION_IN_PROGRESS

    private fun ownedDirectory(path: Path, owner: UserPrincipal): Boolean =
        Files.isDirectory(path, NOFOLLOW_LINKS) &&
            path.toRealPath() == path &&
            Files.getOwner(path, NOFOLLOW_LINKS) == owner

    private fun ownedFile(path: Path, owner: UserPrincipal): Boolean {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return attributes.isRegularFile && Files.getOwner(path, NOFOLLOW_LINKS) == owner
    }

    private val semanticStateNames =
        setOf("semantic-service.log", "semantic-service.plist", "service-start.lock", "service-readiness.json")

    private val LOCK_TIMEOUT_NANOS = BrokerServiceStartupBudgets.lockTimeoutNanos
    private val LOCK_POLL_MILLIS = BrokerOperationalLimits.serviceLockPoll.value
}
