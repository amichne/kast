package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.psi.PsiDocumentManager
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPreWriteObservation
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.kotlin.psi.KtFile

internal sealed interface IntellijSourcePreparation {
    data class Ready(
        val file: VirtualFile,
        val target: KtFile,
        val document: Document,
    ) : IntellijSourcePreparation

    data class Rejected(val failure: SourceWriteFailure) : IntellijSourcePreparation
}

internal class LiveIntellijDocumentSession(
    private val project: Project,
    private val prepared: IntellijSourcePreparation.Ready,
    private val input: IntellijMutationInput,
    private val changedPaths: Set<String>,
    private val freshness: IntellijWriteFreshness = IntellijWriteFreshness.Published,
) : IntellijDocumentMutationSession {
    private val commandGroup = IntellijMutationCommandGroup()
    private var mutationCheckpoint: IntellijMutationCheckpoint = IntellijMutationCheckpoint.Pending

    override fun currentText(): String = prepared.document.text

    override fun mutate(input: IntellijMutationInput): IntellijDocumentMutationResult =
        try {
            onEdt {
                WriteCommandAction.writeCommandAction(project, prepared.target)
                    .withName("Kast semantic change")
                    .withGroupId(commandGroup.atPlatformBoundary())
                    .compute<IntellijDocumentMutationResult, RuntimeException> {
                        mutateAtBoundary(input)
                    } ?: IntellijDocumentMutationResult.EffectUncertain(SourceWriteFailure.MUTATION_FAILED)
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            IntellijDocumentMutationResult.EffectUncertain(SourceWriteFailure.MUTATION_FAILED)
        }

    private fun mutateAtBoundary(input: IntellijMutationInput): IntellijDocumentMutationResult {
        return when (val precondition = finalPrecondition(input.preimageText)) {
            IntellijFinalPrecondition.Ready -> {
                when (val admission = freshness) {
                    IntellijWriteFreshness.Published -> Unit
                    is IntellijWriteFreshness.Hosted ->
                        when (admission.observation.consumeAtWriteBoundary()) {
                            is Refinement.Refined -> Unit
                            is Refinement.Rejected ->
                                return IntellijDocumentMutationResult.RejectedBeforeMutation(
                                    SourceWriteFailure.PREIMAGE_CHANGED
                                )
                        }
                }
                input.mutations
                    .sortedByDescending { it.startInclusive }
                    .forEach { mutation ->
                        prepared.document.replaceString(
                            mutation.startInclusive,
                            mutation.endExclusive,
                            mutation.replacement,
                        )
                    }
                PsiDocumentManager.getInstance(project).commitDocument(prepared.document)
                mutationCheckpoint = IntellijMutationCheckpoint.Completed(prepared.document.modificationStamp)
                IntellijDocumentMutationResult.Completed
            }
            is IntellijFinalPrecondition.Rejected ->
                IntellijDocumentMutationResult.RejectedBeforeMutation(precondition.failure)
        }
    }

    override fun restore(preimageText: String): IntellijSessionStepResult = writeCommand {
        when (val precondition = postMutationPrecondition(IntellijWriteBoundaryStage.LOCAL_RESTORATION)) {
            IntellijFinalPrecondition.Ready -> Unit
            is IntellijFinalPrecondition.Rejected ->
                return@writeCommand IntellijSessionStepResult.Rejected(precondition.failure)
        }
        prepared.document.setText(preimageText)
        PsiDocumentManager.getInstance(project).commitDocument(prepared.document)
        val outcome =
            intellijSaveOutcome(
                unsaved = FileDocumentManager.getInstance().isDocumentUnsaved(prepared.document),
                committed = PsiDocumentManager.getInstance(project).isCommitted(prepared.document),
                observedText = prepared.document.text,
                expectedText = preimageText,
            )
        Logger.getInstance(LiveIntellijDocumentSession::class.java)
            .info("Kast source write boundary: stage=RESTORATION_OBSERVATION outcome=$outcome")
        if (outcome == IntellijSaveOutcome.SAVED_COMMITTED_IMAGE) {
            IntellijSessionStepResult.Completed
        } else {
            IntellijSessionStepResult.Rejected(SourceWriteFailure.ROLLBACK_FAILED)
        }
    }

    override fun save(): IntellijSessionStepResult =
        try {
            onEdt {
                WriteAction.compute<IntellijSessionStepResult, RuntimeException> {
                    when (val precondition = postMutationPrecondition(IntellijWriteBoundaryStage.EXPLICIT_SAVE)) {
                        IntellijFinalPrecondition.Ready -> Unit
                        is IntellijFinalPrecondition.Rejected ->
                            return@compute IntellijSessionStepResult.Rejected(precondition.failure)
                    }
                    // A direct VFS write while this document is unsaved creates a memory/disk conflict;
                    // FileDocumentManager then vetoes the document save without throwing.
                    val documents = FileDocumentManager.getInstance()
                    documents.saveDocumentAsIs(prepared.document)
                    val committed = PsiDocumentManager.getInstance(project).isCommitted(prepared.document)
                    val outcome =
                        intellijSaveOutcome(
                            unsaved = documents.isDocumentUnsaved(prepared.document),
                            committed = committed,
                            observedText = prepared.document.text,
                            expectedText = input.postimageText,
                        )
                    Logger.getInstance(LiveIntellijDocumentSession::class.java)
                        .info("Kast source write boundary: stage=SAVE_OBSERVATION outcome=$outcome")
                    when (outcome) {
                        IntellijSaveOutcome.SAVED_COMMITTED_IMAGE -> IntellijSessionStepResult.Completed
                        IntellijSaveOutcome.DOCUMENT_REMAINS_UNSAVED,
                        IntellijSaveOutcome.DOCUMENT_REMAINS_UNCOMMITTED,
                        IntellijSaveOutcome.DOCUMENT_IMAGE_CHANGED ->
                            IntellijSessionStepResult.Rejected(SourceWriteFailure.SAVE_FAILED)
                    }
                }
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            IntellijSessionStepResult.Rejected(SourceWriteFailure.SAVE_FAILED)
        }

    override fun observe(): IntellijPhysicalSourceObservation =
        observeCompletedPhysicalWrite(
            complete = ::completePhysicalWrite,
            observe = ::readPhysicalPostimage,
            emit = ::logPhysicalWriteCompletion,
        )

    /** VFS saves may be asynchronous; direct filesystem reads require the per-file completion barrier. */
    private fun completePhysicalWrite(): IntellijSessionStepResult =
        try {
            if (ApplicationManager.getApplication().isDispatchThread) {
                IntellijSessionStepResult.Rejected(SourceWriteFailure.OBSERVATION_FAILED)
            } else {
                ManagingFS.getInstance().flushPendingUpdates(prepared.file)
                IntellijSessionStepResult.Completed
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            IntellijSessionStepResult.Rejected(SourceWriteFailure.OBSERVATION_FAILED)
        }

    private fun readPhysicalPostimage(): IntellijPhysicalSourceObservation =
        try {
            IntellijPhysicalSourceObservation.Observed(
                Files.newInputStream(Path.of(input.sourcePath)).use {
                    it.readNBytes(Math.addExact(input.postimageText.toByteArray(StandardCharsets.UTF_8).size, 1))
                },
                changedPaths.toSet(),
            )
        } catch (_: Exception) {
            IntellijPhysicalSourceObservation.Rejected(SourceWriteFailure.OBSERVATION_FAILED)
        }

    /**
     * Proof transition: `String -> IntellijFinalPrecondition`.
     *
     * Ready re-establishes valid writable target, smart mode, saved committed documents, and exact document and
     * physical preimages on EDT immediately before insertion. [SourceWriteFailure] closes rejection. Raw expected text
     * is extracted only from `MutationAuthority` within this request-local adapter session.
     */
    private fun finalPrecondition(expected: String): IntellijFinalPrecondition {
        val stage = IntellijWriteBoundaryStage.MUTATION_ADMISSION
        when (val target = targetPrecondition(stage)) {
            IntellijFinalPrecondition.Ready -> Unit
            is IntellijFinalPrecondition.Rejected -> return target
        }
        return when {
            DumbService.getInstance(project).isDumb -> rejected(stage, IntellijWriteBoundaryFailure.DUMB_MODE)
            FileDocumentManager.getInstance().unsavedDocuments.isNotEmpty() ->
                rejected(stage, IntellijWriteBoundaryFailure.DIRTY_DOCUMENTS)
            PsiDocumentManager.getInstance(project).hasUncommitedDocuments() ->
                rejected(stage, IntellijWriteBoundaryFailure.UNCOMMITTED_DOCUMENTS)
            prepared.document.text != expected -> rejected(stage, IntellijWriteBoundaryFailure.DOCUMENT_IMAGE_CHANGED)
            else -> physicalPrecondition(stage)
        }
    }

    private fun postMutationPrecondition(stage: IntellijWriteBoundaryStage): IntellijFinalPrecondition {
        when (val target = targetPrecondition(stage)) {
            IntellijFinalPrecondition.Ready -> Unit
            is IntellijFinalPrecondition.Rejected -> return target
        }
        val checkpoint =
            when (val current = mutationCheckpoint) {
                is IntellijMutationCheckpoint.Completed -> current
                IntellijMutationCheckpoint.Pending -> return rejected(stage, IntellijWriteBoundaryFailure.NO_POSTIMAGE)
            }
        if (
            prepared.document.modificationStamp != checkpoint.documentStamp ||
                prepared.document.text != input.postimageText
        ) {
            return rejected(stage, IntellijWriteBoundaryFailure.DOCUMENT_IMAGE_CHANGED)
        }
        if (!PsiDocumentManager.getInstance(project).isCommitted(prepared.document)) {
            return rejected(stage, IntellijWriteBoundaryFailure.UNCOMMITTED_DOCUMENTS)
        }
        return physicalPrecondition(stage)
    }

    private fun targetPrecondition(stage: IntellijWriteBoundaryStage): IntellijFinalPrecondition =
        when {
            project.isDisposed || !project.isOpen || !prepared.file.isValid || !prepared.target.isValid ->
                rejected(stage, IntellijWriteBoundaryFailure.TARGET_INVALIDATED)
            !prepared.file.isWritable -> rejected(stage, IntellijWriteBoundaryFailure.TARGET_READ_ONLY)
            prepared.file.path != input.sourcePath ||
                FileDocumentManager.getInstance().getCachedDocument(prepared.file) !== prepared.document ||
                PsiDocumentManager.getInstance(project).getPsiFile(prepared.document) !== prepared.target ->
                rejected(stage, IntellijWriteBoundaryFailure.TARGET_INVALIDATED)
            else -> IntellijFinalPrecondition.Ready
        }

    private fun physicalPrecondition(stage: IntellijWriteBoundaryStage): IntellijFinalPrecondition {
        val physical =
            try {
                Files.newInputStream(Path.of(input.sourcePath)).use {
                    it.readNBytes(Math.addExact(input.preimageText.toByteArray(StandardCharsets.UTF_8).size, 1))
                }
            } catch (_: Exception) {
                return rejected(stage, IntellijWriteBoundaryFailure.PHYSICAL_SOURCE_UNAVAILABLE)
            }
        return if (physical.contentEquals(input.preimageText.toByteArray(StandardCharsets.UTF_8))) {
            Logger.getInstance(LiveIntellijDocumentSession::class.java)
                .info("Kast source write boundary: stage=$stage outcome=ADMITTED")
            IntellijFinalPrecondition.Ready
        } else {
            rejected(stage, IntellijWriteBoundaryFailure.PHYSICAL_IMAGE_CHANGED)
        }
    }

    private fun rejected(
        stage: IntellijWriteBoundaryStage,
        failure: IntellijWriteBoundaryFailure,
    ): IntellijFinalPrecondition.Rejected {
        Logger.getInstance(LiveIntellijDocumentSession::class.java)
            .info("Kast source write boundary: stage=$stage outcome=REJECTED reason=$failure")
        return IntellijFinalPrecondition.Rejected(failure.sourceFailure)
    }

    private fun writeCommand(action: () -> IntellijSessionStepResult): IntellijSessionStepResult =
        try {
            onEdt {
                WriteCommandAction.writeCommandAction(project, prepared.target)
                    .withName("Kast semantic change")
                    .withGroupId(commandGroup.atPlatformBoundary())
                    .compute<IntellijSessionStepResult, RuntimeException>(action)
                    ?: IntellijSessionStepResult.Rejected(SourceWriteFailure.MUTATION_FAILED)
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            IntellijSessionStepResult.Rejected(SourceWriteFailure.MUTATION_FAILED)
        }
}

/** One attempt may group its local restoration; separate sessions must remain separate Undo steps. */
private class IntellijMutationCommandGroup {
    private val identity = UUID.randomUUID()

    fun atPlatformBoundary(): String = "kast.change.semantic:$identity"
}

internal enum class IntellijSaveOutcome {
    SAVED_COMMITTED_IMAGE,
    DOCUMENT_REMAINS_UNSAVED,
    DOCUMENT_REMAINS_UNCOMMITTED,
    DOCUMENT_IMAGE_CHANGED,
}

/** A save API returning normally is not evidence that a vetoable save succeeded. */
internal fun intellijSaveOutcome(
    unsaved: Boolean,
    committed: Boolean,
    observedText: String,
    expectedText: String,
): IntellijSaveOutcome =
    when {
        unsaved -> IntellijSaveOutcome.DOCUMENT_REMAINS_UNSAVED
        !committed -> IntellijSaveOutcome.DOCUMENT_REMAINS_UNCOMMITTED
        observedText != expectedText -> IntellijSaveOutcome.DOCUMENT_IMAGE_CHANGED
        else -> IntellijSaveOutcome.SAVED_COMMITTED_IMAGE
    }

internal sealed interface IntellijWriteFreshness {
    data object Published : IntellijWriteFreshness

    data class Hosted(val observation: HostedPreWriteObservation) : IntellijWriteFreshness
}

private sealed interface IntellijMutationCheckpoint {
    data object Pending : IntellijMutationCheckpoint

    data class Completed(val documentStamp: Long) : IntellijMutationCheckpoint
}

private enum class IntellijWriteBoundaryStage {
    MUTATION_ADMISSION,
    LOCAL_RESTORATION,
    EXPLICIT_SAVE,
}

private enum class IntellijWriteBoundaryFailure(val sourceFailure: SourceWriteFailure) {
    TARGET_INVALIDATED(SourceWriteFailure.TARGET_INVALIDATED),
    TARGET_READ_ONLY(SourceWriteFailure.TARGET_READ_ONLY),
    DUMB_MODE(SourceWriteFailure.DUMB_MODE),
    DIRTY_DOCUMENTS(SourceWriteFailure.PREIMAGE_CHANGED),
    UNCOMMITTED_DOCUMENTS(SourceWriteFailure.PREIMAGE_CHANGED),
    DOCUMENT_IMAGE_CHANGED(SourceWriteFailure.PREIMAGE_CHANGED),
    PHYSICAL_IMAGE_CHANGED(SourceWriteFailure.PREIMAGE_CHANGED),
    PHYSICAL_SOURCE_UNAVAILABLE(SourceWriteFailure.PREIMAGE_CHANGED),
    NO_POSTIMAGE(SourceWriteFailure.MUTATION_FAILED),
}

private sealed interface IntellijFinalPrecondition {
    data object Ready : IntellijFinalPrecondition

    data class Rejected(val failure: SourceWriteFailure) : IntellijFinalPrecondition
}

private sealed interface EdtValue<out Value> {
    data object Pending : EdtValue<Nothing>

    data class Completed<Value>(val value: Value) : EdtValue<Value>
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
