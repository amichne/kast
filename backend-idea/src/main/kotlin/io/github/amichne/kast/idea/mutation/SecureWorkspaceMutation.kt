package io.github.amichne.kast.idea.mutation

import com.sun.jna.Native
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.FileHashing
import java.nio.file.Path
import io.github.amichne.kast.idea.*

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
    internal val afterTargetDetached: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    internal val beforePreparedFileCreation: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    internal val beforeFinalCommit: (Path, IdeaWorkspaceMutation) -> Unit = { _, _ -> },
    internal val beforeNoReplaceRename: (Path, SecureWorkspaceRenamePhase) -> Unit = { _, _ -> },
    internal val afterDeleteReservationCommitted: (Path) -> Unit = {},
    internal val beforeCleanupUnlink: (Path) -> Unit = {},
) {
    internal val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()

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

}
