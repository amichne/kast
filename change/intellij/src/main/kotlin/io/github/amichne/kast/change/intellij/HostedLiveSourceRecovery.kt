package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import io.github.amichne.kast.change.apply.LiveRecoveryAuthority
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.change.recovery.RecoveryDocumentObservation
import io.github.amichne.kast.change.recovery.RecoverySourceObservation
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.evidence.contract.RecoverySourcePath
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPreWriteObservation
import java.nio.file.Files
import java.nio.file.Path

enum class LiveRecoverySourceFailure {
    UNAVAILABLE,
    TOO_LARGE,
}

/** Fresh project-bound observation includes loaded buffers; storage never guesses whether a pre-write record wrote. */
class HostedLiveSourceRecovery(private val project: Project) {
    fun rollback(
        authority: LiveRecoveryAuthority,
        observation: HostedPreWriteObservation,
    ): AddDeclarationRollbackResult = IntellijExistingSourceRollback(project).rollback(authority, observation)

    fun observe(
        source: RecoverySourcePath,
        limits: ReadLimits,
    ): Refinement<RecoverySourceObservation, LiveRecoverySourceFailure> {
        val path = Path.of(source.value)
        return try {
            val bytes =
                when (val read = Files.newInputStream(path).use { readBoundedMutationSource(it, limits) }) {
                    is BoundedMutationSourceRead.Complete -> read.bytes
                    BoundedMutationSourceRead.LimitExceeded ->
                        return Refinement.Rejected(LiveRecoverySourceFailure.TOO_LARGE)
                }
            val saved = RecoveryPreimage.fromBoundary(bytes)
            val document =
                ReadAction.computeBlocking<RecoveryDocumentObservation, RuntimeException> {
                    if (project.isDisposed) return@computeBlocking RecoveryDocumentObservation.Unavailable
                    val file =
                        LocalFileSystem.getInstance().findFileByNioFile(path)
                            ?: return@computeBlocking RecoveryDocumentObservation.Unavailable
                    val files = FileDocumentManager.getInstance()
                    val loaded =
                        files.getDocument(file) ?: return@computeBlocking RecoveryDocumentObservation.Unavailable
                    if (
                        files.isDocumentUnsaved(loaded) || !PsiDocumentManager.getInstance(project).isCommitted(loaded)
                    ) {
                        RecoveryDocumentObservation.DirtyOrUncommitted
                    } else
                        RecoveryDocumentObservation.SavedAndCommitted(
                            RecoveryPreimage.fromBoundary(loaded.text.toByteArray(Charsets.UTF_8))
                        )
                }
            Refinement.Refined(RecoverySourceObservation(source, saved, document))
        } catch (_: java.io.IOException) {
            Refinement.Rejected(LiveRecoverySourceFailure.UNAVAILABLE)
        }
    }
}
