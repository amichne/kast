package io.github.amichne.kast.fixtureprobe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ManagingFS
import java.nio.file.Files
import java.nio.file.Path

internal sealed interface ProbeSavePreparation {
    data class Resolved(val result: ProbeExecution) : ProbeSavePreparation

    data class Pending(val save: ProbePendingSave) : ProbeSavePreparation
}

/** Document and PSI evidence captured on EDT before physical saved bytes are observed by the worker. */
internal data class ProbeDocumentEvidence(
    val document: ProbeDigest,
    val documentState: ProbeDocumentState,
    val syntax: ProbeSyntaxState,
    val undo: ProbeUndoState,
    val declarations: List<ProbeDeclaration>,
) {
    fun withSaved(saved: ProbeDigest): ProbeEvidence =
        ProbeEvidence(saved, document, documentState, syntax, undo, declarations)
}

internal class ProbePendingSave(
    private val file: VirtualFile,
    private val path: Path,
    private val expected: ProbeDigest,
    private val document: ProbeDocumentEvidence,
) {
    fun complete(): ProbeExecution {
        if (ApplicationManager.getApplication().isDispatchThread)
            return ProbeExecution.EffectUncertain(ProbeFailure.NATIVE_UNAVAILABLE)
        return completeProbeSave(expected = expected, document = document, complete = ::flush, observe = ::observe)
    }

    private fun flush(): ProbeResult<Unit> =
        try {
            ManagingFS.getInstance().flushPendingUpdates(file)
            ProbeResult.Accepted(Unit)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeResult.Rejected(ProbeFailure.SAVE_REJECTED)
        }

    private fun observe(): ProbeResult<ProbeDigest> =
        try {
            Files.newInputStream(path).use { input ->
                val bytes = input.readNBytes(MAXIMUM_SOURCE_BYTES + 1)
                if (bytes.size > MAXIMUM_SOURCE_BYTES) ProbeResult.Rejected(ProbeFailure.SOURCE_TOO_LARGE)
                else ProbeResult.Accepted(ProbeDigest.observe(bytes))
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeResult.Rejected(ProbeFailure.SAVE_REJECTED)
        }
}

internal fun completeProbeSave(
    expected: ProbeDigest,
    document: ProbeDocumentEvidence,
    complete: () -> ProbeResult<Unit>,
    observe: () -> ProbeResult<ProbeDigest>,
): ProbeExecution {
    when (val completion = complete()) {
        is ProbeResult.Accepted -> Unit
        is ProbeResult.Rejected -> return ProbeExecution.EffectUncertain(completion.failure)
    }
    val saved =
        when (val observation = observe()) {
            is ProbeResult.Accepted -> observation.value
            is ProbeResult.Rejected -> return ProbeExecution.EffectUncertain(observation.failure)
        }
    if (saved != expected || document.document != expected)
        return ProbeExecution.EffectUncertain(ProbeFailure.SAVE_REJECTED)
    return if (document.documentState == ProbeDocumentState.SAVED_COMMITTED)
        ProbeExecution.Completed(document.withSaved(saved))
    else ProbeExecution.EffectUncertain(ProbeFailure.DOCUMENT_STATE_REJECTED)
}
