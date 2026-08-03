package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationScratchSet
import java.nio.file.Path
import io.github.amichne.kast.idea.*

internal fun SecureWorkspaceMutation.replaceFileExact(
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
                    exception.rethrowIfParentDirectoryDurabilityFailed()
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
