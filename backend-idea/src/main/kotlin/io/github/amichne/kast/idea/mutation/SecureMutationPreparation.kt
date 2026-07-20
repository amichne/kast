package io.github.amichne.kast.idea.mutation

import com.sun.jna.Native
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import io.github.amichne.kast.idea.*

internal fun SecureWorkspaceMutation.detachValidatedTarget(
        parent: NativeDescriptor,
        fileName: String,
        target: Path,
        expectedDiskHash: String,
        mutation: IdeaWorkspaceMutation,
        hashConflictMessage: String,
        api: PosixFileApi,
        platform: PosixPlatform,
    ): DetachedTarget {
        val quarantineName = moveToUniqueName(
            parent = parent,
            sourceName = fileName,
            prefix = QUARANTINE_PREFIX,
            target = target,
            platform = platform,
            phase = SecureWorkspaceRenamePhase.DETACH_TARGET,
            sourceMissing = {
                throw NotFoundException(
                    message = "The requested file does not exist",
                    details = mapOf("filePath" to target.toString()),
                )
            },
        )
        val descriptorValue = api.openat(parent.value, quarantineName, platform.readFileFlags, 0)
        if (descriptorValue < 0) {
            val errno = Native.getLastError()
            restoreUnopenedQuarantine(parent, fileName, quarantineName, target, platform)
            throw nativeFailure("openat-quarantine", target, quarantineName, errno)
        }

        val descriptor = NativeDescriptor(api, descriptorValue)
        val detached = try {
            val status = descriptorStatus(api, platform, descriptor.value, target)
            if (status.mode.fileType != NativeFileType.REGULAR) {
                throw UnsafeWorkspaceMutationException(
                    message = "Secure workspace mutation requires a regular file target",
                    details = failureDetails(target, "reject-non-regular-target") + mapOf(
                        "fileType" to status.mode.fileType.name,
                        "fileMode" to status.mode.bits.toString(8),
                    ),
                )
            }
            val content = readFully(api, descriptor.value, target)
            DetachedTarget(
                name = quarantineName,
                descriptor = descriptor,
                status = status,
                actualDiskHash = FileHashing.sha256(content),
            )
        } catch (exception: Exception) {
            descriptor.close()
            restoreUnopenedQuarantine(parent, fileName, quarantineName, target, platform)
            throw exception
        }

        if (detached.actualDiskHash != expectedDiskHash) {
            detached.use {
                throw conflictWithRestoration(
                    message = hashConflictMessage,
                    target = target,
                    fileName = fileName,
                    detached = detached,
                    parent = parent,
                    platform = platform,
                    details = mapOf(
                        "expectedHash" to expectedDiskHash,
                        "actualHash" to detached.actualDiskHash,
                    ),
                )
            }
        }

        try {
            afterTargetDetached(target, mutation)
        } catch (exception: Exception) {
            detached.use {
                throw conflictWithRestoration(
                    message = "The secure mutation was interrupted after detaching its target",
                    target = target,
                    fileName = fileName,
                    detached = detached,
                    parent = parent,
                    platform = platform,
                    details = mapOf("cause" to (exception.message ?: exception::class.java.simpleName)),
                )
            }
        }
        return detached
    }

internal fun SecureWorkspaceMutation.createPreparedFile(
        parent: NativeDescriptor,
        target: Path,
        content: String,
        mode: Int,
        api: PosixFileApi,
        platform: PosixPlatform,
        onUntrackedPreparationFailure: (Exception) -> Nothing,
        onPreparationFailure: (PreparedFile, Exception) -> Nothing,
    ): PreparedFile {
        repeat(MAX_UNIQUE_NAME_ATTEMPTS) {
            val name = "$PREPARED_PREFIX${UUID.randomUUID()}.tmp"
            val descriptorValue = api.openat(
                parent.value,
                name,
                platform.createExclusiveFileFlags,
                FILE_MODE,
            )
            if (descriptorValue < 0) {
                val errno = Native.getLastError()
                if (errno == platform.alreadyExistsErrno) return@repeat
                onUntrackedPreparationFailure(nativeFailure("openat-prepared", target, name, errno))
            }

            val descriptor = NativeDescriptor(api, descriptorValue)
            val status = try {
                descriptorStatus(api, platform, descriptor.value, target)
            } catch (exception: Exception) {
                descriptor.close()
                onUntrackedPreparationFailure(
                    UnsafeWorkspaceMutationException(
                        message = "The prepared workspace entry could not be identity-validated for safe cleanup",
                        details = failureDetails(target, "fstat-prepared") + mapOf(
                            "recoveryFilePath" to target.parent.resolve(name).toString(),
                            "cause" to (exception.message ?: exception::class.java.simpleName),
                        ),
                    ),
                )
            }
            val prepared = PreparedFile(name, descriptor, status)
            try {
                requireSuccess(api.fchmod(descriptor.value, mode), "fchmod-prepared", target, name)
                writeFully(api, descriptor.value, content.toByteArray(StandardCharsets.UTF_8), target)
                requireSuccess(api.fsync(descriptor.value), "fsync-prepared", target, name)
                return prepared
            } catch (exception: Exception) {
                onPreparationFailure(prepared, exception)
            }
        }
        onUntrackedPreparationFailure(
            UnsafeWorkspaceMutationException(
                message = "Secure workspace mutation could not allocate a unique prepared entry",
                details = failureDetails(target, "allocate-prepared-name"),
            ),
        )
    }

internal fun SecureWorkspaceMutation.rollbackDetachedFailure(
        message: String,
        target: Path,
        fileName: String,
        detached: DetachedTarget,
        parent: NativeDescriptor,
        platform: PosixPlatform,
        cause: Exception,
    ): Nothing {
        val restoration = try {
            restoreDetached(parent, fileName, detached, target, platform)
        } catch (restorationFailure: Exception) {
            throw UnsafeWorkspaceMutationException(
                message = "The detached workspace entry could not be restored after a pre-commit failure",
                details = failureDetails(target, "rollback-detached") +
                    mapOf(
                        "cause" to cause.failureReason(),
                        "restorationFailure" to restorationFailure.failureReason(),
                        "recoveryFilePath" to target.parent.resolve(detached.name).toString(),
                    ) +
                    detached.status.identity.details(),
            )
        }
        throw conflictWithRestoration(
            message = message,
            target = target,
            fileName = fileName,
            detached = detached,
            parent = parent,
            platform = platform,
            details = cause.rollbackDetails(),
            restoration = restoration,
        )
    }

internal fun SecureWorkspaceMutation.rollbackPreparedFailure(
        message: String,
        target: Path,
        fileName: String,
        detached: DetachedTarget,
        prepared: PreparedFile,
        parent: NativeDescriptor,
        api: PosixFileApi,
        platform: PosixPlatform,
        cause: Exception? = null,
    ): Nothing {
        val restorationAttempt = runCatching {
            restoreDetached(parent, fileName, detached, target, platform)
        }
        val cleanup = try {
            removeExactNamedEntry(parent, prepared, target, api, platform)
        } finally {
            prepared.close()
        }
        val restoration = restorationAttempt.getOrElse { restorationFailure ->
            throw UnsafeWorkspaceMutationException(
                message = "The detached workspace entry could not be restored after a prepared commit failure",
                details = failureDetails(target, "rollback-prepared") +
                    mapOf(
                        "cause" to (cause?.failureReason() ?: message),
                        "restorationFailure" to restorationFailure.failureReason(),
                        "recoveryFilePath" to target.parent.resolve(detached.name).toString(),
                    ) +
                    cleanup.conflictDetails() +
                    detached.status.identity.details(),
            )
        }
        throw conflictWithRestoration(
            message = message,
            target = target,
            fileName = fileName,
            detached = detached,
            parent = parent,
            platform = platform,
            details = cause?.rollbackDetails().orEmpty(),
            restoration = restoration,
            cleanup = cleanup,
        )
    }
