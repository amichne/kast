package io.github.amichne.kast.idea.mutation

import com.sun.jna.Native
import com.sun.jna.Platform
import io.github.amichne.kast.idea.*

internal const val FILE_MODE = 438 // 0666; the process umask still applies.
internal const val DIRECTORY_MODE = 511 // 0777; the process umask still applies.
internal const val CREATED_FILE_MODE = 420 // 0644; deterministic IDEA-compatible source permissions.
internal const val CREATED_DIRECTORY_MODE = 493 // 0755; deterministic IDEA-compatible directory permissions.
internal const val BUFFER_SIZE = 8192
internal const val STAT_BUFFER_SIZE = 256L
internal const val PERMISSION_BITS = 4095 // 07777
internal const val FILE_TYPE_BITS = 61440 // 0170000
internal const val FIFO_MODE = 4096 // 0010000
internal const val CHARACTER_DEVICE_MODE = 8192 // 0020000
internal const val DIRECTORY_MODE_BITS = 16384 // 0040000
internal const val BLOCK_DEVICE_MODE = 24576 // 0060000
internal const val REGULAR_FILE_MODE = 32768 // 0100000
internal const val SYMBOLIC_LINK_MODE = 40960 // 0120000
internal const val SOCKET_MODE = 49152 // 0140000
internal const val MAX_UNIQUE_NAME_ATTEMPTS = 8

internal const val QUARANTINE_PREFIX = ".kast-quarantine-"
internal const val PREPARED_PREFIX = ".kast-prepared-"
internal const val CLEANUP_PREFIX = ".kast-cleanup-"

internal const val MAC_RENAME_EXCLUSIVE = 0x00000004
internal const val MAC_RENAME_NOFOLLOW_ANY = 0x00000010
internal const val LINUX_RENAME_NOREPLACE = 0x00000001

internal const val MAC_OPEN_WRITE_ONLY = 0x0001
internal const val MAC_OPEN_NONBLOCK = 0x0004
internal const val MAC_OPEN_CREATE = 0x0200
internal const val MAC_OPEN_EXCLUSIVE = 0x0800
internal const val MAC_OPEN_NOFOLLOW = 0x0100
internal const val MAC_OPEN_DIRECTORY = 0x100000
internal const val MAC_OPEN_CLOEXEC = 0x1000000

internal const val LINUX_OPEN_WRITE_ONLY = 0x0001
internal const val LINUX_OPEN_NONBLOCK = 0x0800
internal const val LINUX_OPEN_CREATE = 0x0040
internal const val LINUX_OPEN_EXCLUSIVE = 0x0080
internal const val LINUX_OPEN_DIRECTORY = 0x10000
internal const val LINUX_OPEN_NOFOLLOW = 0x20000
internal const val LINUX_OPEN_CLOEXEC = 0x80000

internal val api: PosixFileApi by lazy {
    Native.load(Platform.C_LIBRARY_NAME, PosixFileApi::class.java)
}

internal val macRenameApi: MacRenameApi by lazy {
    Native.load(Platform.C_LIBRARY_NAME, MacRenameApi::class.java)
}

internal val linuxRenameApi: LinuxRenameApi by lazy {
    Native.load(Platform.C_LIBRARY_NAME, LinuxRenameApi::class.java)
}
