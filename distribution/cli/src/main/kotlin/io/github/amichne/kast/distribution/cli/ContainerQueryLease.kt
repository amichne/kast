package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption

/** A live native query keeps its private state until its exact child has been reaped. */
internal class ContainerQueryLease private constructor(private val channel: FileChannel, private val lock: FileLock) :
    AutoCloseable {
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        fun acquire(container: AdmittedContainer): Refinement<ContainerQueryLease, ContainerFailure> =
            acquire(container, true)

        /** Removal must prove both Host shutdown (caller) and the absence of Control readers (this lease). */
        fun <T> whileIdle(
            container: AdmittedContainer,
            action: () -> Refinement<T, ContainerFailure>,
        ): Refinement<T, ContainerFailure> =
            when (val lease = acquire(container, false)) {
                is Refinement.Rejected -> lease
                is Refinement.Refined -> lease.value.use { action() }
            }

        private fun acquire(
            container: AdmittedContainer,
            shared: Boolean,
        ): Refinement<ContainerQueryLease, ContainerFailure> = containerFiles {
            val channel =
                FileChannel.open(
                    container.directory.resolve("queries.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    NOFOLLOW_LINKS,
                )
            val lock =
                try {
                    channel.tryLock(0, Long.MAX_VALUE, shared)
                } catch (_: java.nio.channels.OverlappingFileLockException) {
                    null
                } catch (failure: java.io.IOException) {
                    channel.close()
                    throw failure
                }
            if (lock == null) {
                channel.close()
                Refinement.Rejected(ContainerFailure.BUSY)
            } else Refinement.Refined(ContainerQueryLease(channel, lock))
        }
    }
}
