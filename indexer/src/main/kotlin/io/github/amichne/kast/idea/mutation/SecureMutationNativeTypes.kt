package io.github.amichne.kast.idea.mutation

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import java.nio.file.Path
import io.github.amichne.kast.idea.*

internal class NativeDescriptor(
    private val api: PosixFileApi,
    val value: Int,
) : AutoCloseable {
    private var open = true

    override fun close() {
        if (open) {
            open = false
            api.close(value)
        }
    }
}

internal interface PosixFileApi : Library {
    fun open(path: String, flags: Int, mode: Int): Int

    fun openat(directoryDescriptor: Int, path: String, flags: Int, mode: Int): Int

    fun mkdirat(directoryDescriptor: Int, path: String, mode: Int): Int

    fun unlinkat(directoryDescriptor: Int, path: String, flags: Int): Int

    fun read(descriptor: Int, buffer: com.sun.jna.Pointer, count: NativeLong): NativeLong

    fun write(descriptor: Int, buffer: com.sun.jna.Pointer, count: NativeLong): NativeLong

    fun fsync(descriptor: Int): Int

    fun fchmod(descriptor: Int, mode: Int): Int

    fun fstat(descriptor: Int, status: com.sun.jna.Pointer): Int

    fun close(descriptor: Int): Int
}

internal interface MacRenameApi : Library {
    @Suppress("FunctionName")
    fun renameatx_np(
        oldDirectoryDescriptor: Int,
        oldPath: String,
        newDirectoryDescriptor: Int,
        newPath: String,
        flags: Int,
    ): Int
}

internal interface LinuxRenameApi : Library {
    fun renameat2(
        oldDirectoryDescriptor: Int,
        oldPath: String,
        newDirectoryDescriptor: Int,
        newPath: String,
        flags: Int,
    ): Int
}

internal data class PosixPlatform(
    val directoryFlags: Int,
    val readFileFlags: Int,
    val createExclusiveFileFlags: Int,
    val renamePrimitive: RenamePrimitive,
    val statDeviceOffset: Long,
    val statDeviceEncoding: NativeScalarEncoding,
    val statInodeOffset: Long,
    val statModeOffset: Long,
    val statModeEncoding: NativeScalarEncoding,
    val notFoundErrno: Int = 2,
    val alreadyExistsErrno: Int = 17,
) {
    fun readStatus(status: Memory): NativeFileStatus {
        val modeBits = readScalar(status, statModeOffset, statModeEncoding).toInt()
        return NativeFileStatus(
            mode = NativeFileMode(
                bits = modeBits,
                fileType = NativeFileType.fromMode(modeBits),
            ),
            identity = NativeFileIdentity(
            device = readScalar(status, statDeviceOffset, statDeviceEncoding),
            inode = status.getLong(statInodeOffset),
            ),
        )
    }

    private fun readScalar(status: Memory, offset: Long, encoding: NativeScalarEncoding): Long = when (encoding) {
        NativeScalarEncoding.UNSIGNED_SHORT -> (status.getShort(offset).toInt() and 0xffff).toLong()
        NativeScalarEncoding.INT -> status.getInt(offset).toLong()
        NativeScalarEncoding.LONG -> status.getLong(offset)
    }

    companion object {
        fun current(): PosixPlatform? = when {
            Platform.isMac() -> PosixPlatform(
                directoryFlags = MAC_OPEN_DIRECTORY or MAC_OPEN_NOFOLLOW or MAC_OPEN_CLOEXEC,
                readFileFlags = MAC_OPEN_NOFOLLOW or MAC_OPEN_NONBLOCK or MAC_OPEN_CLOEXEC,
                createExclusiveFileFlags = MAC_OPEN_WRITE_ONLY or MAC_OPEN_CREATE or MAC_OPEN_EXCLUSIVE or
                    MAC_OPEN_NOFOLLOW or MAC_OPEN_CLOEXEC,
                renamePrimitive = RenamePrimitive.MAC_RENAMEATX,
                statDeviceOffset = 0,
                statDeviceEncoding = NativeScalarEncoding.INT,
                statInodeOffset = 8,
                statModeOffset = 4,
                statModeEncoding = NativeScalarEncoding.UNSIGNED_SHORT,
            )

            Platform.isLinux() && Platform.is64Bit() && (Platform.isARM() || Platform.isIntel()) -> PosixPlatform(
                directoryFlags = LINUX_OPEN_DIRECTORY or LINUX_OPEN_NOFOLLOW or LINUX_OPEN_CLOEXEC,
                readFileFlags = LINUX_OPEN_NOFOLLOW or LINUX_OPEN_NONBLOCK or LINUX_OPEN_CLOEXEC,
                createExclusiveFileFlags = LINUX_OPEN_WRITE_ONLY or LINUX_OPEN_CREATE or LINUX_OPEN_EXCLUSIVE or
                    LINUX_OPEN_NOFOLLOW or LINUX_OPEN_CLOEXEC,
                renamePrimitive = RenamePrimitive.LINUX_RENAMEAT2,
                statDeviceOffset = 0,
                statDeviceEncoding = NativeScalarEncoding.LONG,
                statInodeOffset = 8,
                statModeOffset = if (Platform.isARM()) 16 else 24,
                statModeEncoding = NativeScalarEncoding.INT,
            )

            else -> null
        }
    }
}

internal enum class NativeScalarEncoding {
    UNSIGNED_SHORT,
    INT,
    LONG,
}

internal enum class RenamePrimitive {
    MAC_RENAMEATX,
    LINUX_RENAMEAT2,
}

internal enum class RenameNoReplaceOutcome {
    MOVED,
    DESTINATION_EXISTS,
    SOURCE_MISSING,
}

internal enum class Restoration {
    RESTORED,
    QUARANTINED,
}

internal sealed interface CleanupResult {
    val recoveryFilePaths: List<Path>

    fun conflictDetails(): Map<String, String>

    data object Removed : CleanupResult {
        override val recoveryFilePaths: List<Path> = emptyList()

        override fun conflictDetails(): Map<String, String> = emptyMap()
    }

    data class Retained(
        val recoveryFilePath: Path,
        val reason: String,
    ) : CleanupResult {
        override val recoveryFilePaths: List<Path> = listOf(recoveryFilePath)

        override fun conflictDetails(): Map<String, String> = mapOf(
            "cleanupRecoveryFilePath" to recoveryFilePath.toString(),
            "cleanupFailure" to reason,
        )
    }
}

internal sealed interface FinalReservationRelease {
    data class Released(val cleanup: CleanupResult) : FinalReservationRelease

    data class Blocked(
        val entryRecoveryFilePath: Path,
        val restoredToFinalName: Boolean,
        val reason: String,
    ) : FinalReservationRelease
}

internal data class NativeFileIdentity(
    val device: Long,
    val inode: Long,
) {
    fun details(prefix: String = "detached"): Map<String, String> = mapOf(
        "${prefix}Device" to device.toULong().toString(),
        "${prefix}Inode" to inode.toULong().toString(),
    )
}

internal data class NativeFileMode(
    val bits: Int,
    val fileType: NativeFileType,
) {
    val permissionBits: Int = bits and PERMISSION_BITS
}

internal enum class NativeFileType {
    FIFO,
    CHARACTER_DEVICE,
    DIRECTORY,
    BLOCK_DEVICE,
    REGULAR,
    SYMBOLIC_LINK,
    SOCKET,
    UNKNOWN,
    ;

    companion object {
        fun fromMode(mode: Int): NativeFileType = when (mode and FILE_TYPE_BITS) {
            FIFO_MODE -> FIFO
            CHARACTER_DEVICE_MODE -> CHARACTER_DEVICE
            DIRECTORY_MODE_BITS -> DIRECTORY
            BLOCK_DEVICE_MODE -> BLOCK_DEVICE
            REGULAR_FILE_MODE -> REGULAR
            SYMBOLIC_LINK_MODE -> SYMBOLIC_LINK
            SOCKET_MODE -> SOCKET
            else -> UNKNOWN
        }
    }
}

internal data class NativeFileStatus(
    val mode: NativeFileMode,
    val identity: NativeFileIdentity,
)

internal interface ExactNamedEntry : AutoCloseable {
    val name: String
    val status: NativeFileStatus
}

internal class DetachedTarget(
    override val name: String,
    private val descriptor: NativeDescriptor,
    override val status: NativeFileStatus,
    val actualDiskHash: String,
) : ExactNamedEntry {
    override fun close() {
        descriptor.close()
    }
}

internal class PreparedFile(
    override val name: String,
    private val descriptor: NativeDescriptor,
    override val status: NativeFileStatus,
) : ExactNamedEntry {
    override fun close() {
        descriptor.close()
    }
}
