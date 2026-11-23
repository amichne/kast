package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ManagementRoot
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

internal fun <T> withRegistrationLock(root: Path, action: () -> T): T =
    withManagementFileLock(ManagementRoot.of(root).management.registrationLock, "registration-lock", action)

/** The standalone installer uses a POSIX record lock on activation.lock for replacement and removal. */
internal fun <T> withWorkspaceEditLock(root: Path, action: () -> T): T =
    withRegistrationLock(root) {
        withManagementFileLock(ManagementRoot.of(root).management.activationLock, "activation-lock", action)
    }

@Suppress("ThrowsCount")
private fun <T> withManagementFileLock(lock: Path, stage: String, action: () -> T): T {
    if (Files.isSymbolicLink(lock)) throw ManagementRejected(stage, "lock path is untrusted")
    try {
        val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        FileChannel.open(lock, options, mode).use { channel ->
            val acquired = channel.tryLock() ?: throw ManagementRejected(stage, "another operation is active")
            acquired.use {
                return action()
            }
        }
    } catch (failure: ManagementRejected) {
        throw failure
    } catch (_: Exception) {
        throw ManagementRejected(stage, "lock is unavailable")
    }
}
