package io.github.amichne.kast.idea.mutation

import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationScratchSet
import java.nio.file.Path
import io.github.amichne.kast.idea.*

internal fun SecureWorkspaceMutation.createFileExact(
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
                exception.rethrowIfParentDirectoryDurabilityFailed()
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
