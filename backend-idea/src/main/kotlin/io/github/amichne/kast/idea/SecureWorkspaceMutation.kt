package io.github.amichne.kast.idea

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Performs workspace mutations relative to held POSIX directory descriptors.
 *
 * The walk starts at the filesystem root and refuses symlinks for every
 * component. Once a directory is open, later symlink replacement cannot
 * redirect resolution away from that held directory identity.
 */
internal class SecureWorkspaceMutation(
    workspaceRoot: Path,
) {
    private val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()

    fun createFile(target: Path, content: String) {
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.CREATE_FILE)
        withParentDescriptor(normalizedTarget, createParents = true) { parent, fileName, api, platform ->
            val descriptor = api.openat(
                parent.value,
                fileName,
                platform.createExclusiveFileFlags,
                FILE_MODE,
            )
            if (descriptor < 0) {
                val errno = Native.getLastError()
                if (errno == platform.alreadyExistsErrno) {
                    throw ConflictException(
                        message = "The requested file already exists",
                        details = mapOf("filePath" to normalizedTarget.toString()),
                    )
                }
                throw nativeFailure("openat-create", normalizedTarget, fileName, errno)
            }

            NativeDescriptor(api, descriptor).use { file ->
                try {
                    requireSuccess(api.fchmod(file.value, CREATED_FILE_MODE), "fchmod-create", normalizedTarget, fileName)
                    writeFully(api, file.value, content.toByteArray(StandardCharsets.UTF_8), normalizedTarget)
                    requireSuccess(api.fsync(file.value), "fsync", normalizedTarget, fileName)
                } catch (exception: Exception) {
                    api.unlinkat(parent.value, fileName, 0)
                    throw exception
                }
            }
        }
    }

    fun replaceFile(target: Path, expectedDiskHash: String, content: String) {
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.TEXT_EDIT)
        val currentMode = targetMode(normalizedTarget)
        withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
            val currentDescriptor = api.openat(parent.value, fileName, platform.readFileFlags, 0)
            if (currentDescriptor < 0) {
                val errno = Native.getLastError()
                if (errno == platform.notFoundErrno) {
                    throw NotFoundException(
                        message = "The requested file does not exist",
                        details = mapOf("filePath" to normalizedTarget.toString()),
                    )
                }
                throw nativeFailure("openat-read", normalizedTarget, fileName, errno)
            }
            val currentContent = NativeDescriptor(api, currentDescriptor).use { file ->
                readFully(api, file.value, normalizedTarget)
            }
            val actualDiskHash = FileHashing.sha256(currentContent)
            if (actualDiskHash != expectedDiskHash) {
                throw ConflictException(
                    message = "The file changed at the secure write boundary",
                    details = mapOf(
                        "filePath" to normalizedTarget.toString(),
                        "expectedHash" to expectedDiskHash,
                        "actualHash" to actualDiskHash,
                    ),
                )
            }

            val temporaryName = ".kast-secure-${UUID.randomUUID()}.tmp"
            val temporaryDescriptor = api.openat(
                parent.value,
                temporaryName,
                platform.createExclusiveFileFlags,
                FILE_MODE,
            )
            if (temporaryDescriptor < 0) {
                throw nativeFailure(
                    "openat-temporary",
                    normalizedTarget,
                    temporaryName,
                    Native.getLastError(),
                )
            }

            try {
                NativeDescriptor(api, temporaryDescriptor).use { temporary ->
                    requireSuccess(
                        api.fchmod(temporary.value, currentMode),
                        "fchmod-temporary",
                        normalizedTarget,
                        temporaryName,
                    )
                    writeFully(api, temporary.value, content.toByteArray(StandardCharsets.UTF_8), normalizedTarget)
                    requireSuccess(api.fsync(temporary.value), "fsync-temporary", normalizedTarget, temporaryName)
                }
                requireSuccess(
                    api.renameat(parent.value, temporaryName, parent.value, fileName),
                    "renameat",
                    normalizedTarget,
                    fileName,
                )
            } finally {
                api.unlinkat(parent.value, temporaryName, 0)
            }
        }
    }

    fun deleteFile(target: Path, expectedDiskHash: String) {
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.DELETE_FILE)
        withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
            val currentDescriptor = api.openat(parent.value, fileName, platform.readFileFlags, 0)
            if (currentDescriptor < 0) {
                val errno = Native.getLastError()
                if (errno == platform.notFoundErrno) {
                    throw NotFoundException(
                        message = "The requested file does not exist",
                        details = mapOf("filePath" to normalizedTarget.toString()),
                    )
                }
                throw nativeFailure("openat-delete", normalizedTarget, fileName, errno)
            }
            val currentContent = NativeDescriptor(api, currentDescriptor).use { file ->
                readFully(api, file.value, normalizedTarget)
            }
            val actualDiskHash = FileHashing.sha256(currentContent)
            if (actualDiskHash != expectedDiskHash) {
                throw ConflictException(
                    message = "The file changed after the delete plan was created",
                    details = mapOf(
                        "filePath" to normalizedTarget.toString(),
                        "expectedHash" to expectedDiskHash,
                        "actualHash" to actualDiskHash,
                    ),
                )
            }
            requireSuccess(
                api.unlinkat(parent.value, fileName, 0),
                "unlinkat",
                normalizedTarget,
                fileName,
            )
        }
    }

    private fun <T> withParentDescriptor(
        target: Path,
        createParents: Boolean,
        action: (NativeDescriptor, String, PosixFileApi, PosixPlatform) -> T,
    ): T {
        val platform = PosixPlatform.current()
            ?: throw unsupportedPlatform(target)
        val api = loadApi(target)
        val filesystemRoot = checkNotNull(normalizedWorkspaceRoot.root) {
            "Absolute workspace root must have a filesystem root"
        }
        val rootDescriptor = api.open(filesystemRoot.toString(), platform.directoryFlags, 0)
        if (rootDescriptor < 0) {
            throw nativeFailure("open-root", target, filesystemRoot.toString(), Native.getLastError())
        }

        return NativeDescriptor(api, rootDescriptor).use { root ->
            var current = root
            val opened = mutableListOf<NativeDescriptor>()
            try {
                val workspaceComponents = filesystemRoot.relativize(normalizedWorkspaceRoot).map(Path::toString)
                workspaceComponents.forEach { component ->
                    val next = openDirectory(api, platform, current, component, target, create = false)
                    opened += next
                    current = next
                }

                val relativeTarget = normalizedWorkspaceRoot.relativize(target)
                val targetComponents = relativeTarget.map(Path::toString).toList()
                targetComponents.dropLast(1).forEach { component ->
                    val next = openDirectory(api, platform, current, component, target, createParents)
                    opened += next
                    current = next
                }
                action(current, targetComponents.last(), api, platform)
            } finally {
                opened.asReversed().forEach(NativeDescriptor::close)
            }
        }
    }

    private fun openDirectory(
        api: PosixFileApi,
        platform: PosixPlatform,
        parent: NativeDescriptor,
        component: String,
        target: Path,
        create: Boolean,
    ): NativeDescriptor {
        var descriptor = api.openat(parent.value, component, platform.directoryFlags, 0)
        if (descriptor >= 0) {
            return NativeDescriptor(api, descriptor)
        }
        var errno = Native.getLastError()
        var createdDirectory = false
        if (create && errno == platform.notFoundErrno) {
            val mkdirResult = api.mkdirat(parent.value, component, DIRECTORY_MODE)
            val mkdirErrno = Native.getLastError()
            if (mkdirResult < 0 && mkdirErrno != platform.alreadyExistsErrno) {
                throw nativeFailure("mkdirat", target, component, mkdirErrno)
            }
            createdDirectory = mkdirResult == 0
            descriptor = api.openat(parent.value, component, platform.directoryFlags, 0)
            errno = Native.getLastError()
            if (descriptor >= 0 && createdDirectory && api.fchmod(descriptor, CREATED_DIRECTORY_MODE) < 0) {
                val chmodErrno = Native.getLastError()
                api.close(descriptor)
                throw nativeFailure("fchmod-directory", target, component, chmodErrno)
            }
        }
        if (descriptor < 0) {
            throw nativeFailure("openat-directory", target, component, errno)
        }
        return NativeDescriptor(api, descriptor)
    }

    private fun requireWorkspaceTarget(target: Path, mutation: IdeaWorkspaceMutation): Path {
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (normalizedTarget == normalizedWorkspaceRoot || !normalizedTarget.startsWith(normalizedWorkspaceRoot)) {
            throw UnsafeWorkspaceMutationException(
                message = "Secure mutation target is outside the active workspace",
                details = failureDetails(normalizedTarget, mutation.wireName),
            )
        }
        return normalizedTarget
    }

    private fun writeFully(api: PosixFileApi, descriptor: Int, bytes: ByteArray, target: Path) {
        if (bytes.isEmpty()) return
        val memory = Memory(bytes.size.toLong())
        memory.write(0, bytes, 0, bytes.size)
        var offset = 0L
        while (offset < bytes.size) {
            val written = api.write(descriptor, memory.share(offset), NativeLong(bytes.size - offset))
            if (written.toLong() <= 0) {
                throw nativeFailure("write", target, target.fileName.toString(), Native.getLastError())
            }
            offset += written.toLong()
        }
    }

    private fun readFully(api: PosixFileApi, descriptor: Int, target: Path): String {
        val output = ByteArrayOutputStream()
        val buffer = Memory(BUFFER_SIZE.toLong())
        while (true) {
            val read = api.read(descriptor, buffer, NativeLong(BUFFER_SIZE.toLong())).toLong()
            if (read == 0L) break
            if (read < 0L) {
                throw nativeFailure("read", target, target.fileName.toString(), Native.getLastError())
            }
            output.write(buffer.getByteArray(0, read.toInt()))
        }
        return output.toString(StandardCharsets.UTF_8)
    }

    private fun targetMode(target: Path): Int {
        val permissions = try {
            Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)
        } catch (exception: Exception) {
            throw UnsafeWorkspaceMutationException(
                message = "Secure workspace mutation could not preserve target permissions",
                details = failureDetails(target, "read-target-permissions") + mapOf(
                    "cause" to (exception.message ?: exception::class.java.simpleName),
                ),
            )
        }
        return permissions.fold(0) { mode, permission ->
            mode or permission.modeBit
        }
    }

    private val PosixFilePermission.modeBit: Int
        get() = when (this) {
            PosixFilePermission.OWNER_READ -> 256
            PosixFilePermission.OWNER_WRITE -> 128
            PosixFilePermission.OWNER_EXECUTE -> 64
            PosixFilePermission.GROUP_READ -> 32
            PosixFilePermission.GROUP_WRITE -> 16
            PosixFilePermission.GROUP_EXECUTE -> 8
            PosixFilePermission.OTHERS_READ -> 4
            PosixFilePermission.OTHERS_WRITE -> 2
            PosixFilePermission.OTHERS_EXECUTE -> 1
        }

    private fun requireSuccess(result: Int, operation: String, target: Path, component: String) {
        if (result < 0) {
            throw nativeFailure(operation, target, component, Native.getLastError())
        }
    }

    private fun loadApi(target: Path): PosixFileApi = try {
        api
    } catch (exception: LinkageError) {
        throw UnsafeWorkspaceMutationException(
            message = "Secure workspace mutation primitives are unavailable",
            details = failureDetails(target, "native-load") + mapOf(
                "cause" to (exception.message ?: exception::class.java.simpleName),
            ),
        )
    }

    private fun unsupportedPlatform(target: Path): UnsafeWorkspaceMutationException =
        UnsafeWorkspaceMutationException(
            message = "Secure workspace mutations require a supported POSIX runtime",
            details = failureDetails(target, "unsupported-platform") + mapOf(
                "operatingSystem" to System.getProperty("os.name", "unknown"),
            ),
        )

    private fun nativeFailure(
        operation: String,
        target: Path,
        component: String,
        errno: Int,
    ): UnsafeWorkspaceMutationException = UnsafeWorkspaceMutationException(
        message = "Secure workspace mutation refused an unsafe filesystem path",
        details = failureDetails(target, operation) + mapOf(
            "pathComponent" to component,
            "errno" to errno.toString(),
        ),
    )

    private fun failureDetails(target: Path, operation: String): Map<String, String> = mapOf(
        "filePath" to target.toString(),
        "workspaceRoot" to normalizedWorkspaceRoot.toString(),
        "nativeOperation" to operation,
    )

    private class NativeDescriptor(
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

    private interface PosixFileApi : Library {
        fun open(path: String, flags: Int, mode: Int): Int

        fun openat(directoryDescriptor: Int, path: String, flags: Int, mode: Int): Int

        fun mkdirat(directoryDescriptor: Int, path: String, mode: Int): Int

        fun unlinkat(directoryDescriptor: Int, path: String, flags: Int): Int

        fun renameat(
            oldDirectoryDescriptor: Int,
            oldPath: String,
            newDirectoryDescriptor: Int,
            newPath: String,
        ): Int

        fun read(descriptor: Int, buffer: com.sun.jna.Pointer, count: NativeLong): NativeLong

        fun write(descriptor: Int, buffer: com.sun.jna.Pointer, count: NativeLong): NativeLong

        fun fsync(descriptor: Int): Int

        fun fchmod(descriptor: Int, mode: Int): Int

        fun close(descriptor: Int): Int
    }

    private data class PosixPlatform(
        val directoryFlags: Int,
        val readFileFlags: Int,
        val createExclusiveFileFlags: Int,
        val notFoundErrno: Int = 2,
        val alreadyExistsErrno: Int = 17,
    ) {
        companion object {
            fun current(): PosixPlatform? = when {
                Platform.isMac() -> PosixPlatform(
                    directoryFlags = MAC_OPEN_DIRECTORY or MAC_OPEN_NOFOLLOW or MAC_OPEN_CLOEXEC,
                    readFileFlags = MAC_OPEN_NOFOLLOW or MAC_OPEN_CLOEXEC,
                    createExclusiveFileFlags = MAC_OPEN_WRITE_ONLY or MAC_OPEN_CREATE or MAC_OPEN_EXCLUSIVE or
                        MAC_OPEN_NOFOLLOW or MAC_OPEN_CLOEXEC,
                )

                Platform.isLinux() -> PosixPlatform(
                    directoryFlags = LINUX_OPEN_DIRECTORY or LINUX_OPEN_NOFOLLOW or LINUX_OPEN_CLOEXEC,
                    readFileFlags = LINUX_OPEN_NOFOLLOW or LINUX_OPEN_CLOEXEC,
                    createExclusiveFileFlags = LINUX_OPEN_WRITE_ONLY or LINUX_OPEN_CREATE or LINUX_OPEN_EXCLUSIVE or
                        LINUX_OPEN_NOFOLLOW or LINUX_OPEN_CLOEXEC,
                )

                else -> null
            }
        }
    }

    private companion object {
        const val FILE_MODE = 438 // 0666; the process umask still applies.
        const val DIRECTORY_MODE = 511 // 0777; the process umask still applies.
        const val CREATED_FILE_MODE = 420 // 0644; deterministic IDEA-compatible source permissions.
        const val CREATED_DIRECTORY_MODE = 493 // 0755; deterministic IDEA-compatible directory permissions.
        const val BUFFER_SIZE = 8192

        const val MAC_OPEN_WRITE_ONLY = 0x0001
        const val MAC_OPEN_CREATE = 0x0200
        const val MAC_OPEN_EXCLUSIVE = 0x0800
        const val MAC_OPEN_NOFOLLOW = 0x0100
        const val MAC_OPEN_DIRECTORY = 0x100000
        const val MAC_OPEN_CLOEXEC = 0x1000000

        const val LINUX_OPEN_WRITE_ONLY = 0x0001
        const val LINUX_OPEN_CREATE = 0x0040
        const val LINUX_OPEN_EXCLUSIVE = 0x0080
        const val LINUX_OPEN_DIRECTORY = 0x10000
        const val LINUX_OPEN_NOFOLLOW = 0x20000
        const val LINUX_OPEN_CLOEXEC = 0x80000

        val api: PosixFileApi by lazy {
            Native.load(Platform.C_LIBRARY_NAME, PosixFileApi::class.java)
        }
    }
}
