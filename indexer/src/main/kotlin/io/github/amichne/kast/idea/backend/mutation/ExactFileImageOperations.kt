package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.api.contract.result.ExactFileImageResult
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.UnsafeWorkspaceMutationException
import io.github.amichne.kast.api.validation.ParsedExactFileImageQuery
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.mutation.SecureWorkspaceMutationResult
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext

internal interface ExactFileImageCasObserver {
    fun beforeWriteCriticalSection(target: Path) = Unit

    fun afterSecureCommit(target: Path) = Unit

    object Disabled : ExactFileImageCasObserver
}

internal suspend fun KastIndexerBackend.exactFileImageCasOperation(
    query: ParsedExactFileImageQuery,
): ExactFileImageResult = mutationAttemptGate.write(query.mutationAttemptId) {
    exactFileImageCasUnderFence(query)
}

private suspend fun KastIndexerBackend.exactFileImageCasUnderFence(
    query: ParsedExactFileImageQuery,
): ExactFileImageResult = try {
    withContext(readDispatcher) {
        telemetry.inSpan(IdeaTelemetryScope.EXACT_FILE_IMAGE_CAS, "kast.idea.exactFileImageCas") {
            val target = query.filePath.toJavaPath()
            rejectUnsavedDocument(target)
            exactFileImageCasObserver.beforeWriteCriticalSection(target)
            var committedResult: ExactFileImageResult? = null
            var committedMutation: SecureWorkspaceMutationResult? = null
            WriteCommandAction.runWriteCommandAction(project) {
                rejectUnsavedDocument(target)
                val postimage = query.content.copyBytes()
                val mutationResult = try {
                    exactFileImageMutation.replaceFile(
                        target = target,
                        expectedDiskHash = query.expectedCurrentSha256.value,
                        content = postimage,
                        scratch = query.mutationScratch,
                    )
                } catch (failure: CancellationException) {
                    throw ExactFileImageCleanupCancellation(failure)
                }
                try {
                    exactFileImageCasObserver.afterSecureCommit(target)
                    exactFileImageMutation.verifyCommittedFile(
                        target = target,
                        expectedContent = postimage,
                        mutation = IdeaWorkspaceMutation.TEXT_EDIT,
                    )
                    val actualResultSha256 = exactFileImageMutation.currentFileSha256(
                        target,
                        IdeaWorkspaceMutation.TEXT_EDIT,
                    )
                    if (actualResultSha256 != query.expectedResultSha256.value) {
                        throw ConflictException(
                            message = "Exact file-image post-commit hash verification failed",
                            details = mapOf(
                                "filePath" to target.toString(),
                                "expectedHash" to query.expectedResultSha256.value,
                                "actualHash" to actualResultSha256,
                            ),
                        )
                    }
                } catch (failure: ProcessCanceledException) {
                    throw failure
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    throw committedImageUnsafe(target, query, mutationResult, failure)
                }
                if (mutationResult is SecureWorkspaceMutationResult.CommittedWithRecovery) {
                    throw committedImageUnsafe(target, query, mutationResult)
                }
                committedResult = ExactFileImageResult.committed(
                    filePath = query.filePath.value,
                    previousSha256 = query.expectedCurrentSha256,
                    resultSha256 = query.expectedResultSha256,
                )
                committedMutation = mutationResult
            }
            try {
                refreshExactFileImage(target)
            } catch (failure: ProcessCanceledException) {
                throw failure
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw committedImageUnsafe(target, query, checkNotNull(committedMutation), failure)
            }
            checkNotNull(committedResult)
        }
    }
} catch (failure: ExactFileImageCleanupCancellation) {
    throw failure.cancellation
}

private class ExactFileImageCleanupCancellation(
    val cancellation: CancellationException,
) : RuntimeException()

private fun KastIndexerBackend.rejectUnsavedDocument(target: Path) {
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(target) ?: return
    val documentManager = FileDocumentManager.getInstance()
    val document = documentManager.getCachedDocument(virtualFile) ?: return
    if (documentManager.isDocumentUnsaved(document)) {
        throw ConflictException(
            message = "Exact file-image compare-and-swap refuses an unsaved IntelliJ document",
            details = mapOf("filePath" to target.toString()),
        )
    }
}

private fun KastIndexerBackend.refreshExactFileImage(target: Path) {
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        ?: throw UnsafeWorkspaceMutationException(
            message = "Exact file-image commit could not refresh its IntelliJ file",
            details = mapOf(
                "filePath" to target.toString(),
                "committed" to "true",
            ),
        )
    VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
    WriteAction.runAndWait<RuntimeException> {
        val fileDocumentManager = FileDocumentManager.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(virtualFile)
        val document = fileDocumentManager.getCachedDocument(virtualFile)
        if (document == null) {
            psiFile?.let(psiManager::reloadFromDisk)
        } else {
            fileDocumentManager.reloadFromDisk(document)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}

private fun committedImageUnsafe(
    target: Path,
    query: ParsedExactFileImageQuery,
    mutationResult: SecureWorkspaceMutationResult,
    failure: Exception? = null,
): UnsafeWorkspaceMutationException {
    val recoveryDetails = if (mutationResult is SecureWorkspaceMutationResult.CommittedWithRecovery) {
        buildMap {
            put("recoveryFilePathCount", mutationResult.recoveryFilePaths.size.toString())
            mutationResult.recoveryFilePaths.forEachIndexed { index, recoveryPath ->
                put("recoveryFilePath.$index", recoveryPath.toString())
            }
        }
    } else {
        emptyMap()
    }
    return UnsafeWorkspaceMutationException(
        message = if (failure == null) {
            "Exact file-image commit retained secure recovery evidence"
        } else {
            "Exact file-image commit could not prove its final state"
        },
        details = mapOf(
            "filePath" to target.toString(),
            "committed" to "true",
            "expectedResultSha256" to query.expectedResultSha256.value,
        ) + recoveryDetails + failure?.let { error ->
            mapOf(
                "cause" to (error.message ?: error::class.java.simpleName),
                "causeClass" to (error::class.qualifiedName ?: error::class.java.name),
            )
        }.orEmpty(),
    )
}
