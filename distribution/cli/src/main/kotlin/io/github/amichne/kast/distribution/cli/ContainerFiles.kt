package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val containerJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
internal val containerDirectoryMode = PosixFilePermissions.fromString("rwx------")
internal const val CONTAINER_RECORD_BYTES = 65_536

/** Expected adapter failures cross this boundary once; defects and interruption are not converted to success. */
internal inline fun <T> containerFiles(
    observe: (ContainerFilesystemObservation) -> Unit = ::recordContainerFilesystemObservation,
    action: () -> Refinement<T, ContainerFailure>,
): Refinement<T, ContainerFailure> =
    try {
        action()
    } catch (_: java.util.zip.ZipException) {
        Refinement.Rejected(ContainerFailure.INVALID_ARCHIVE)
    } catch (failure: IOException) {
        observe(containerFilesystemObservation(failure))
        Refinement.Rejected(ContainerFailure.FILESYSTEM_UNAVAILABLE)
    } catch (_: SecurityException) {
        Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    } catch (_: SerializationException) {
        Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    } catch (_: IllegalArgumentException) {
        Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    }

internal fun privateContainerDirectory(path: Path): Refinement<Path, ContainerFailure> {
    Files.createDirectories(path, PosixFilePermissions.asFileAttribute(containerDirectoryMode))
    return admitPrivateContainerDirectory(path)
}

internal fun admitPrivateContainerDirectory(path: Path): Refinement<Path, ContainerFailure> {
    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) return Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    return if (
        path.toRealPath() != path ||
            Files.getOwner(path) != Files.getOwner(Path.of(System.getProperty("user.home"))) ||
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != containerDirectoryMode
    )
        Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    else Refinement.Refined(path)
}

/** Current records keep retirement locks outside the directory that retirement can detach. */
internal fun containerQueryLockPath(workspace: AdmittedWorkspace): Refinement<Path, ContainerFailure> =
    when (workspace.generation) {
        is ContainerGenerationSource.Legacy -> Refinement.Refined(workspace.directory.resolve("queries.lock"))
        ContainerGenerationSource.Current ->
            when (val locks = privateContainerDirectory(workspace.directory.parent.parent.resolve("locks"))) {
                is Refinement.Rejected -> locks
                is Refinement.Refined -> Refinement.Refined(locks.value.resolve("${workspace.id.text}.queries.lock"))
            }
    }

internal inline fun <reified T> readContainerRecord(
    path: Path,
    limit: Int = CONTAINER_RECORD_BYTES,
): Refinement<T, ContainerFailure> {
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path)
        return Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
    return if (bytes.size > limit) Refinement.Rejected(ContainerFailure.INVALID_RECORD)
    else Refinement.Refined(containerJson.decodeFromString<T>(bytes.decodeToString(throwOnInvalidSequence = true)))
}

internal inline fun <reified T> writeContainerRecord(path: Path, record: T) {
    Files.writeString(
        path,
        containerJson.encodeToString(record) + "\n",
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
    )
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
}

internal fun <T> withContainerLock(
    root: Path,
    action: () -> Refinement<T, ContainerFailure>,
): Refinement<T, ContainerFailure> =
    withContainerRegistryChannel(root) { channel ->
        val lease =
            try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
        if (lease == null) Refinement.Rejected(ContainerFailure.BUSY) else lease.use { action() }
    }

internal fun <T> withContainerPublicationLock(
    root: Path,
    allowance: ElapsedTimeAllowance,
    waiting: ContainerPreparationWait,
    action: () -> Refinement<T, ContainerFailure>,
): Refinement<T, ContainerFailure> =
    withContainerRegistryChannel(root) { channel ->
        when (val acquired = awaitContainerPreparationLock(channel, allowance, waiting)) {
            is Refinement.Rejected -> acquired
            is Refinement.Refined -> acquired.value.use { action() }
        }
    }

private fun <T> withContainerRegistryChannel(
    root: Path,
    action: (FileChannel) -> Refinement<T, ContainerFailure>,
): Refinement<T, ContainerFailure> = containerFiles {
    Files.createDirectories(root, PosixFilePermissions.asFileAttribute(containerDirectoryMode))
    val mode = Files.getPosixFilePermissions(root, NOFOLLOW_LINKS)
    val sharedWrite =
        setOf(
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
        )
    if (
        root.toRealPath() != root ||
            Files.getOwner(root) != Files.getOwner(Path.of(System.getProperty("user.home"))) ||
            mode.any(sharedWrite::contains)
    ) {
        return@containerFiles Refinement.Rejected(ContainerFailure.FOREIGN_STATE)
    }
    val lock = root.resolve("container.lock")
    FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
        action(channel)
    }
}

internal interface ContainerPreparationWait {
    fun elapsed(): Duration

    fun pause(duration: Duration)

    fun admitActive(): Refinement<Unit, ContainerFailure>
}

/** A query lease is required before waiting for preparation, so retirement cannot cross this boundary. */
internal fun <T> withContainerPreparationLock(
    lease: ContainerQueryLease,
    allowance: ElapsedTimeAllowance,
    waiting: ContainerPreparationWait,
    action: (ElapsedTimeAllowance) -> Refinement<T, ContainerFailure>,
): Refinement<T, ContainerFailure> = lease.whileActive {
    containerFiles {
        FileChannel.open(
                lease.workspace.directory.resolve("preparation.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                NOFOLLOW_LINKS,
            )
            .use { channel ->
                when (val acquired = awaitContainerPreparationLock(channel, allowance, waiting)) {
                    is Refinement.Rejected -> acquired
                    is Refinement.Refined ->
                        acquired.value.use {
                            when (val remaining = remainingPreparationAllowance(allowance, waiting.elapsed())) {
                                is Refinement.Rejected -> remaining
                                is Refinement.Refined -> action(remaining.value)
                            }
                        }
                }
            }
    }
}

private fun remainingPreparationAllowance(
    allowance: ElapsedTimeAllowance,
    elapsed: Duration,
): Refinement<ElapsedTimeAllowance, ContainerFailure> =
    when (allowance) {
        ElapsedTimeAllowance.Unlimited -> Refinement.Refined(allowance)
        is ElapsedTimeAllowance.Bounded ->
            when (
                val remaining = ElapsedTimeAllowance.admitMillis((allowance.duration - elapsed).inWholeMilliseconds)
            ) {
                is Refinement.Rejected -> Refinement.Rejected(ContainerFailure.PREPARATION_WAIT_EXPIRED)
                is Refinement.Refined -> remaining
            }
    }

private fun awaitContainerPreparationLock(
    channel: FileChannel,
    allowance: ElapsedTimeAllowance,
    waiting: ContainerPreparationWait,
): Refinement<FileLock, ContainerFailure> {
    while (true) {
        when (val active = waiting.admitActive()) {
            is Refinement.Rejected -> return active
            is Refinement.Refined -> Unit
        }
        val remaining = allowance.duration - waiting.elapsed()
        if (remaining <= Duration.ZERO) return Refinement.Rejected(ContainerFailure.PREPARATION_WAIT_EXPIRED)
        val lock =
            try {
                channel.tryLock()
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
        if (lock != null) return Refinement.Refined(lock)
        try {
            waiting.pause(minOf(remaining, CONTAINER_LOCK_RETRY_MILLIS.milliseconds))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Refinement.Rejected(ContainerFailure.CANCELLED_INTERRUPT)
        }
    }
}

private const val CONTAINER_LOCK_RETRY_MILLIS = 25
