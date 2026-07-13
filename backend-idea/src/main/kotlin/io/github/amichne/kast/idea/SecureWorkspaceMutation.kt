package io.github.amichne.kast.idea

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.NotFoundException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.FileHashing
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

/**
 * Performs workspace mutations relative to held POSIX directory descriptors.
 *
 * The walk starts at the filesystem root and refuses symlinks for every
 * component. Once a directory is open, later symlink replacement cannot
 * redirect resolution away from that held directory identity. Existing
 * targets are detached before descriptor validation; final-name commits and
 * restoration use no-replace namespace operations. Best-effort cleanup moves
 * entries behind randomized internal names and verifies their device/inode
 * identity immediately before unlinking. Deliberate races against those
 * internal names are outside this boundary; a cleanup failure retains and
 * reports a recovery path instead of hiding a committed mutation.
 */
internal class SecureWorkspaceMutation(
    workspaceRoot: Path,
    private val afterTargetDetached: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    private val beforePreparedFileCreation: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    private val beforeFinalCommit: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    private val beforeNoReplaceRename: (Path, SecureWorkspaceRenamePhase) -> Unit = { _, _ -> },
    private val afterDeleteReservationCommitted: (Path) -> Unit = {},
    private val beforeCleanupUnlink: (Path) -> Unit = {},
) {
    private val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()

    fun createFile(target: Path, content: String): SecureWorkspaceMutationResult {
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.CREATE_FILE)
        return withParentDescriptor(normalizedTarget, createParents = true) { parent, fileName, api, platform ->
            val prepared = createPreparedFile(
                parent = parent,
                target = normalizedTarget,
                content = content,
                mode = CREATED_FILE_MODE,
                api = api,
                platform = platform,
                onUntrackedPreparationFailure = { exception -> throw exception },
                onPreparationFailure = { failedPrepared, exception ->
                    val cleanup = removeExactNamedEntry(parent, failedPrepared, normalizedTarget, api, platform)
                    failedPrepared.close()
                    if (cleanup is CleanupResult.Retained) {
                        throw preCommitFailure(normalizedTarget, "prepare-file", exception, cleanup)
                    }
                    throw exception
                },
            )
            prepared.use {
                val commitOutcome = try {
                    beforeFinalCommit(normalizedTarget, IdeaWorkspaceMutation.CREATE_FILE)
                    renameNoReplace(
                        parent = parent,
                        sourceName = prepared.name,
                        destinationName = fileName,
                        target = normalizedTarget,
                        platform = platform,
                        phase = SecureWorkspaceRenamePhase.FINAL_COMMIT,
                    )
                } catch (exception: Exception) {
                    val cleanup = removeExactNamedEntry(parent, prepared, normalizedTarget, api, platform)
                    throw preCommitFailure(normalizedTarget, "create-before-commit", exception, cleanup)
                }
                when (commitOutcome) {
                    RenameNoReplaceOutcome.MOVED -> SecureWorkspaceMutationResult.Committed
                    RenameNoReplaceOutcome.DESTINATION_EXISTS -> {
                        val cleanup = removeExactNamedEntry(parent, prepared, normalizedTarget, api, platform)
                        throw ConflictException(
                            message = "The requested file already exists",
                            details = mapOf("filePath" to normalizedTarget.toString()) + cleanup.conflictDetails(),
                        )
                    }

                    RenameNoReplaceOutcome.SOURCE_MISSING -> {
                        val cleanup = removeExactNamedEntry(parent, prepared, normalizedTarget, api, platform)
                        throw preCommitFailure(
                            target = normalizedTarget,
                            operation = "create-prepared-source-missing",
                            cause = nativeFailure(
                                operation = "create-prepared-source-missing",
                                target = normalizedTarget,
                                component = prepared.name,
                                errno = platform.notFoundErrno,
                            ),
                            cleanup = cleanup,
                        )
                    }
                }
            }
        }
    }

    fun replaceFile(target: Path, expectedDiskHash: String, content: String): SecureWorkspaceMutationResult {
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.TEXT_EDIT)
        return withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
            detachValidatedTarget(
                parent = parent,
                fileName = fileName,
                target = normalizedTarget,
                expectedDiskHash = expectedDiskHash,
                mutation = IdeaWorkspaceMutation.TEXT_EDIT,
                hashConflictMessage = "The file changed at the secure write boundary",
                api = api,
                platform = platform,
            ).use { detached ->
                try {
                    beforePreparedFileCreation(normalizedTarget, IdeaWorkspaceMutation.TEXT_EDIT)
                } catch (exception: Exception) {
                    rollbackDetachedFailure(
                        message = "The secure replacement could not prepare its commit",
                        target = normalizedTarget,
                        fileName = fileName,
                        detached = detached,
                        parent = parent,
                        platform = platform,
                        cause = exception,
                    )
                }
                val prepared = createPreparedFile(
                    parent = parent,
                    target = normalizedTarget,
                    content = content,
                    mode = detached.status.mode.permissionBits,
                    api = api,
                    platform = platform,
                    onUntrackedPreparationFailure = { exception ->
                        rollbackDetachedFailure(
                            message = "The secure replacement could not prepare its commit",
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            parent = parent,
                            platform = platform,
                            cause = exception,
                        )
                    },
                    onPreparationFailure = { failedPrepared, exception ->
                        rollbackPreparedFailure(
                            message = "The secure replacement could not prepare its commit",
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            prepared = failedPrepared,
                            parent = parent,
                            api = api,
                            platform = platform,
                            cause = exception,
                        )
                    },
                )
                prepared.use {
                    val commitOutcome = try {
                        beforeFinalCommit(normalizedTarget, IdeaWorkspaceMutation.TEXT_EDIT)
                        renameNoReplace(
                            parent = parent,
                            sourceName = prepared.name,
                            destinationName = fileName,
                            target = normalizedTarget,
                            platform = platform,
                            phase = SecureWorkspaceRenamePhase.FINAL_COMMIT,
                        )
                    } catch (exception: Exception) {
                        rollbackPreparedFailure(
                            message = "The secure replacement was interrupted before commit",
                            parent = parent,
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            prepared = prepared,
                            api = api,
                            platform = platform,
                            cause = exception,
                        )
                    }
                    when (commitOutcome) {
                        RenameNoReplaceOutcome.MOVED -> Unit
                        RenameNoReplaceOutcome.DESTINATION_EXISTS -> rollbackPreparedFailure(
                            message = "A concurrent file appeared before the secure replacement committed",
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            prepared = prepared,
                            parent = parent,
                            api = api,
                            platform = platform,
                        )

                        RenameNoReplaceOutcome.SOURCE_MISSING -> rollbackPreparedFailure(
                            message = "The prepared replacement disappeared before commit",
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            prepared = prepared,
                            parent = parent,
                            api = api,
                            platform = platform,
                        )
                    }
                }
                val cleanup = removeExactNamedEntry(parent, detached, normalizedTarget, api, platform)
                SecureWorkspaceMutationResult.committed(cleanup.recoveryFilePaths)
            }
        }
    }

    fun deleteFile(target: Path, expectedDiskHash: String): SecureWorkspaceMutationResult {
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.DELETE_FILE)
        return withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
            detachValidatedTarget(
                parent = parent,
                fileName = fileName,
                target = normalizedTarget,
                expectedDiskHash = expectedDiskHash,
                mutation = IdeaWorkspaceMutation.DELETE_FILE,
                hashConflictMessage = "The file changed after the delete plan was created",
                api = api,
                platform = platform,
            ).use { detached ->
                try {
                    beforePreparedFileCreation(normalizedTarget, IdeaWorkspaceMutation.DELETE_FILE)
                } catch (exception: Exception) {
                    rollbackDetachedFailure(
                        message = "The secure deletion could not prepare its reservation",
                        target = normalizedTarget,
                        fileName = fileName,
                        detached = detached,
                        parent = parent,
                        platform = platform,
                        cause = exception,
                    )
                }
                val reservation = createPreparedFile(
                    parent = parent,
                    target = normalizedTarget,
                    content = "",
                    mode = CREATED_FILE_MODE,
                    api = api,
                    platform = platform,
                    onUntrackedPreparationFailure = { exception ->
                        rollbackDetachedFailure(
                            message = "The secure deletion could not prepare its reservation",
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            parent = parent,
                            platform = platform,
                            cause = exception,
                        )
                    },
                    onPreparationFailure = { failedReservation, exception ->
                        rollbackPreparedFailure(
                            message = "The secure deletion could not prepare its reservation",
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            prepared = failedReservation,
                            parent = parent,
                            api = api,
                            platform = platform,
                            cause = exception,
                        )
                    },
                )
                val reservationRelease = reservation.use {
                    val commitOutcome = try {
                        beforeFinalCommit(normalizedTarget, IdeaWorkspaceMutation.DELETE_FILE)
                        renameNoReplace(
                            parent = parent,
                            sourceName = reservation.name,
                            destinationName = fileName,
                            target = normalizedTarget,
                            platform = platform,
                            phase = SecureWorkspaceRenamePhase.FINAL_COMMIT,
                        )
                    } catch (exception: Exception) {
                        rollbackPreparedFailure(
                            message = "The secure deletion was interrupted before commit",
                            parent = parent,
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            prepared = reservation,
                            api = api,
                            platform = platform,
                            cause = exception,
                        )
                    }
                    when (commitOutcome) {
                        RenameNoReplaceOutcome.MOVED -> Unit
                        RenameNoReplaceOutcome.DESTINATION_EXISTS -> rollbackPreparedFailure(
                            message = "A concurrent file appeared before the secure deletion committed",
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            prepared = reservation,
                            parent = parent,
                            api = api,
                            platform = platform,
                        )

                        RenameNoReplaceOutcome.SOURCE_MISSING -> rollbackPreparedFailure(
                            message = "The prepared deletion reservation disappeared before commit",
                            target = normalizedTarget,
                            fileName = fileName,
                            detached = detached,
                            prepared = reservation,
                            parent = parent,
                            api = api,
                            platform = platform,
                        )
                    }

                    afterDeleteReservationCommitted(normalizedTarget)

                    releaseFinalReservation(
                        parent = parent,
                        fileName = fileName,
                        reservation = reservation,
                        target = normalizedTarget,
                        api = api,
                        platform = platform,
                    )
                }
                val reservationCleanup = when (reservationRelease) {
                    is FinalReservationRelease.Released -> reservationRelease.cleanup
                    is FinalReservationRelease.Blocked -> {
                        val concurrentRecoveryDetails = if (reservationRelease.restoredToFinalName) {
                            mapOf("concurrentEntryRestoration" to "restored")
                        } else {
                            mapOf(
                                "concurrentEntryRestoration" to "quarantined",
                                "concurrentEntryRecoveryFilePath" to reservationRelease.entryRecoveryFilePath.toString(),
                            )
                        }
                        throw ConflictException(
                            message = "The deletion reservation was replaced before the final name was released",
                            details = mapOf(
                                "filePath" to normalizedTarget.toString(),
                                "recoveryFilePath" to normalizedTarget.parent.resolve(detached.name).toString(),
                                "cause" to reservationRelease.reason,
                            ) +
                                concurrentRecoveryDetails +
                                detached.status.identity.details(),
                        )
                    }
                }
                val detachedCleanup = removeExactNamedEntry(parent, detached, normalizedTarget, api, platform)
                SecureWorkspaceMutationResult.committed(
                    detachedCleanup.recoveryFilePaths + reservationCleanup.recoveryFilePaths,
                )
            }
        }
    }

    fun verifyCommittedFile(
        target: Path,
        expectedContent: String,
        mutation: IdeaWorkspaceMutation,
    ) {
        val normalizedTarget = requireWorkspaceTarget(target, mutation)
        withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
            val descriptorValue = api.openat(parent.value, fileName, platform.readFileFlags, 0)
            if (descriptorValue < 0) {
                throw nativeFailure(
                    operation = "openat-verify-committed-file",
                    target = normalizedTarget,
                    component = fileName,
                    errno = Native.getLastError(),
                )
            }
            NativeDescriptor(api, descriptorValue).use { descriptor ->
                val status = descriptorStatus(api, platform, descriptor.value, normalizedTarget)
                if (status.mode.fileType != NativeFileType.REGULAR) {
                    throw UnsafeWorkspaceMutationException(
                        message = "Secure post-commit verification requires a regular file target",
                        details = failureDetails(normalizedTarget, "reject-non-regular-verification-target") + mapOf(
                            "fileType" to status.mode.fileType.name,
                            "fileMode" to status.mode.bits.toString(8),
                        ),
                    )
                }
                val actualContent = readFully(api, descriptor.value, normalizedTarget)
                if (actualContent != expectedContent) {
                    throw ConflictException(
                        message = "Secure post-commit content verification failed",
                        details = mapOf(
                            "filePath" to normalizedTarget.toString(),
                            "mutation" to mutation.wireName,
                            "expectedHash" to FileHashing.sha256(expectedContent),
                            "actualHash" to FileHashing.sha256(actualContent),
                        ),
                    )
                }
            }
        }
    }

    fun verifyCommittedDeletion(target: Path) {
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.DELETE_FILE)
        withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
            val descriptorValue = api.openat(parent.value, fileName, platform.readFileFlags, 0)
            if (descriptorValue < 0) {
                val errno = Native.getLastError()
                if (errno == platform.notFoundErrno) return@withParentDescriptor
                throw nativeFailure(
                    operation = "openat-verify-committed-deletion",
                    target = normalizedTarget,
                    component = fileName,
                    errno = errno,
                )
            }
            NativeDescriptor(api, descriptorValue).close()
            throw ValidationException(
                message = "Secure post-commit deletion verification found a final entry",
                details = mapOf(
                    "filePath" to normalizedTarget.toString(),
                    "mutation" to IdeaWorkspaceMutation.DELETE_FILE.wireName,
                ),
            )
        }
    }

    private fun detachValidatedTarget(
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

    private fun createPreparedFile(
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

    private fun rollbackDetachedFailure(
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

    private fun rollbackPreparedFailure(
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

    private fun Exception.rollbackDetails(): Map<String, String> =
        mapOf("cause" to failureReason()) + when (this) {
            is UnsafeWorkspaceMutationException -> details.mapKeys { (key, _) -> "cause.$key" }
            else -> emptyMap()
        }

    private fun conflictWithRestoration(
        message: String,
        target: Path,
        fileName: String,
        detached: DetachedTarget,
        parent: NativeDescriptor,
        platform: PosixPlatform,
        details: Map<String, String> = emptyMap(),
        restoration: Restoration? = null,
        cleanup: CleanupResult = CleanupResult.Removed,
    ): ConflictException {
        val effectiveRestoration = restoration ?: restoreDetached(parent, fileName, detached, target, platform)
        val restorationDetails = when (effectiveRestoration) {
            Restoration.RESTORED -> mapOf("restoration" to "restored")
            Restoration.QUARANTINED -> mapOf(
                "restoration" to "quarantined",
                "recoveryFilePath" to target.parent.resolve(detached.name).toString(),
            )
        }
        return ConflictException(
            message = message,
            details = mapOf("filePath" to target.toString()) +
                details +
                restorationDetails +
                cleanup.conflictDetails() +
                detached.status.identity.details(),
        )
    }

    private fun restoreDetached(
        parent: NativeDescriptor,
        fileName: String,
        detached: DetachedTarget,
        target: Path,
        platform: PosixPlatform,
    ): Restoration = when (
        renameNoReplace(
            parent = parent,
            sourceName = detached.name,
            destinationName = fileName,
            target = target,
            platform = platform,
            phase = SecureWorkspaceRenamePhase.RESTORE_TARGET,
        )
    ) {
        RenameNoReplaceOutcome.MOVED -> {
            Restoration.RESTORED
        }

        RenameNoReplaceOutcome.DESTINATION_EXISTS -> Restoration.QUARANTINED
        RenameNoReplaceOutcome.SOURCE_MISSING -> throw UnsafeWorkspaceMutationException(
            message = "The detached workspace entry disappeared before it could be restored",
            details = failureDetails(target, "restore-detached-source-missing") +
                mapOf("recoveryFilePath" to target.parent.resolve(detached.name).toString()) +
                detached.status.identity.details(),
        )
    }

    private fun restoreUnopenedQuarantine(
        parent: NativeDescriptor,
        fileName: String,
        quarantineName: String,
        target: Path,
        platform: PosixPlatform,
    ) {
        when (
            renameNoReplace(
                parent = parent,
                sourceName = quarantineName,
                destinationName = fileName,
                target = target,
                platform = platform,
                phase = SecureWorkspaceRenamePhase.RESTORE_TARGET,
            )
        ) {
            RenameNoReplaceOutcome.MOVED -> Unit
            RenameNoReplaceOutcome.DESTINATION_EXISTS -> throw UnsafeWorkspaceMutationException(
                message = "The detached workspace entry could not be restored because the target name is occupied",
                details = failureDetails(target, "restore-unopened-quarantine") + mapOf(
                    "recoveryFilePath" to target.parent.resolve(quarantineName).toString(),
                ),
            )

            RenameNoReplaceOutcome.SOURCE_MISSING -> throw UnsafeWorkspaceMutationException(
                message = "The detached workspace entry disappeared before it could be opened",
                details = failureDetails(target, "restore-unopened-quarantine-source-missing"),
            )
        }
    }

    private fun removeExactNamedEntry(
        parent: NativeDescriptor,
        entry: ExactNamedEntry,
        target: Path,
        api: PosixFileApi,
        platform: PosixPlatform,
    ): CleanupResult = removeExactName(
        parent = parent,
        sourceName = entry.name,
        expectedIdentity = entry.status.identity,
        target = target,
        api = api,
        platform = platform,
    )

    private fun releaseFinalReservation(
        parent: NativeDescriptor,
        fileName: String,
        reservation: PreparedFile,
        target: Path,
        api: PosixFileApi,
        platform: PosixPlatform,
    ): FinalReservationRelease {
        val targetPath = target.parent.resolve(fileName)
        val cleanupName = try {
            moveToUniqueName(
                parent = parent,
                sourceName = fileName,
                prefix = CLEANUP_PREFIX,
                target = target,
                platform = platform,
                phase = SecureWorkspaceRenamePhase.MOVE_CLEANUP,
                sourceMissing = {
                    throw UnsafeWorkspaceMutationException(
                        message = "The deletion reservation disappeared before final-name release",
                        details = failureDetails(target, "delete-reservation-source-missing"),
                    )
                },
            )
        } catch (exception: Exception) {
            return FinalReservationRelease.Blocked(
                entryRecoveryFilePath = targetPath,
                restoredToFinalName = true,
                reason = exception.failureReason(),
            )
        }
        val cleanupPath = target.parent.resolve(cleanupName)
        fun blocked(reason: String): FinalReservationRelease.Blocked {
            val recoveryPath = restoreCleanupName(parent, fileName, cleanupName, target, platform)
            return FinalReservationRelease.Blocked(
                entryRecoveryFilePath = recoveryPath,
                restoredToFinalName = recoveryPath == targetPath,
                reason = reason,
            )
        }
        fun releasedWithRecovery(reason: String): FinalReservationRelease.Released =
            FinalReservationRelease.Released(
                CleanupResult.Retained(
                    recoveryFilePath = cleanupPath,
                    reason = reason,
                ),
            )

        val cleanupDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (cleanupDescriptorValue < 0) {
            return blocked("openat-delete-reservation failed with errno ${Native.getLastError()}")
        }
        NativeDescriptor(api, cleanupDescriptorValue).use { cleanupDescriptor ->
            val cleanupStatus = try {
                descriptorStatus(api, platform, cleanupDescriptor.value, target)
            } catch (exception: Exception) {
                return blocked(exception.failureReason())
            }
            if (cleanupStatus.identity != reservation.status.identity) {
                return blocked("delete reservation identity changed before final-name release")
            }
        }

        try {
            beforeCleanupUnlink(cleanupPath)
        } catch (exception: Exception) {
            return releasedWithRecovery(exception.failureReason())
        }

        val finalDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (finalDescriptorValue < 0) {
            return blocked("openat-delete-reservation-recheck failed with errno ${Native.getLastError()}")
        }
        NativeDescriptor(api, finalDescriptorValue).use { finalDescriptor ->
            val finalStatus = try {
                descriptorStatus(api, platform, finalDescriptor.value, target)
            } catch (exception: Exception) {
                return blocked(exception.failureReason())
            }
            if (finalStatus.identity != reservation.status.identity) {
                return blocked("delete reservation identity changed immediately before unlink")
            }
        }

        if (api.unlinkat(parent.value, cleanupName, 0) < 0) {
            return releasedWithRecovery("unlinkat-delete-reservation failed with errno ${Native.getLastError()}")
        }
        return FinalReservationRelease.Released(CleanupResult.Removed)
    }

    private fun removeExactName(
        parent: NativeDescriptor,
        sourceName: String,
        expectedIdentity: NativeFileIdentity,
        target: Path,
        api: PosixFileApi,
        platform: PosixPlatform,
    ): CleanupResult {
        val sourcePath = target.parent.resolve(sourceName)
        val cleanupName = try {
            moveToUniqueName(
                parent = parent,
                sourceName = sourceName,
                prefix = CLEANUP_PREFIX,
                target = target,
                platform = platform,
                phase = SecureWorkspaceRenamePhase.MOVE_CLEANUP,
                sourceMissing = {
                    throw UnsafeWorkspaceMutationException(
                        message = "The descriptor-identified workspace entry disappeared before cleanup",
                        details = failureDetails(target, "cleanup-source-missing") + expectedIdentity.details(),
                    )
                },
            )
        } catch (exception: Exception) {
            return CleanupResult.Retained(
                recoveryFilePath = sourcePath,
                reason = exception.failureReason(),
            )
        }
        val cleanupPath = target.parent.resolve(cleanupName)
        fun retained(reason: String): CleanupResult.Retained = CleanupResult.Retained(
            recoveryFilePath = restoreCleanupName(parent, sourceName, cleanupName, target, platform),
            reason = reason,
        )

        val cleanupDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (cleanupDescriptorValue < 0) {
            val errno = Native.getLastError()
            return retained("openat-cleanup failed with errno $errno")
        }
        NativeDescriptor(api, cleanupDescriptorValue).use { cleanupDescriptor ->
            val cleanupStatus = try {
                descriptorStatus(api, platform, cleanupDescriptor.value, target)
            } catch (exception: Exception) {
                return retained(exception.failureReason())
            }
            if (cleanupStatus.identity != expectedIdentity) {
                return retained("cleanup identity did not match the descriptor-validated entry")
            }
        }

        try {
            beforeCleanupUnlink(cleanupPath)
        } catch (exception: Exception) {
            return retained(exception.failureReason())
        }

        val finalDescriptorValue = api.openat(parent.value, cleanupName, platform.readFileFlags, 0)
        if (finalDescriptorValue < 0) {
            val errno = Native.getLastError()
            return retained("openat-cleanup-recheck failed with errno $errno")
        }
        NativeDescriptor(api, finalDescriptorValue).use { finalDescriptor ->
            val finalStatus = try {
                descriptorStatus(api, platform, finalDescriptor.value, target)
            } catch (exception: Exception) {
                return retained(exception.failureReason())
            }
            if (finalStatus.identity != expectedIdentity) {
                return retained("cleanup identity changed before unlink")
            }
        }

        if (api.unlinkat(parent.value, cleanupName, 0) < 0) {
            val errno = Native.getLastError()
            return retained("unlinkat-cleanup failed with errno $errno")
        }
        return CleanupResult.Removed
    }

    private fun restoreCleanupName(
        parent: NativeDescriptor,
        sourceName: String,
        cleanupName: String,
        target: Path,
        platform: PosixPlatform,
    ): Path {
        val sourcePath = target.parent.resolve(sourceName)
        val cleanupPath = target.parent.resolve(cleanupName)
        return try {
            when (
                renameNoReplace(
                    parent = parent,
                    sourceName = cleanupName,
                    destinationName = sourceName,
                    target = target,
                    platform = platform,
                    phase = SecureWorkspaceRenamePhase.RESTORE_CLEANUP,
                )
            ) {
                RenameNoReplaceOutcome.MOVED -> sourcePath
                RenameNoReplaceOutcome.DESTINATION_EXISTS, RenameNoReplaceOutcome.SOURCE_MISSING -> cleanupPath
            }
        } catch (_: Exception) {
            cleanupPath
        }
    }

    private fun preCommitFailure(
        target: Path,
        operation: String,
        cause: Exception,
        cleanup: CleanupResult,
    ): UnsafeWorkspaceMutationException = UnsafeWorkspaceMutationException(
        message = "Secure workspace mutation failed before commit",
        details = failureDetails(target, operation) +
            mapOf("cause" to cause.failureReason()) +
            cleanup.conflictDetails(),
    )

    private fun Throwable.failureReason(): String = message ?: this::class.java.simpleName

    private fun moveToUniqueName(
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

    private fun renameNoReplace(
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

    private fun descriptorStatus(
        api: PosixFileApi,
        platform: PosixPlatform,
        descriptor: Int,
        target: Path,
    ): NativeFileStatus {
        val status = Memory(STAT_BUFFER_SIZE)
        requireSuccess(api.fstat(descriptor, status), "fstat", target, target.fileName.toString())
        return platform.readStatus(status)
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

        fun read(descriptor: Int, buffer: com.sun.jna.Pointer, count: NativeLong): NativeLong

        fun write(descriptor: Int, buffer: com.sun.jna.Pointer, count: NativeLong): NativeLong

        fun fsync(descriptor: Int): Int

        fun fchmod(descriptor: Int, mode: Int): Int

        fun fstat(descriptor: Int, status: com.sun.jna.Pointer): Int

        fun close(descriptor: Int): Int
    }

    private interface MacRenameApi : Library {
        @Suppress("FunctionName")
        fun renameatx_np(
            oldDirectoryDescriptor: Int,
            oldPath: String,
            newDirectoryDescriptor: Int,
            newPath: String,
            flags: Int,
        ): Int
    }

    private interface LinuxRenameApi : Library {
        fun renameat2(
            oldDirectoryDescriptor: Int,
            oldPath: String,
            newDirectoryDescriptor: Int,
            newPath: String,
            flags: Int,
        ): Int
    }

    private data class PosixPlatform(
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

    private enum class NativeScalarEncoding {
        UNSIGNED_SHORT,
        INT,
        LONG,
    }

    private enum class RenamePrimitive {
        MAC_RENAMEATX,
        LINUX_RENAMEAT2,
    }

    private enum class RenameNoReplaceOutcome {
        MOVED,
        DESTINATION_EXISTS,
        SOURCE_MISSING,
    }

    private enum class Restoration {
        RESTORED,
        QUARANTINED,
    }

    private sealed interface CleanupResult {
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

    private sealed interface FinalReservationRelease {
        data class Released(val cleanup: CleanupResult) : FinalReservationRelease

        data class Blocked(
            val entryRecoveryFilePath: Path,
            val restoredToFinalName: Boolean,
            val reason: String,
        ) : FinalReservationRelease
    }

    private data class NativeFileIdentity(
        val device: Long,
        val inode: Long,
    ) {
        fun details(prefix: String = "detached"): Map<String, String> = mapOf(
            "${prefix}Device" to device.toULong().toString(),
            "${prefix}Inode" to inode.toULong().toString(),
        )
    }

    private data class NativeFileMode(
        val bits: Int,
        val fileType: NativeFileType,
    ) {
        val permissionBits: Int = bits and PERMISSION_BITS
    }

    private enum class NativeFileType {
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

    private data class NativeFileStatus(
        val mode: NativeFileMode,
        val identity: NativeFileIdentity,
    )

    private interface ExactNamedEntry : AutoCloseable {
        val name: String
        val status: NativeFileStatus
    }

    private class DetachedTarget(
        override val name: String,
        private val descriptor: NativeDescriptor,
        override val status: NativeFileStatus,
        val actualDiskHash: String,
    ) : ExactNamedEntry {
        override fun close() {
            descriptor.close()
        }
    }

    private class PreparedFile(
        override val name: String,
        private val descriptor: NativeDescriptor,
        override val status: NativeFileStatus,
    ) : ExactNamedEntry {
        override fun close() {
            descriptor.close()
        }
    }

    private companion object {
        const val FILE_MODE = 438 // 0666; the process umask still applies.
        const val DIRECTORY_MODE = 511 // 0777; the process umask still applies.
        const val CREATED_FILE_MODE = 420 // 0644; deterministic IDEA-compatible source permissions.
        const val CREATED_DIRECTORY_MODE = 493 // 0755; deterministic IDEA-compatible directory permissions.
        const val BUFFER_SIZE = 8192
        const val STAT_BUFFER_SIZE = 256L
        const val PERMISSION_BITS = 4095 // 07777
        const val FILE_TYPE_BITS = 61440 // 0170000
        const val FIFO_MODE = 4096 // 0010000
        const val CHARACTER_DEVICE_MODE = 8192 // 0020000
        const val DIRECTORY_MODE_BITS = 16384 // 0040000
        const val BLOCK_DEVICE_MODE = 24576 // 0060000
        const val REGULAR_FILE_MODE = 32768 // 0100000
        const val SYMBOLIC_LINK_MODE = 40960 // 0120000
        const val SOCKET_MODE = 49152 // 0140000
        const val MAX_UNIQUE_NAME_ATTEMPTS = 8

        const val QUARANTINE_PREFIX = ".kast-quarantine-"
        const val PREPARED_PREFIX = ".kast-prepared-"
        const val CLEANUP_PREFIX = ".kast-cleanup-"

        const val MAC_RENAME_EXCLUSIVE = 0x00000004
        const val MAC_RENAME_NOFOLLOW_ANY = 0x00000010
        const val LINUX_RENAME_NOREPLACE = 0x00000001

        const val MAC_OPEN_WRITE_ONLY = 0x0001
        const val MAC_OPEN_NONBLOCK = 0x0004
        const val MAC_OPEN_CREATE = 0x0200
        const val MAC_OPEN_EXCLUSIVE = 0x0800
        const val MAC_OPEN_NOFOLLOW = 0x0100
        const val MAC_OPEN_DIRECTORY = 0x100000
        const val MAC_OPEN_CLOEXEC = 0x1000000

        const val LINUX_OPEN_WRITE_ONLY = 0x0001
        const val LINUX_OPEN_NONBLOCK = 0x0800
        const val LINUX_OPEN_CREATE = 0x0040
        const val LINUX_OPEN_EXCLUSIVE = 0x0080
        const val LINUX_OPEN_DIRECTORY = 0x10000
        const val LINUX_OPEN_NOFOLLOW = 0x20000
        const val LINUX_OPEN_CLOEXEC = 0x80000

        val api: PosixFileApi by lazy {
            Native.load(Platform.C_LIBRARY_NAME, PosixFileApi::class.java)
        }

        val macRenameApi: MacRenameApi by lazy {
            Native.load(Platform.C_LIBRARY_NAME, MacRenameApi::class.java)
        }

        val linuxRenameApi: LinuxRenameApi by lazy {
            Native.load(Platform.C_LIBRARY_NAME, LinuxRenameApi::class.java)
        }
    }
}
