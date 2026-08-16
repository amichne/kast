package io.github.amichne.kast.indexer.storage

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path

internal data class PosixStorageLeaseIdentity(
    val device: Long,
    val inode: Long,
) {
    companion object {
        private const val STATUS_BUFFER_SIZE = 256L

        private interface PosixApi : Library {
            fun fstat(descriptor: Int, status: Pointer): Int
        }

        private val api: PosixApi by lazy {
            Native.load(Platform.C_LIBRARY_NAME, PosixApi::class.java)
        }

        fun matches(descriptor: Int, leaseFile: Path): Boolean =
            fromDescriptor(descriptor)?.let { inherited -> inherited == fromPath(leaseFile) } == true

        private fun fromDescriptor(descriptor: Int): PosixStorageLeaseIdentity? {
            if (descriptor < 0) return null
            val status = Memory(STATUS_BUFFER_SIZE)
            if (api.fstat(descriptor, status) != 0) return null
            return when {
                Platform.isMac() -> PosixStorageLeaseIdentity(
                    device = Integer.toUnsignedLong(status.getInt(0)),
                    inode = status.getLong(8),
                )

                Platform.isLinux() && Platform.is64Bit() && (Platform.isARM() || Platform.isIntel()) ->
                    PosixStorageLeaseIdentity(
                        device = status.getLong(0),
                        inode = status.getLong(8),
                    )

                else -> null
            }
        }

        private fun fromPath(path: Path): PosixStorageLeaseIdentity {
            val attributes = Files.readAttributes(path, "unix:dev,ino")
            return PosixStorageLeaseIdentity(
                device = (checkNotNull(attributes["dev"]) as Number).toLong(),
                inode = (checkNotNull(attributes["ino"]) as Number).toLong(),
            )
        }
    }
}
