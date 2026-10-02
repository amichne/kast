package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstallationResetRequest
import io.github.amichne.kast.distribution.contract.InstallationResetStorage
import io.github.amichne.kast.distribution.contract.installationResetFence
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal sealed interface ResetFenceAcquisition {
    data class Acquired(val fence: ForceResetFence) : ResetFenceAcquisition

    data class Rejected(val failure: ForceResetFailure) : ResetFenceAcquisition
}

/** The stable lock is never unlinked: waiters cannot acquire a different inode during activation. */
internal class ForceResetFence
private constructor(
    val root: ForceResetRoot,
    private val channel: FileChannel,
    private val lock: FileLock,
    private var storage: InstallationResetStorage,
) : AutoCloseable {
    val marker: Path = installationResetFence(root.path)

    companion object {
        fun acquire(root: ForceResetRoot): ResetFenceAcquisition {
            val marker = installationResetFence(root.path)
            return try {
                Files.createDirectories(marker.parent)
                val channel =
                    FileChannel.open(
                        marker.resolveSibling(marker.fileName.toString() + ".lock"),
                        CREATE,
                        WRITE,
                        NOFOLLOW_LINKS,
                    )
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lock == null) {
                    channel.close()
                    return ResetFenceAcquisition.Rejected(ForceResetFailure.RESET_BUSY)
                }
                val storage =
                    when (val read = readStorage(marker, root)) {
                        is ResetJournalAdmission.Admitted -> read.storage
                        ResetJournalAdmission.Rejected -> {
                            lock.release()
                            channel.close()
                            return ResetFenceAcquisition.Rejected(ForceResetFailure.FENCE_REJECTED)
                        }
                    }
                val fence = ForceResetFence(root, channel, lock, storage)
                when (val held = fence.hold()) {
                    ResetEffect.Completed -> ResetFenceAcquisition.Acquired(fence)
                    is ResetEffect.Rejected -> {
                        fence.close()
                        ResetFenceAcquisition.Rejected(held.failure)
                    }
                }
            } catch (_: IOException) {
                ResetFenceAcquisition.Rejected(ForceResetFailure.FENCE_REJECTED)
            } catch (_: SecurityException) {
                ResetFenceAcquisition.Rejected(ForceResetFailure.FENCE_REJECTED)
            }
        }

        private fun readStorage(marker: Path, root: ForceResetRoot): ResetJournalAdmission {
            if (Files.notExists(marker, NOFOLLOW_LINKS))
                return ResetJournalAdmission.Admitted(InstallationResetStorage.Fenced)
            val raw = readBoundedFile(marker, 4096) ?: return ResetJournalAdmission.Rejected
            val record =
                try {
                    managementJson.decodeFromString<InstallationResetRequest>(raw)
                } catch (_: SerializationException) {
                    return ResetJournalAdmission.Rejected
                }
            if (record.schemaVersion != 1 || record.installationRoot != root.path.resolve("installation").toString())
                return ResetJournalAdmission.Rejected
            return when (val storage = record.storage) {
                InstallationResetStorage.Fenced,
                InstallationResetStorage.Activating,
                InstallationResetStorage.Erased -> ResetJournalAdmission.Admitted(storage)
                is InstallationResetStorage.Retained ->
                    if (retentionPath(marker, storage.directory)) ResetJournalAdmission.Admitted(storage)
                    else ResetJournalAdmission.Rejected
            }
        }

        private fun retentionPath(marker: Path, raw: String): Boolean {
            return try {
                val path = Path.of(raw)
                if (!path.isAbsolute || path.normalize() != path || path.parent != marker.parent) return false
                path.fileName.toString().startsWith(retentionPrefix(marker)) && !Files.isSymbolicLink(path)
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        private fun retentionPrefix(marker: Path) =
            ".kast-retiring-" + marker.fileName.toString().removePrefix(".kast-reset-").removeSuffix(".json") + "-"
    }

    private var identity: ResetFenceIdentity = ResetFenceIdentity.Unproven
    private var detachment: ResetLease = ResetLease.FRESH

    fun admission(): ResetEffect =
        try {
            val prior = identity
            val attributes =
                Files.readAttributes(marker, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!lock.isValid || !channel.isOpen) return ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
            if (prior is ResetFenceIdentity.Proven && attributes.isRegularFile && attributes.fileKey() == prior.key)
                ResetEffect.Completed
            else ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        } catch (_: IOException) {
            ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        } catch (_: SecurityException) {
            ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        }

    fun detach(): ResetStorageDetachment {
        if (detachment != ResetLease.FRESH) return ResetStorageDetachment.Rejected(ForceResetFailure.RESET_BUSY)
        when (val admitted = admission()) {
            ResetEffect.Completed -> Unit
            is ResetEffect.Rejected -> return ResetStorageDetachment.Rejected(admitted.failure)
        }
        detachment = ResetLease.CONSUMED
        return try {
            when (val prior = storage) {
                InstallationResetStorage.Fenced,
                InstallationResetStorage.Activating,
                InstallationResetStorage.Erased -> detachNew()
                is InstallationResetStorage.Retained -> resumeRetention(Path.of(prior.directory))
            }
        } catch (_: IOException) {
            ResetStorageDetachment.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        } catch (_: SecurityException) {
            ResetStorageDetachment.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        }
    }

    private fun detachNew(): ResetStorageDetachment {
        if (Files.notExists(root.path, NOFOLLOW_LINKS))
            return ResetStorageDetachment.Detached(RetainedResetStorage.Missing)
        val directory = root.path.parent.resolve(retentionPrefix(marker) + java.util.UUID.randomUUID().toString())
        storage = InstallationResetStorage.Retained(directory.toString())
        when (val held = hold()) {
            ResetEffect.Completed -> Unit
            is ResetEffect.Rejected -> return ResetStorageDetachment.Rejected(held.failure)
        }
        return resumeRetention(directory)
    }

    private fun resumeRetention(directory: Path): ResetStorageDetachment {
        if (Files.isSymbolicLink(directory))
            return ResetStorageDetachment.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        val payload = directory.resolve("payload")
        val original = presence(root.path)
        val retained = presence(payload)
        if (original == ResetPathPresence.REJECTED || retained == ResetPathPresence.REJECTED)
            return ResetStorageDetachment.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        if (original == ResetPathPresence.PRESENT && retained == ResetPathPresence.PRESENT)
            return ResetStorageDetachment.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        if (original == ResetPathPresence.PRESENT) return retainOriginal(directory, payload)
        if (retained == ResetPathPresence.ABSENT) {
            Files.deleteIfExists(directory)
            return ResetStorageDetachment.Detached(RetainedResetStorage.Missing)
        }
        return ResetStorageDetachment.Detached(RetainedResetStorage.Held.recorded(directory))
    }

    private fun retainOriginal(directory: Path, payload: Path): ResetStorageDetachment {
        if (Files.notExists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
            return ResetStorageDetachment.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        if (Files.list(directory).use { it.findAny().isPresent })
            return ResetStorageDetachment.Rejected(ForceResetFailure.FILESYSTEM_REJECTED)
        Files.move(root.path, payload, ATOMIC_MOVE)
        return ResetStorageDetachment.Detached(RetainedResetStorage.Held.recorded(directory))
    }

    private fun presence(path: Path): ResetPathPresence =
        try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            ResetPathPresence.PRESENT
        } catch (_: java.nio.file.NoSuchFileException) {
            ResetPathPresence.ABSENT
        } catch (_: IOException) {
            ResetPathPresence.REJECTED
        } catch (_: SecurityException) {
            ResetPathPresence.REJECTED
        }

    fun beginActivation(staged: StagedReset): ResetEffect {
        if (staged.erased.fence !== this) return ResetEffect.Rejected(ForceResetFailure.ROOT_REJECTED)
        if (admission() != ResetEffect.Completed) return ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        storage = InstallationResetStorage.Activating
        return hold()
    }

    fun erased(): ResetEffect {
        storage = InstallationResetStorage.Erased
        return hold()
    }

    fun hold(): ResetEffect =
        try {
            if (!lock.isValid || !channel.isOpen) return ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
            val pending = Files.createTempFile(marker.parent, marker.fileName.toString(), ".pending")
            try {
                Files.writeString(
                    pending,
                    managementJson.encodeToString(
                        InstallationResetRequest(root.path.resolve("installation").toString(), storage)
                    ),
                    WRITE,
                    NOFOLLOW_LINKS,
                )
                FileChannel.open(pending, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
                Files.move(pending, marker, ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(pending)
            }
            val attributes =
                Files.readAttributes(marker, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val key = attributes.fileKey()
            if (!attributes.isRegularFile || key == null) ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
            else {
                identity = ResetFenceIdentity.Proven(key)
                ResetEffect.Completed
            }
        } catch (_: IOException) {
            ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        } catch (_: SecurityException) {
            ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        }

    fun liftRemoved(erased: ErasedReset): ResetEffect {
        if (erased.fence !== this || !Files.notExists(root.path, NOFOLLOW_LINKS))
            return ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
        return lift()
    }

    fun liftReady(ready: ReadyReset): ResetEffect {
        if (ready.staged.erased.fence !== this || storage != InstallationResetStorage.Activating)
            return ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
        return lift()
    }

    private fun lift(): ResetEffect =
        try {
            if (!lock.isValid || !channel.isOpen) return ResetEffect.Rejected(ForceResetFailure.RESET_BUSY)
            val prior = identity
            val attributes =
                Files.readAttributes(marker, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (prior !is ResetFenceIdentity.Proven || !attributes.isRegularFile || attributes.fileKey() != prior.key)
                return ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
            Files.delete(marker)
            ResetEffect.Completed
        } catch (_: IOException) {
            ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        } catch (_: SecurityException) {
            ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        }

    fun release(): ResetEffect =
        try {
            try {
                lock.release()
            } finally {
                channel.close()
            }
            ResetEffect.Completed
        } catch (_: IOException) {
            ResetEffect.Rejected(ForceResetFailure.FENCE_REJECTED)
        }

    override fun close() {
        release()
    }
}

internal sealed interface RetainedResetStorage {
    data object Missing : RetainedResetStorage

    class Held private constructor(val directory: Path) : RetainedResetStorage {
        companion object {
            fun recorded(directory: Path): Held = Held(directory)
        }
    }
}

internal sealed interface ResetStorageDetachment {
    data class Detached(val storage: RetainedResetStorage) : ResetStorageDetachment

    data class Rejected(val failure: ForceResetFailure) : ResetStorageDetachment
}

private sealed interface ResetFenceIdentity {
    data object Unproven : ResetFenceIdentity

    class Proven(val key: Any) : ResetFenceIdentity
}

internal enum class ResetLease {
    FRESH,
    CONSUMED,
}

private sealed interface ResetJournalAdmission {
    data class Admitted(val storage: InstallationResetStorage) : ResetJournalAdmission

    data object Rejected : ResetJournalAdmission
}

private enum class ResetPathPresence {
    PRESENT,
    ABSENT,
    REJECTED,
}
