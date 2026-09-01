package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import io.github.amichne.kast.change.apply.SourceWriteFailure
import org.jetbrains.kotlin.psi.KtFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

internal sealed interface IntellijSourcePreparation {
    data class Ready(
        val file: VirtualFile,
        val target: KtFile,
        val document: Document,
    ) : IntellijSourcePreparation

    data class Rejected(
        val failure: SourceWriteFailure,
    ) : IntellijSourcePreparation
}

internal class LiveIntellijDocumentSession(
    private val project: Project,
    private val prepared: IntellijSourcePreparation.Ready,
    private val input: IntellijMutationInput,
    private val changedPaths: Set<String>,
) : IntellijDocumentMutationSession {
    override fun currentText(): String = prepared.document.text

    override fun mutate(input: IntellijMutationInput): IntellijSessionStepResult = writeCommand {
        when (val precondition = finalPrecondition(input.preimageText)) {
            IntellijFinalPrecondition.Ready -> {
                input.mutations.sortedByDescending { it.startInclusive }.forEach { mutation ->
                    prepared.document.replaceString(
                        mutation.startInclusive,
                        mutation.endExclusive,
                        mutation.replacement,
                    )
                }
                PsiDocumentManager.getInstance(project).commitDocument(prepared.document)
                IntellijSessionStepResult.Completed
            }
            is IntellijFinalPrecondition.Rejected ->
                IntellijSessionStepResult.Rejected(precondition.failure)
        }
    }

    override fun restore(preimageText: String): IntellijSessionStepResult = writeCommand {
        prepared.document.setText(preimageText)
        PsiDocumentManager.getInstance(project).commitDocument(prepared.document)
        if (prepared.document.text == preimageText) {
            IntellijSessionStepResult.Completed
        } else {
            IntellijSessionStepResult.Rejected(SourceWriteFailure.ROLLBACK_FAILED)
        }
    }

    override fun save(): IntellijSessionStepResult = try {
        onEdt {
            WriteAction.run<RuntimeException> {
                prepared.file.setBinaryContent(
                    input.postimageText.toByteArray(StandardCharsets.UTF_8),
                )
                FileDocumentManager.getInstance().saveDocumentAsIs(prepared.document)
            }
        }
        IntellijSessionStepResult.Completed
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        IntellijSessionStepResult.Rejected(SourceWriteFailure.SAVE_FAILED)
    }

    override fun observe(): IntellijPhysicalSourceObservation = try {
        IntellijPhysicalSourceObservation.Observed(
            Files.readAllBytes(Path.of(input.sourcePath)),
            changedPaths.toSet(),
        )
    } catch (_: Exception) {
        IntellijPhysicalSourceObservation.Rejected(SourceWriteFailure.OBSERVATION_FAILED)
    }

    /**
     * Proof transition: `String -> IntellijFinalPrecondition`.
     *
     * Ready re-establishes valid writable target, smart mode, and exact document preimage on EDT
     * immediately before insertion. [SourceWriteFailure] closes rejection. Raw expected text is
     * extracted only from `MutationAuthority` within this request-local adapter session.
     */
    private fun finalPrecondition(expected: String): IntellijFinalPrecondition = when {
        !prepared.file.isValid || !prepared.target.isValid ->
            IntellijFinalPrecondition.Rejected(SourceWriteFailure.TARGET_INVALIDATED)
        !prepared.file.isWritable ->
            IntellijFinalPrecondition.Rejected(SourceWriteFailure.TARGET_READ_ONLY)
        DumbService.getInstance(project).isDumb ->
            IntellijFinalPrecondition.Rejected(SourceWriteFailure.DUMB_MODE)
        prepared.document.text != expected ->
            IntellijFinalPrecondition.Rejected(SourceWriteFailure.PREIMAGE_CHANGED)
        else -> IntellijFinalPrecondition.Ready
    }

    private fun writeCommand(action: () -> IntellijSessionStepResult): IntellijSessionStepResult =
        try {
            onEdt {
                WriteCommandAction.writeCommandAction(project, prepared.target)
                    .withName("Kast semantic change")
                    .withGroupId("kast.change.semantic")
                    .compute<IntellijSessionStepResult, RuntimeException>(action)
                ?: IntellijSessionStepResult.Rejected(SourceWriteFailure.MUTATION_FAILED)
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            IntellijSessionStepResult.Rejected(SourceWriteFailure.MUTATION_FAILED)
        }
}

private sealed interface IntellijFinalPrecondition {
    data object Ready : IntellijFinalPrecondition

    data class Rejected(
        val failure: SourceWriteFailure,
    ) : IntellijFinalPrecondition
}

private sealed interface EdtValue<out Value> {
    data object Pending : EdtValue<Nothing>

    data class Completed<Value>(
        val value: Value,
    ) : EdtValue<Value>
}

internal fun <Value> onEdt(action: () -> Value): Value {
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) return action()
    val result = AtomicReference<EdtValue<Value>>(EdtValue.Pending)
    application.invokeAndWait { result.set(EdtValue.Completed(action())) }
    return when (val completed = result.get()) {
        EdtValue.Pending -> error("EDT invocation returned without a value")
        is EdtValue.Completed -> completed.value
    }
}
