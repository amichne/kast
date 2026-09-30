package io.github.amichne.kast.distribution.managed

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

sealed interface ManagedCopiedEpochAdmission {
    data class Admitted(val epoch: ManagedCopiedEpoch) : ManagedCopiedEpochAdmission

    data object Rejected : ManagedCopiedEpochAdmission
}

enum class ManagedCopiedEpochVerification {
    VERIFIED,
    REJECTED,
}

enum class ManagedCopiedEpochRemoval {
    DELETED,
    IDENTITY_REJECTED,
    IO_REJECTED,
}

/** An exact copied epoch file bound to its physical staging directory, state directory and inode. */
class ManagedCopiedEpoch
private constructor(
    private val staged: Path,
    private val installationIdentity: EpochEntryIdentity,
    private val stateIdentity: EpochEntryIdentity,
    private val identity: EpochEntryIdentity,
) {
    private val state: Path
        get() = staged.resolve("state")

    private val path: Path
        get() = state.resolve("epoch.json")

    fun verify(): ManagedCopiedEpochVerification =
        try {
            if (physicalEpochDirectory(staged) && sameEpochDirectories() && sameEpochFile())
                ManagedCopiedEpochVerification.VERIFIED
            else ManagedCopiedEpochVerification.REJECTED
        } catch (_: java.io.IOException) {
            ManagedCopiedEpochVerification.REJECTED
        } catch (_: SecurityException) {
            ManagedCopiedEpochVerification.REJECTED
        }

    private fun sameEpochFile(): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && epochEntryIdentity(path) == identity

    private fun sameEpochDirectories(): Boolean =
        epochEntryIdentity(staged) == installationIdentity &&
            physicalEpochDirectory(state) &&
            epochEntryIdentity(state) == stateIdentity

    fun remove(): ManagedCopiedEpochRemoval =
        try {
            if (verify() != ManagedCopiedEpochVerification.VERIFIED) ManagedCopiedEpochRemoval.IDENTITY_REJECTED
            else {
                Files.delete(path)
                FileChannel.open(state, StandardOpenOption.READ).use { it.force(true) }
                ManagedCopiedEpochRemoval.DELETED
            }
        } catch (_: java.io.IOException) {
            ManagedCopiedEpochRemoval.IO_REJECTED
        } catch (_: SecurityException) {
            ManagedCopiedEpochRemoval.IO_REJECTED
        }

    companion object {
        fun admit(staged: Path): ManagedCopiedEpochAdmission =
            try {
                val state = staged.resolve("state")
                val path = state.resolve("epoch.json")
                if (
                    !eligibleEpochStage(staged) ||
                        !physicalEpochDirectory(state) ||
                        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                )
                    ManagedCopiedEpochAdmission.Rejected
                else
                    ManagedCopiedEpochAdmission.Admitted(
                        ManagedCopiedEpoch(
                            staged = staged,
                            installationIdentity = epochEntryIdentity(staged),
                            stateIdentity = epochEntryIdentity(state),
                            identity = epochEntryIdentity(path),
                        )
                    )
            } catch (_: java.io.IOException) {
                ManagedCopiedEpochAdmission.Rejected
            } catch (_: SecurityException) {
                ManagedCopiedEpochAdmission.Rejected
            }
    }
}

private fun physicalEpochDirectory(path: Path): Boolean =
    normalizedEpochPath(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path

private fun normalizedEpochPath(path: Path): Boolean = path.isAbsolute && path.normalize() == path

private fun eligibleEpochStage(path: Path): Boolean =
    physicalEpochDirectory(path) && path.fileName.toString().startsWith(".install-")

private data class EpochEntryIdentity(val device: Long, val inode: Long, val owner: Long)

private fun epochEntryIdentity(path: Path) =
    EpochEntryIdentity(
        (Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
    )
