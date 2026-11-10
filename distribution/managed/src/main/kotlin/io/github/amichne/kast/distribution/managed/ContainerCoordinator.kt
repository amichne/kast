package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

object ContainerCoordinator {
    enum class Failure {
        LOCK_UNAVAILABLE
    }

    /** One HOME-scoped lock serializes preparation without blocking another invocation. */
    fun <T> withLock(home: Path, operation: () -> T): Refinement<T, Failure> =
        try {
            val directory = home.resolve(".cache/kast/container")
            Files.createDirectories(directory)
            FileChannel.open(directory.resolve("coordinator.lock"), CREATE, WRITE).use { channel ->
                tryContainerLock(channel, operation)
            }
        } catch (_: IOException) {
            Refinement.Rejected(Failure.LOCK_UNAVAILABLE)
        } catch (_: OverlappingFileLockException) {
            Refinement.Rejected(Failure.LOCK_UNAVAILABLE)
        }

    private fun <T> tryContainerLock(channel: FileChannel, operation: () -> T): Refinement<T, Failure> {
        val lock = channel.tryLock() ?: return Refinement.Rejected(Failure.LOCK_UNAVAILABLE)
        return lock.use { Refinement.Refined(operation()) }
    }
}
