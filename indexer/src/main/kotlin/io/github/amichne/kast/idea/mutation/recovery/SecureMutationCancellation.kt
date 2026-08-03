package io.github.amichne.kast.idea.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.sun.jna.Native
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.contract.ExactFileImageSha256
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import io.github.amichne.kast.idea.*


internal fun SecureWorkspaceMutation.rethrowCommittedCancellation(
    cleanup: CleanupResult,
    target: Path,
    mutation: IdeaWorkspaceMutation,
) = rethrowCommittedCancellation(listOf(cleanup), target, mutation)

internal fun SecureWorkspaceMutation.rethrowCommittedCancellation(
    cleanups: List<CleanupResult>,
    target: Path,
    mutation: IdeaWorkspaceMutation,
) {
    val cancelled = cleanups.filterIsInstance<CleanupResult.Cancelled>()
    if (cancelled.isEmpty()) return
    val recoveryPaths = cleanups.flatMap(CleanupResult::recoveryFilePaths).distinct().sorted()
    val cancellation = cancelled.first().cancellation
    cancellation.addSuppressed(
        UnsafeWorkspaceMutationException(
            message = "Secure workspace mutation retained recovery evidence after cancellation",
            details = buildMap {
                putAll(failureDetails(target, "post-commit-cancellation"))
                putAll(
                    mapOf(
                    "committed" to "true",
                    "mutation" to mutation.wireName,
                    ),
                )
                put("recoveryFilePathCount", recoveryPaths.size.toString())
                recoveryPaths.forEachIndexed { index, path -> put("recoveryFilePath.$index", path.toString()) }
            },
        ),
    )
    throw cancellation
}

internal fun retainedCleanup(
    recoveryFilePath: Path,
    failure: Exception,
): CleanupResult = when (failure) {
    is ProcessCanceledException -> CleanupResult.Cancelled.of(recoveryFilePath, failure)
    is CancellationException -> CleanupResult.Cancelled.of(recoveryFilePath, failure)
    else -> CleanupResult.Retained(recoveryFilePath, failure.failureReason())
}

internal sealed interface CleanupNameRestoration {
    val recoveryFilePath: Path

    data class Restored(
        override val recoveryFilePath: Path,
    ) : CleanupNameRestoration

    data class Retained(
        override val recoveryFilePath: Path,
        val failure: Exception,
    ) : CleanupNameRestoration
}

internal fun SecureWorkspaceMutation.restoreCleanupName(
        parent: NativeDescriptor,
        sourceName: String,
        cleanupName: String,
        target: Path,
        platform: PosixPlatform,
    ): CleanupNameRestoration {
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
                RenameNoReplaceOutcome.MOVED -> CleanupNameRestoration.Restored(sourcePath)
                RenameNoReplaceOutcome.DESTINATION_EXISTS -> CleanupNameRestoration.Retained(
                    cleanupPath,
                    UnsafeWorkspaceMutationException(
                        message = "Secure workspace cleanup restoration found an occupied source name",
                        details = failureDetails(target, "restore-cleanup-destination-exists") +
                            mapOf("recoveryFilePath" to cleanupPath.toString()),
                    ),
                )
                RenameNoReplaceOutcome.SOURCE_MISSING -> CleanupNameRestoration.Retained(
                    cleanupPath,
                    UnsafeWorkspaceMutationException(
                        message = "Secure workspace cleanup restoration lost its cleanup entry",
                        details = failureDetails(target, "restore-cleanup-source-missing") +
                            mapOf("recoveryFilePath" to cleanupPath.toString()),
                    ),
                )
            }
        } catch (exception: Exception) {
            exception.rethrowIfParentDirectoryDurabilityFailed()
            exception.rethrowIfMutationCancellation(
                UnsafeWorkspaceMutationException(
                    message = "Secure workspace cleanup restoration retained recovery evidence after cancellation",
                    details = failureDetails(target, "restore-cleanup-cancellation") +
                        mapOf("recoveryFilePath" to cleanupPath.toString()),
                ),
            )
            CleanupNameRestoration.Retained(cleanupPath, exception)
        }
    }

internal fun Throwable.rethrowIfMutationCancellation(evidence: RuntimeException) {
    if (this !is ProcessCanceledException && this !is CancellationException) return
    addSuppressed(evidence)
    throw this
}

internal fun CleanupResult.rethrowIfMutationCancellation(evidence: RuntimeException) {
    if (this !is CleanupResult.Cancelled) return
    cancellation.rethrowIfMutationCancellation(evidence)
}

internal fun SecureWorkspaceMutation.preCommitFailure(
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

internal fun Throwable.failureReason(): String = message ?: this::class.java.simpleName
