package io.github.amichne.kast.indexer.storage

import io.github.amichne.kast.indexer.project.IndexerProjectLayout
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

class IndexerStorageInUseException(
    val code: String = CODE,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        const val CODE: String = "INDEXER_STORAGE_IN_USE"
    }
}

sealed class IndexerStorageLease : AutoCloseable {
    private class Inherited(
        @Suppress("unused") private val fileDescriptor: Int,
    ) : IndexerStorageLease() {
        // Closing any descriptor for this file can release every POSIX record
        // lock owned by this process. Keep the inherited descriptor untouched
        // until JVM exit.
        override fun close() = Unit
    }

    private class Direct(
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : IndexerStorageLease() {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    companion object {
        fun acquire(layout: IndexerProjectLayout): IndexerStorageLease {
            layout.prepare()
            return layout.inheritedStorageLeaseFileDescriptor
                ?.let { descriptor -> inherited(layout, descriptor) }
                ?: direct(layout)
        }

        private fun inherited(layout: IndexerProjectLayout, descriptor: Int): IndexerStorageLease {
            require(PosixStorageLeaseIdentity.matches(descriptor, layout.storageLeaseFile)) {
                "Inherited indexer storage lease descriptor does not own ${layout.storageLeaseFile}"
            }
            return Inherited(descriptor)
        }

        private fun direct(layout: IndexerProjectLayout): IndexerStorageLease {
            val channel = FileChannel.open(
                layout.storageLeaseFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (failure: OverlappingFileLockException) {
                channel.close()
                throw storageInUse(layout, failure)
            }
            if (lock == null) {
                channel.close()
                throw storageInUse(layout)
            }
            return Direct(channel, lock)
        }

        private fun storageInUse(
            layout: IndexerProjectLayout,
            cause: Throwable? = null,
        ): IndexerStorageInUseException = IndexerStorageInUseException(
            message = "Kast indexer storage is already in use for ${layout.workspaceRoot}",
            cause = cause,
        )
    }
}
