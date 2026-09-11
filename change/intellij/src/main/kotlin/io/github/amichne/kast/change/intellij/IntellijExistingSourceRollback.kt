package io.github.amichne.kast.change.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.change.apply.LiveRecoveryAuthority
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.apply.MutationPreconditionAtIntellijBoundary
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedPreWriteObservation
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtFile

/** Exact recovery primitive for a source that existed before mutation. */
internal class IntellijExistingSourceRollback(private val project: Project) {
    /**
     * Proof transition: `(MutationAuthority, ExistingPrecondition, ByteArray) -> AddDeclarationRollbackResult`.
     *
     * RolledBack establishes that both the physical file and live IntelliJ document equal the exact durable recovery
     * preimage, overwriting only the authority's exact postimage. [AddDeclarationRollbackFailure] closes divergent or
     * unavailable state. Raw recovery bytes remain inside this adapter boundary.
     */
    fun rollback(
        authority: MutationAuthority,
        expected: MutationPreconditionAtIntellijBoundary.Existing,
        preimage: ByteArray,
    ): AddDeclarationRollbackResult {
        if (!preimage.contentEquals(expected.text.toByteArray(StandardCharsets.UTF_8))) {
            return rejected(
                AddDeclarationRollbackFailure.CONTENT_DIVERGED,
                IntellijExistingRollbackRejection.PREIMAGE_AUTHORITY_MISMATCH,
            )
        }
        return rollbackExact(
            project = project,
            input =
                IntellijExistingRecoveryInput(
                    authority.source.path.value,
                    expected.text,
                    authority.postimageTextAtIntellijBoundary(),
                ),
            preimage = preimage,
            freshness = IntellijWriteFreshness.Published,
        )
    }

    fun rollback(
        authority: LiveRecoveryAuthority,
        observation: HostedPreWriteObservation,
    ): AddDeclarationRollbackResult = observation.use {
        if (observation.reference != authority.current)
            return@use rejected(
                AddDeclarationRollbackFailure.CONTENT_DIVERGED,
                IntellijExistingRollbackRejection.PREIMAGE_AUTHORITY_MISMATCH,
            )
        rollbackExact(
            project = project,
            input =
                IntellijExistingRecoveryInput(
                    authority.plan.target.file.path.value,
                    authority.preimageTextAtIntellijBoundary(),
                    authority.postimageTextAtIntellijBoundary(),
                ),
            preimage = authority.preimageBytesAtRecoveryBoundary(),
            freshness = IntellijWriteFreshness.Hosted(observation),
        )
    }
}

private fun rollbackExact(
    project: Project,
    input: IntellijExistingRecoveryInput,
    preimage: ByteArray,
    freshness: IntellijWriteFreshness,
): AddDeclarationRollbackResult {
    val physical =
        when (val observed = observeRollbackPhysical(input, preimage)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return observed.failure
        }
    val loaded =
        when (val observed = observeRollbackDocument(project, input, physical)) {
            is Refinement.Refined -> observed.value
            is Refinement.Rejected -> return observed.failure
        }
    if (physical == ExistingRollbackPhysicalState.Preimage) return AddDeclarationRollbackResult.RolledBack
    val target =
        ReadAction.computeBlocking<ExistingRollbackTarget?, RuntimeException> {
            val psi = PsiManager.getInstance(project).findFile(loaded.file) as? KtFile ?: return@computeBlocking null
            if (FileDocumentManager.getInstance().getDocument(loaded.file) !== loaded.document)
                return@computeBlocking null
            ExistingRollbackTarget(psi, loaded)
        }
            ?: return rejected(
                AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                IntellijExistingRollbackRejection.PSI_OR_DOCUMENT_UNAVAILABLE,
            )
    return writeRollback(project = project, target = target, input = input, preimage = preimage, freshness = freshness)
}

private fun observeRollbackPhysical(
    input: IntellijExistingRecoveryInput,
    preimage: ByteArray,
): Refinement<ExistingRollbackPhysicalState, AddDeclarationRollbackResult.Rejected> {
    val current =
        try {
            readRollbackBytes(input, preimage)
        } catch (_: Exception) {
            return Refinement.Rejected(
                rejected(
                    AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                    IntellijExistingRollbackRejection.PHYSICAL_SOURCE_UNAVAILABLE,
                )
            )
        }
    return when (
        val state =
            existingRollbackPhysicalState(current, preimage, input.postimage.toByteArray(StandardCharsets.UTF_8))
    ) {
        ExistingRollbackPhysicalState.Preimage,
        ExistingRollbackPhysicalState.Postimage -> Refinement.Refined(state)
        ExistingRollbackPhysicalState.Diverged ->
            Refinement.Rejected(
                rejected(
                    AddDeclarationRollbackFailure.CONTENT_DIVERGED,
                    IntellijExistingRollbackRejection.CURRENT_POSTIMAGE_MISMATCH,
                )
            )
    }
}

private fun observeRollbackDocument(
    project: Project,
    input: IntellijExistingRecoveryInput,
    physical: ExistingRollbackPhysicalState,
): Refinement<ExistingRollbackDocument, AddDeclarationRollbackResult.Rejected> {
    val file =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(input.path))
            ?: return Refinement.Rejected(
                rejected(
                    AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                    IntellijExistingRollbackRejection.VIRTUAL_FILE_UNAVAILABLE,
                )
            )
    val document =
        ReadAction.computeBlocking<Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(file) }
            ?: return Refinement.Rejected(
                rejected(
                    AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                    IntellijExistingRollbackRejection.PSI_OR_DOCUMENT_UNAVAILABLE,
                )
            )
    val loaded = ExistingRollbackDocument(file, document)
    val stage =
        when (physical) {
            ExistingRollbackPhysicalState.Preimage -> ExistingRollbackDocumentStage.ALREADY_PREIMAGE
            ExistingRollbackPhysicalState.Postimage -> ExistingRollbackDocumentStage.POSTIMAGE
            ExistingRollbackPhysicalState.Diverged ->
                return Refinement.Rejected(
                    rejected(
                        AddDeclarationRollbackFailure.CONTENT_DIVERGED,
                        IntellijExistingRollbackRejection.CURRENT_POSTIMAGE_MISMATCH,
                    )
                )
        }
    val expected = if (physical == ExistingRollbackPhysicalState.Preimage) input.preimage else input.postimage
    return when (
        val synchronized =
            synchronizeDocument(project = project, loaded = loaded, expectedText = expected, stage = stage)
    ) {
        ExistingRollbackDocumentSynchronization.Synchronized -> Refinement.Refined(loaded)
        is ExistingRollbackDocumentSynchronization.Rejected ->
            Refinement.Rejected(rejected(synchronized.failure, synchronized.reason))
    }
}

private fun writeRollback(
    project: Project,
    target: ExistingRollbackTarget,
    input: IntellijExistingRecoveryInput,
    preimage: ByteArray,
    freshness: IntellijWriteFreshness,
): AddDeclarationRollbackResult =
    try {
        val write = onEdt {
            WriteCommandAction.writeCommandAction(project, target.psi)
                .withName("Kast rollback semantic change")
                .compute<ExistingRollbackWrite, RuntimeException> {
                    when (
                        val final =
                            finalRecoveryPrecondition(
                                project = project,
                                target = target,
                                input = input,
                                freshness = freshness,
                            )
                    ) {
                        is ExistingRollbackAdmission.Rejected ->
                            return@compute ExistingRollbackWrite.Rejected(final.reason)
                        ExistingRollbackAdmission.Ready -> Unit
                    }
                    target.loaded.file.setBinaryContent(preimage)
                    ExistingRollbackWrite.Written
                } ?: ExistingRollbackWrite.Rejected(IntellijExistingRollbackRejection.WRITE_BOUNDARY_FAILED)
        }
        when (write) {
            ExistingRollbackWrite.Written ->
                observeRestoredRollback(project = project, loaded = target.loaded, input = input, preimage = preimage)
            is ExistingRollbackWrite.Rejected -> rejected(write.reason.rollbackFailure(), write.reason)
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        rejected(AddDeclarationRollbackFailure.WRITE_REJECTED, IntellijExistingRollbackRejection.WRITE_BOUNDARY_FAILED)
    }

private fun observeRestoredRollback(
    project: Project,
    loaded: ExistingRollbackDocument,
    input: IntellijExistingRecoveryInput,
    preimage: ByteArray,
): AddDeclarationRollbackResult {
    when (
        val synchronized =
            synchronizeDocument(
                project = project,
                loaded = loaded,
                expectedText = input.preimage,
                stage = ExistingRollbackDocumentStage.PREIMAGE,
            )
    ) {
        ExistingRollbackDocumentSynchronization.Synchronized -> Unit
        is ExistingRollbackDocumentSynchronization.Rejected ->
            return rejected(synchronized.failure, synchronized.reason)
    }
    val restored = readRollbackBytes(input, preimage)
    if (restored.contentEquals(preimage)) return AddDeclarationRollbackResult.RolledBack
    val reason =
        if (restored.contentEquals(input.postimage.toByteArray(StandardCharsets.UTF_8)))
            IntellijExistingRollbackRejection.POST_WRITE_POSTIMAGE_UNCHANGED
        else IntellijExistingRollbackRejection.POST_WRITE_CONTENT_DIVERGED
    return rejected(AddDeclarationRollbackFailure.WRITE_REJECTED, reason)
}

private fun finalRecoveryPrecondition(
    project: Project,
    target: ExistingRollbackTarget,
    input: IntellijExistingRecoveryInput,
    freshness: IntellijWriteFreshness,
): ExistingRollbackAdmission {
    when (val admitted = recoveryTargetPrecondition(project, target, input)) {
        ExistingRollbackAdmission.Ready -> Unit
        is ExistingRollbackAdmission.Rejected -> return admitted
    }
    val loaded = target.loaded
    if (
        FileDocumentManager.getInstance().unsavedDocuments.isNotEmpty() ||
            PsiDocumentManager.getInstance(project).hasUncommitedDocuments() ||
            loaded.document.text != input.postimage
    ) {
        return ExistingRollbackAdmission.Rejected(IntellijExistingRollbackRejection.DOCUMENT_POSTIMAGE_CHANGED)
    }
    when (val physical = exactRecoveryPostimage(input)) {
        ExistingRollbackAdmission.Ready -> Unit
        is ExistingRollbackAdmission.Rejected -> return physical
    }
    return when (freshness) {
        IntellijWriteFreshness.Published -> ExistingRollbackAdmission.Ready
        is IntellijWriteFreshness.Hosted ->
            when (freshness.observation.consumeAtWriteBoundary()) {
                is Refinement.Refined -> ExistingRollbackAdmission.Ready
                is Refinement.Rejected ->
                    ExistingRollbackAdmission.Rejected(IntellijExistingRollbackRejection.TARGET_INVALIDATED)
            }
    }
}

private fun recoveryTargetPrecondition(
    project: Project,
    target: ExistingRollbackTarget,
    input: IntellijExistingRecoveryInput,
): ExistingRollbackAdmission {
    val loaded = target.loaded
    if (project.isDisposed || !project.isOpen || !target.psi.isValid)
        return ExistingRollbackAdmission.Rejected(IntellijExistingRollbackRejection.TARGET_INVALIDATED)
    if (!loaded.file.isValid || !loaded.file.isWritable)
        return ExistingRollbackAdmission.Rejected(IntellijExistingRollbackRejection.TARGET_INVALIDATED)
    if (
        loaded.file.path != input.path ||
            FileDocumentManager.getInstance().getCachedDocument(loaded.file) !== loaded.document ||
            PsiDocumentManager.getInstance(project).getPsiFile(loaded.document) !== target.psi
    ) {
        return ExistingRollbackAdmission.Rejected(IntellijExistingRollbackRejection.TARGET_INVALIDATED)
    }
    return ExistingRollbackAdmission.Ready
}

private fun exactRecoveryPostimage(input: IntellijExistingRecoveryInput): ExistingRollbackAdmission {
    val expected = input.postimage.toByteArray(StandardCharsets.UTF_8)
    val physical =
        try {
            Files.newInputStream(Path.of(input.path)).use { stream ->
                stream.readNBytes(Math.addExact(expected.size, 1))
            }
        } catch (_: java.io.IOException) {
            return ExistingRollbackAdmission.Rejected(IntellijExistingRollbackRejection.PHYSICAL_SOURCE_UNAVAILABLE)
        }
    return if (physical.contentEquals(expected)) ExistingRollbackAdmission.Ready
    else ExistingRollbackAdmission.Rejected(IntellijExistingRollbackRejection.PHYSICAL_POSTIMAGE_CHANGED)
}

private fun readRollbackBytes(input: IntellijExistingRecoveryInput, preimage: ByteArray): ByteArray =
    Files.newInputStream(Path.of(input.path)).use { stream ->
        stream.readNBytes(
            Math.addExact(maxOf(preimage.size, input.postimage.toByteArray(StandardCharsets.UTF_8).size), 1)
        )
    }

private fun IntellijExistingRollbackRejection.rollbackFailure(): AddDeclarationRollbackFailure =
    when (this) {
        IntellijExistingRollbackRejection.DOCUMENT_POSTIMAGE_CHANGED,
        IntellijExistingRollbackRejection.PHYSICAL_POSTIMAGE_CHANGED -> AddDeclarationRollbackFailure.CONTENT_DIVERGED
        IntellijExistingRollbackRejection.PHYSICAL_SOURCE_UNAVAILABLE ->
            AddDeclarationRollbackFailure.TARGET_UNAVAILABLE
        else -> AddDeclarationRollbackFailure.WRITE_REJECTED
    }

private fun rejected(
    failure: AddDeclarationRollbackFailure,
    reason: IntellijExistingRollbackRejection,
): AddDeclarationRollbackResult.Rejected {
    rollbackLog.warn("Kast exact existing-source rollback rejected: ${reason.name}")
    return AddDeclarationRollbackResult.Rejected(failure)
}

private val rollbackLog: Logger
    get() = Logger.getInstance(IntellijExistingSourceRollback::class.java)

private fun synchronizeDocument(
    project: Project,
    loaded: ExistingRollbackDocument,
    expectedText: String,
    stage: ExistingRollbackDocumentStage,
): ExistingRollbackDocumentSynchronization =
    try {
        VfsUtil.markDirtyAndRefresh(false, false, false, loaded.file)
        onEdt {
            val documents = FileDocumentManager.getInstance()
            when {
                !loaded.file.isValid ->
                    ExistingRollbackDocumentSynchronization.Rejected(
                        AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                        IntellijExistingRollbackRejection.TARGET_INVALIDATED,
                    )
                documents.isDocumentUnsaved(loaded.document) ->
                    ExistingRollbackDocumentSynchronization.Rejected(
                        stage.failure,
                        stage.unsavedReason,
                    )
                else -> {
                    documents.reloadFromDisk(loaded.document, project)
                    PsiDocumentManager.getInstance(project).commitDocument(loaded.document)
                    if (
                        loaded.document.text == expectedText &&
                            !documents.isDocumentUnsaved(loaded.document) &&
                            PsiDocumentManager.getInstance(project).isCommitted(loaded.document)
                    ) {
                        ExistingRollbackDocumentSynchronization.Synchronized
                    } else {
                        ExistingRollbackDocumentSynchronization.Rejected(
                            stage.failure,
                            stage.mismatchReason,
                        )
                    }
                }
            }
        }
    } catch (cancellation: ProcessCanceledException) {
        throw cancellation
    } catch (_: Exception) {
        ExistingRollbackDocumentSynchronization.Rejected(
            AddDeclarationRollbackFailure.WRITE_REJECTED,
            IntellijExistingRollbackRejection.DOCUMENT_SYNCHRONIZATION_FAILED,
        )
    }
