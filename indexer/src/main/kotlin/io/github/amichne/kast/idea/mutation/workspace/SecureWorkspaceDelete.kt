package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationScratchSet
import java.nio.file.Path
import io.github.amichne.kast.idea.*

internal fun SecureWorkspaceMutation.deleteFileExact(
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
                    exception.rethrowIfParentDirectoryDurabilityFailed()
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
