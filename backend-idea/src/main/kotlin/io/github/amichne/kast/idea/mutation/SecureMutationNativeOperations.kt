package io.github.amichne.kast.idea.mutation

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import io.github.amichne.kast.idea.*

internal fun SecureWorkspaceMutation.moveToUniqueName(
        parent: NativeDescriptor,
        sourceName: String,
        prefix: String,
        target: Path,
        platform: PosixPlatform,
        phase: SecureWorkspaceRenamePhase,
        sourceMissing: () -> Nothing,
    ): String {
        repeat(MAX_UNIQUE_NAME_ATTEMPTS) {
            val destinationName = "$prefix${UUID.randomUUID()}"
            when (
                renameNoReplace(
                    parent = parent,
                    sourceName = sourceName,
                    destinationName = destinationName,
                    target = target,
                    platform = platform,
                    phase = phase,
                )
            ) {
                RenameNoReplaceOutcome.MOVED -> return destinationName
                RenameNoReplaceOutcome.DESTINATION_EXISTS -> Unit
                RenameNoReplaceOutcome.SOURCE_MISSING -> sourceMissing()
            }
        }
        throw UnsafeWorkspaceMutationException(
            message = "Secure workspace mutation could not allocate a unique quarantine entry",
            details = failureDetails(target, "allocate-quarantine-name"),
        )
    }

internal fun SecureWorkspaceMutation.renameNoReplace(
        parent: NativeDescriptor,
        sourceName: String,
        destinationName: String,
        target: Path,
        platform: PosixPlatform,
        phase: SecureWorkspaceRenamePhase,
    ): RenameNoReplaceOutcome {
        beforeNoReplaceRename(target, phase)
        val result = try {
            when (platform.renamePrimitive) {
                RenamePrimitive.MAC_RENAMEATX -> macRenameApi.renameatx_np(
                    parent.value,
                    sourceName,
                    parent.value,
                    destinationName,
                    MAC_RENAME_EXCLUSIVE or MAC_RENAME_NOFOLLOW_ANY,
                )

                RenamePrimitive.LINUX_RENAMEAT2 -> linuxRenameApi.renameat2(
                    parent.value,
                    sourceName,
                    parent.value,
                    destinationName,
                    LINUX_RENAME_NOREPLACE,
                )
            }
        } catch (exception: LinkageError) {
            throw UnsafeWorkspaceMutationException(
                message = "Atomic no-replace workspace mutation primitives are unavailable",
                details = failureDetails(target, "native-no-replace-load") + mapOf(
                    "cause" to (exception.message ?: exception::class.java.simpleName),
                ),
            )
        }
        if (result == 0) return RenameNoReplaceOutcome.MOVED
        return when (val errno = Native.getLastError()) {
            platform.alreadyExistsErrno -> RenameNoReplaceOutcome.DESTINATION_EXISTS
            platform.notFoundErrno -> RenameNoReplaceOutcome.SOURCE_MISSING
            else -> throw nativeFailure("rename-no-replace", target, sourceName, errno)
        }
    }

internal fun <T> SecureWorkspaceMutation.withParentDescriptor(
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

internal fun SecureWorkspaceMutation.openDirectory(
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

internal fun SecureWorkspaceMutation.requireWorkspaceTarget(target: Path, mutation: IdeaWorkspaceMutation): Path {
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (normalizedTarget == normalizedWorkspaceRoot || !normalizedTarget.startsWith(normalizedWorkspaceRoot)) {
            throw UnsafeWorkspaceMutationException(
                message = "Secure mutation target is outside the active workspace",
                details = failureDetails(normalizedTarget, mutation.wireName),
            )
        }
        return normalizedTarget
    }

internal fun SecureWorkspaceMutation.writeFully(api: PosixFileApi, descriptor: Int, bytes: ByteArray, target: Path) {
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

internal fun SecureWorkspaceMutation.readFully(api: PosixFileApi, descriptor: Int, target: Path): String {
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

internal fun SecureWorkspaceMutation.descriptorStatus(
        api: PosixFileApi,
        platform: PosixPlatform,
        descriptor: Int,
        target: Path,
    ): NativeFileStatus {
        val status = Memory(STAT_BUFFER_SIZE)
        requireSuccess(api.fstat(descriptor, status), "fstat", target, target.fileName.toString())
        return platform.readStatus(status)
    }

internal fun SecureWorkspaceMutation.requireSuccess(result: Int, operation: String, target: Path, component: String) {
        if (result < 0) {
            throw nativeFailure(operation, target, component, Native.getLastError())
        }
    }

internal fun SecureWorkspaceMutation.loadApi(target: Path): PosixFileApi = try {
        api
    } catch (exception: LinkageError) {
        throw UnsafeWorkspaceMutationException(
            message = "Secure workspace mutation primitives are unavailable",
            details = failureDetails(target, "native-load") + mapOf(
                "cause" to (exception.message ?: exception::class.java.simpleName),
            ),
        )
    }

internal fun SecureWorkspaceMutation.unsupportedPlatform(target: Path): UnsafeWorkspaceMutationException =
        UnsafeWorkspaceMutationException(
            message = "Secure workspace mutations require a supported POSIX runtime",
            details = failureDetails(target, "unsupported-platform") + mapOf(
                "operatingSystem" to System.getProperty("os.name", "unknown"),
            ),
        )

internal fun SecureWorkspaceMutation.nativeFailure(
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

internal fun SecureWorkspaceMutation.failureDetails(target: Path, operation: String): Map<String, String> = mapOf(
        "filePath" to target.toString(),
        "workspaceRoot" to normalizedWorkspaceRoot.toString(),
        "nativeOperation" to operation,
    )
