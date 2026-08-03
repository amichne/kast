package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationScratchSet
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

    fun createFile(target: Path, content: String): SecureWorkspaceMutationResult =
        createFile(target, strictUtf8Bytes(content), createParents = true)

    fun createFile(target: Path, content: ByteArray): SecureWorkspaceMutationResult =
        createFile(target, content, createParents = true)

    fun createFileRequiringExistingParents(target: Path, content: String): SecureWorkspaceMutationResult =
        createFile(target, strictUtf8Bytes(content), createParents = false)

    fun createFileRequiringExistingParents(target: Path, content: ByteArray): SecureWorkspaceMutationResult =
        createFile(target, content, createParents = false)

    fun createFileRequiringExistingParents(
        target: Path,
        content: String,
        scratch: ParsedMutationScratchSet,
    ): SecureWorkspaceMutationResult = createFile(
        target,
        strictUtf8Bytes(content),
        createParents = false,
        scratch = scratch,
    )

    fun createFileRequiringExistingParents(
        target: Path,
        content: ByteArray,
        scratch: ParsedMutationScratchSet,
    ): SecureWorkspaceMutationResult = createFile(target, content, createParents = false, scratch = scratch)

    private fun createFile(
        target: Path,
        content: ByteArray,
        createParents: Boolean,
        scratch: ParsedMutationScratchSet? = null,
    ): SecureWorkspaceMutationResult {
        val exactContent = content.copyOf()
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.CREATE_FILE)
        val scratchNames = requireScratchNames(normalizedTarget, scratch, IdeaWorkspaceMutation.CREATE_FILE)
        return withParentDescriptor(normalizedTarget, createParents = createParents) { parent, fileName, api, platform ->
            requireScratchEntriesAbsent(parent, normalizedTarget, scratchNames, api, platform)
            val prepared = createPreparedFile(
                parent = parent,
                target = normalizedTarget,
                content = exactContent,
                mode = CREATED_FILE_MODE,
                api = api,
                platform = platform,
                preparedName = scratchNames?.preparedName,
                onUntrackedPreparationFailure = { exception -> throw exception },
                onPreparationFailure = { failedPrepared, exception ->
                    val cleanup = removeExactNamedEntry(
                        parent,
                        failedPrepared,
                        normalizedTarget,
                        api,
                        platform,
                        cleanupName = scratchNames?.preparedCleanupName,
                    )
                    failedPrepared.close()
                    val evidence = preCommitFailure(normalizedTarget, "prepare-file", exception, cleanup)
                    exception.rethrowIfMutationCancellation(evidence)
                    cleanup.rethrowIfMutationCancellation(evidence)
                    if (cleanup is CleanupResult.Retained) {
                        throw evidence
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
                    val cleanup = removeExactNamedEntry(
                        parent,
                        prepared,
                        normalizedTarget,
                        api,
                        platform,
                        cleanupName = scratchNames?.preparedCleanupName,
                    )
                    val evidence = preCommitFailure(normalizedTarget, "create-before-commit", exception, cleanup)
                    exception.rethrowIfMutationCancellation(evidence)
                    cleanup.rethrowIfMutationCancellation(evidence)
                    throw evidence
                }
                when (commitOutcome) {
                    RenameNoReplaceOutcome.MOVED -> SecureWorkspaceMutationResult.Committed
                    RenameNoReplaceOutcome.DESTINATION_EXISTS -> {
                        val cleanup = removeExactNamedEntry(
                            parent,
                            prepared,
                            normalizedTarget,
                            api,
                            platform,
                            cleanupName = scratchNames?.preparedCleanupName,
                        )
                        val evidence = ConflictException(
                            message = "The requested file already exists",
                            details = mapOf("filePath" to normalizedTarget.toString()) + cleanup.conflictDetails(),
                        )
                        cleanup.rethrowIfMutationCancellation(evidence)
                        throw evidence
                    }

                    RenameNoReplaceOutcome.SOURCE_MISSING -> {
                        val cleanup = removeExactNamedEntry(
                            parent,
                            prepared,
                            normalizedTarget,
                            api,
                            platform,
                            cleanupName = scratchNames?.preparedCleanupName,
                        )
                        val evidence = preCommitFailure(
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
                        cleanup.rethrowIfMutationCancellation(evidence)
                        throw evidence
                    }
                }
            }.requireOwnedRecoverySubset(scratchNames)
        }
    }

    fun replaceFile(target: Path, expectedDiskHash: String, content: String): SecureWorkspaceMutationResult =
        replaceFile(target, expectedDiskHash, strictUtf8Bytes(content))

    fun replaceFile(
        target: Path,
        expectedDiskHash: String,
        content: ByteArray,
        scratch: ParsedMutationScratchSet? = null,
    ): SecureWorkspaceMutationResult {
        val exactContent = content.copyOf()
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.TEXT_EDIT)
        val scratchNames = requireScratchNames(normalizedTarget, scratch, IdeaWorkspaceMutation.TEXT_EDIT)
        return withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
            requireScratchEntriesAbsent(parent, normalizedTarget, scratchNames, api, platform)
            detachValidatedTarget(
                parent = parent,
                fileName = fileName,
                target = normalizedTarget,
                expectedDiskHash = expectedDiskHash,
                mutation = IdeaWorkspaceMutation.TEXT_EDIT,
                hashConflictMessage = "The file changed at the secure write boundary",
                api = api,
                platform = platform,
                quarantineName = scratchNames?.quarantineName,
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
                    content = exactContent,
                    mode = detached.status.mode.permissionBits,
                    api = api,
                    platform = platform,
                    preparedName = scratchNames?.preparedName,
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
                            preparedCleanupName = scratchNames?.preparedCleanupName,
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
                            preparedCleanupName = scratchNames?.preparedCleanupName,
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
                            preparedCleanupName = scratchNames?.preparedCleanupName,
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
                            preparedCleanupName = scratchNames?.preparedCleanupName,
                        )
                    }
                }
                val cleanup = removeExactNamedEntry(
                    parent,
                    detached,
                    normalizedTarget,
                    api,
                    platform,
                    cleanupName = scratchNames?.quarantineCleanupName,
                )
                rethrowCommittedCancellation(cleanup, normalizedTarget, IdeaWorkspaceMutation.TEXT_EDIT)
                SecureWorkspaceMutationResult.committed(cleanup.recoveryFilePaths)
                    .requireOwnedRecoverySubset(scratchNames)
            }
        }
    }

    fun deleteFile(
        target: Path,
        expectedDiskHash: String,
        scratch: ParsedMutationScratchSet? = null,
    ): SecureWorkspaceMutationResult {
        val normalizedTarget = requireWorkspaceTarget(target, IdeaWorkspaceMutation.DELETE_FILE)
        val scratchNames = requireScratchNames(normalizedTarget, scratch, IdeaWorkspaceMutation.DELETE_FILE)
        return withParentDescriptor(normalizedTarget, createParents = false) { parent, fileName, api, platform ->
            requireScratchEntriesAbsent(parent, normalizedTarget, scratchNames, api, platform)
            detachValidatedTarget(
                parent = parent,
                fileName = fileName,
                target = normalizedTarget,
                expectedDiskHash = expectedDiskHash,
                mutation = IdeaWorkspaceMutation.DELETE_FILE,
                hashConflictMessage = "The file changed after the delete plan was created",
                api = api,
                platform = platform,
                quarantineName = scratchNames?.quarantineName,
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
                    content = byteArrayOf(),
                    mode = CREATED_FILE_MODE,
                    api = api,
                    platform = platform,
                    preparedName = scratchNames?.preparedName,
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
                            preparedCleanupName = scratchNames?.preparedCleanupName,
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
                            preparedCleanupName = scratchNames?.preparedCleanupName,
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
                            preparedCleanupName = scratchNames?.preparedCleanupName,
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
                            preparedCleanupName = scratchNames?.preparedCleanupName,
                        )
                    }

                    try {
                        afterDeleteReservationCommitted(normalizedTarget)
                    } catch (exception: Exception) {
                        val recoveryPaths = listOf(
                            normalizedTarget,
                            normalizedTarget.parent.resolve(detached.name),
                        ).distinct().sorted()
                        exception.rethrowIfMutationCancellation(
                            UnsafeWorkspaceMutationException(
                                message = "Secure deletion retained reservation and preimage recovery evidence after cancellation",
                                details = buildMap {
                                    putAll(failureDetails(normalizedTarget, "delete-reservation-committed-cancellation"))
                                    put("committed", "true")
                                    put("mutation", IdeaWorkspaceMutation.DELETE_FILE.wireName)
                                    put("recoveryFilePathCount", recoveryPaths.size.toString())
                                    recoveryPaths.forEachIndexed { index, path ->
                                        put("recoveryFilePath.$index", path.toString())
                                    }
                                },
                            ),
                        )
                        throw exception
                    }

                    releaseFinalReservation(
                        parent = parent,
                        fileName = fileName,
                        reservation = reservation,
                        target = normalizedTarget,
                        api = api,
                        platform = platform,
                        exactCleanupName = scratchNames?.preparedCleanupName,
                    )
                }
                val reservationCleanup = when (reservationRelease) {
                    is FinalReservationRelease.Released -> reservationRelease.cleanup
                    is FinalReservationRelease.Cancelled -> {
                        val cancellation = reservationRelease.cancellation
                        cancellation.addSuppressed(
                            UnsafeWorkspaceMutationException(
                                message = "Secure deletion retained both reservation and preimage recovery evidence after cancellation",
                                details = buildMap {
                                    putAll(failureDetails(normalizedTarget, "delete-reservation-cancellation"))
                                    val paths = listOf(
                                        reservationRelease.entryRecoveryFilePath,
                                        normalizedTarget.parent.resolve(detached.name),
                                    ).distinct().sorted()
                                    put("committed", "true")
                                    put("mutation", IdeaWorkspaceMutation.DELETE_FILE.wireName)
                                    put("recoveryFilePathCount", paths.size.toString())
                                    paths.forEachIndexed { index, path ->
                                        put("recoveryFilePath.$index", path.toString())
                                    }
                                },
                            ),
                        )
                        throw cancellation
                    }
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
                val detachedCleanup = removeExactNamedEntry(
                    parent,
                    detached,
                    normalizedTarget,
                    api,
                    platform,
                    cleanupName = scratchNames?.quarantineCleanupName,
                )
                rethrowCommittedCancellation(
                    listOf(reservationCleanup, detachedCleanup),
                    normalizedTarget,
                    IdeaWorkspaceMutation.DELETE_FILE,
                )
                SecureWorkspaceMutationResult.committed(
                    detachedCleanup.recoveryFilePaths + reservationCleanup.recoveryFilePaths,
                ).requireOwnedRecoverySubset(scratchNames)
            }
        }
    }

    fun verifyCommittedFile(
        target: Path,
        expectedContent: String,
        mutation: IdeaWorkspaceMutation,
    ) = verifyCommittedFile(target, strictUtf8Bytes(expectedContent), mutation)

    fun verifyCommittedFile(
        target: Path,
        expectedContent: ByteArray,
        mutation: IdeaWorkspaceMutation,
    ) = verifyCommittedFileState(target, expectedContent.copyOf(), mutation)

    fun readFileBytes(target: Path, mutation: IdeaWorkspaceMutation): ByteArray =
        readFileBytesState(target, mutation)

    fun observeExactFile(
        target: Path,
        mutation: IdeaWorkspaceMutation,
    ): SecureWorkspaceFileObservation = observeExactFileState(target, mutation)

    fun currentFileSha256(target: Path, mutation: IdeaWorkspaceMutation): String =
        FileHashing.sha256(readFileBytes(target, mutation))

    fun verifyCommittedDeletion(target: Path) = verifyCommittedDeletionState(target)

}
