package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import io.github.amichne.kast.change.apply.MutationSourceCaptureFailure
import io.github.amichne.kast.change.apply.ObservedMutationSource
import io.github.amichne.kast.change.apply.SourceObservationFailure
import io.github.amichne.kast.change.apply.SourceObservationResult
import io.github.amichne.kast.change.apply.SourceWriteAccess
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtFile

/** Request-local bounded physical observation; no mutation authority escapes this adapter. */
internal class IntellijMutationSourceObserver(private val project: Project) {
    private val addFile = IntellijAddFileSourcePrimitive(project)

    fun observe(source: SymbolDiscoveryFileIdentity.Workspace, limits: ReadLimits): SourceObservationResult =
        try {
            ProgressManager.checkCanceled()
            if (DumbService.getInstance(project).isDumb) {
                rejectedObservation(SourceObservationFailure.DUMB_MODE)
            } else {
                ReadAction.computeBlocking<SourceObservationResult, RuntimeException> {
                    observeReady(source, limits)
                }
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            rejectedObservation(SourceObservationFailure.SOURCE_BYTES_UNAVAILABLE)
        }

    private fun observeReady(
        source: SymbolDiscoveryFileIdentity.Workspace,
        limits: ReadLimits,
    ): SourceObservationResult {
        when (val absence = addFile.observeIfAbsent(source)) {
            IntellijAddFileAbsenceObservation.TargetPresent -> Unit
            is IntellijAddFileAbsenceObservation.Observed -> return absence.result
            is IntellijAddFileAbsenceObservation.Rejected -> return rejectedObservation(absence.failure)
        }
        val path = Path.of(source.path.value)
        val file =
            LocalFileSystem.getInstance().findFileByNioFile(path)
                ?: return rejectedObservation(SourceObservationFailure.TARGET_NOT_FOUND)
        if (!file.isValid) return rejectedObservation(SourceObservationFailure.TARGET_INVALIDATED)
        val target =
            PsiManager.getInstance(project).findFile(file) as? KtFile
                ?: return rejectedObservation(SourceObservationFailure.TARGET_NOT_KOTLIN)
        if (!target.isValid) return rejectedObservation(SourceObservationFailure.TARGET_INVALIDATED)
        if (FileDocumentManager.getInstance().getDocument(file) == null) {
            return rejectedObservation(SourceObservationFailure.DOCUMENT_UNAVAILABLE)
        }
        return captureSource(source = source, writable = file.isWritable, path = path, limits = limits)
    }

    private fun captureSource(
        source: SymbolDiscoveryFileIdentity.Workspace,
        writable: Boolean,
        path: Path,
        limits: ReadLimits,
    ): SourceObservationResult {
        val bytes =
            try {
                when (val read = Files.newInputStream(path).use { readBoundedMutationSource(it, limits) }) {
                    is BoundedMutationSourceRead.Complete -> read.bytes
                    BoundedMutationSourceRead.LimitExceeded ->
                        return rejectedObservation(SourceObservationFailure.SOURCE_LIMIT_EXCEEDED)
                }
            } catch (_: Exception) {
                return rejectedObservation(SourceObservationFailure.SOURCE_BYTES_UNAVAILABLE)
            }
        val access = if (writable) SourceWriteAccess.Writable else SourceWriteAccess.ReadOnly
        return when (val captured = ObservedMutationSource.capture(source, bytes, access)) {
            is Refinement.Refined -> SourceObservationResult.Observed(captured.value)
            is Refinement.Rejected ->
                rejectedObservation(
                    when (captured.failure) {
                        MutationSourceCaptureFailure.INVALID_UTF8,
                        MutationSourceCaptureFailure.SOURCE_HASH_UNREPRESENTABLE ->
                            SourceObservationFailure.INVALID_SOURCE_CONTENT
                    }
                )
        }
    }

    private fun rejectedObservation(failure: SourceObservationFailure) = SourceObservationResult.Rejected(failure)
}
