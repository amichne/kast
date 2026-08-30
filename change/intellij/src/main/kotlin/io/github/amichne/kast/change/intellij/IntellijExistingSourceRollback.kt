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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.apply.MutationPreconditionAtIntellijBoundary
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackResult
import org.jetbrains.kotlin.psi.KtFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Exact recovery primitive for a source that existed before mutation. */
internal class IntellijExistingSourceRollback(
    private val project: Project,
) {
    private val log = Logger.getInstance(IntellijExistingSourceRollback::class.java)

    /**
     * Proof transition: `(MutationAuthority, ExistingPrecondition, ByteArray) ->
     * AddDeclarationRollbackResult`.
     *
     * RolledBack establishes that both the physical file and live IntelliJ document equal the
     * exact durable recovery preimage, overwriting only the authority's exact postimage.
     * [AddDeclarationRollbackFailure] closes divergent or unavailable state. Raw recovery bytes
     * remain inside this adapter boundary.
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
        val path = Path.of(authority.source.path.value)
        val current = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            return rejected(
                AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                IntellijExistingRollbackRejection.PHYSICAL_SOURCE_UNAVAILABLE,
            )
        }
        val physicalState = when (existingRollbackPhysicalState(
            current,
            preimage,
            authority.postimageBytesAtIntellijBoundary(),
        )) {
            ExistingRollbackPhysicalState.Preimage -> ExistingRollbackPhysicalState.Preimage
            ExistingRollbackPhysicalState.Postimage -> ExistingRollbackPhysicalState.Postimage
            ExistingRollbackPhysicalState.Diverged -> return rejected(
                AddDeclarationRollbackFailure.CONTENT_DIVERGED,
                IntellijExistingRollbackRejection.CURRENT_POSTIMAGE_MISMATCH,
            )
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                   ?: return rejected(
                       AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                       IntellijExistingRollbackRejection.VIRTUAL_FILE_UNAVAILABLE,
                   )
        val document = ReadAction.computeBlocking<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
        } ?: return rejected(
            AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
            IntellijExistingRollbackRejection.PSI_OR_DOCUMENT_UNAVAILABLE,
        )
        val expectedDocumentText = when (physicalState) {
            ExistingRollbackPhysicalState.Preimage -> expected.text
            ExistingRollbackPhysicalState.Postimage -> authority.postimageTextAtIntellijBoundary()
            ExistingRollbackPhysicalState.Diverged -> error("Divergent physical state escaped")
        }
        val documentStage = when (physicalState) {
            ExistingRollbackPhysicalState.Preimage -> ExistingRollbackDocumentStage.ALREADY_PREIMAGE
            ExistingRollbackPhysicalState.Postimage -> ExistingRollbackDocumentStage.POSTIMAGE
            ExistingRollbackPhysicalState.Diverged -> error("Divergent physical state escaped")
        }
        when (val synchronized = synchronizeDocument(
            file,
            document,
            expectedDocumentText,
            documentStage,
        )) {
            ExistingRollbackDocumentSynchronization.Synchronized -> Unit
            is ExistingRollbackDocumentSynchronization.Rejected -> return rejected(
                synchronized.failure,
                synchronized.reason,
            )
        }
        if (physicalState == ExistingRollbackPhysicalState.Preimage) {
            return AddDeclarationRollbackResult.RolledBack
        }
        val target = ReadAction.computeBlocking<ExistingRollbackTarget?, RuntimeException> {
            val psi = PsiManager.getInstance(project).findFile(file) as? KtFile
                ?: return@computeBlocking null
            if (FileDocumentManager.getInstance().getDocument(file) !== document) {
                return@computeBlocking null
            }
            ExistingRollbackTarget(psi, document)
        } ?: return rejected(
            AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
            IntellijExistingRollbackRejection.PSI_OR_DOCUMENT_UNAVAILABLE,
        )
        return try {
            val write = onEdt {
                if (!file.isValid || !target.psi.isValid) {
                    return@onEdt ExistingRollbackWrite.Rejected(
                        IntellijExistingRollbackRejection.TARGET_INVALIDATED,
                    )
                }
                if (target.document.text != authority.postimageTextAtIntellijBoundary()) {
                    return@onEdt ExistingRollbackWrite.Rejected(
                        IntellijExistingRollbackRejection.DOCUMENT_POSTIMAGE_CHANGED,
                    )
                }
                val immediatePhysical = try {
                    Files.readAllBytes(path)
                } catch (_: Exception) {
                    return@onEdt ExistingRollbackWrite.Rejected(
                        IntellijExistingRollbackRejection.PHYSICAL_SOURCE_UNAVAILABLE,
                    )
                }
                if (!immediatePhysical.contentEquals(authority.postimageBytesAtIntellijBoundary())) {
                    return@onEdt ExistingRollbackWrite.Rejected(
                        IntellijExistingRollbackRejection.PHYSICAL_POSTIMAGE_CHANGED,
                    )
                }
                WriteCommandAction.writeCommandAction(project, target.psi)
                    .withName("Kast rollback semantic change")
                    .compute<Unit, RuntimeException> {
                        file.setBinaryContent(preimage)
                    }
                ExistingRollbackWrite.Written
            }
            when (write) {
                ExistingRollbackWrite.Written -> {
                    when (val synchronized = synchronizeDocument(
                        file,
                        document,
                        expected.text,
                        ExistingRollbackDocumentStage.PREIMAGE,
                    )) {
                        ExistingRollbackDocumentSynchronization.Synchronized -> Unit
                        is ExistingRollbackDocumentSynchronization.Rejected -> return rejected(
                            synchronized.failure,
                            synchronized.reason,
                        )
                    }
                    val restored = Files.readAllBytes(path)
                    if (restored.contentEquals(preimage)) {
                        AddDeclarationRollbackResult.RolledBack
                    } else {
                        rejected(
                            AddDeclarationRollbackFailure.WRITE_REJECTED,
                            if (restored.contentEquals(
                                    authority.postimageBytesAtIntellijBoundary(),
                                )
                            ) {
                                IntellijExistingRollbackRejection.POST_WRITE_POSTIMAGE_UNCHANGED
                            } else {
                                IntellijExistingRollbackRejection.POST_WRITE_CONTENT_DIVERGED
                            },
                        )
                    }
                }
                is ExistingRollbackWrite.Rejected -> rejected(
                    when (write.reason) {
                        IntellijExistingRollbackRejection.DOCUMENT_POSTIMAGE_CHANGED,
                        IntellijExistingRollbackRejection.PHYSICAL_POSTIMAGE_CHANGED,
                        -> AddDeclarationRollbackFailure.CONTENT_DIVERGED
                        IntellijExistingRollbackRejection.PHYSICAL_SOURCE_UNAVAILABLE ->
                            AddDeclarationRollbackFailure.TARGET_UNAVAILABLE
                        else -> AddDeclarationRollbackFailure.WRITE_REJECTED
                    },
                    write.reason,
                )
            }
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (failure: Exception) {
            log.warn(
                "Kast exact existing-source rollback failed during the IntelliJ write boundary",
                failure,
            )
            rejected(
                AddDeclarationRollbackFailure.WRITE_REJECTED,
                IntellijExistingRollbackRejection.WRITE_BOUNDARY_FAILED,
            )
        }
    }

    private fun synchronizeDocument(
        file: VirtualFile,
        document: Document,
        expectedText: String,
        stage: ExistingRollbackDocumentStage,
    ): ExistingRollbackDocumentSynchronization = try {
        VfsUtil.markDirtyAndRefresh(false, false, false, file)
        onEdt {
            val documents = FileDocumentManager.getInstance()
            when {
                !file.isValid -> ExistingRollbackDocumentSynchronization.Rejected(
                    AddDeclarationRollbackFailure.TARGET_UNAVAILABLE,
                    IntellijExistingRollbackRejection.TARGET_INVALIDATED,
                )
                documents.isDocumentUnsaved(document) ->
                    ExistingRollbackDocumentSynchronization.Rejected(
                        stage.failure,
                        stage.unsavedReason,
                    )
                else -> {
                    documents.reloadFromDisk(document, project)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    if (document.text == expectedText) {
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
    } catch (failure: Exception) {
        log.warn(
            "Kast exact existing-source rollback failed while synchronizing the IntelliJ document",
            failure,
        )
        ExistingRollbackDocumentSynchronization.Rejected(
            AddDeclarationRollbackFailure.WRITE_REJECTED,
            IntellijExistingRollbackRejection.DOCUMENT_SYNCHRONIZATION_FAILED,
        )
    }

    private fun rejected(
        failure: AddDeclarationRollbackFailure,
        reason: IntellijExistingRollbackRejection,
    ): AddDeclarationRollbackResult.Rejected {
        log.warn("Kast exact existing-source rollback rejected: ${reason.name}")
        return AddDeclarationRollbackResult.Rejected(failure)
    }
}

/** Reason-only physical diagnostic; raw paths and source bytes never enter the IDE log. */
private enum class IntellijExistingRollbackRejection {
    PREIMAGE_AUTHORITY_MISMATCH,
    PHYSICAL_SOURCE_UNAVAILABLE,
    PHYSICAL_POSTIMAGE_CHANGED,
    CURRENT_POSTIMAGE_MISMATCH,
    VIRTUAL_FILE_UNAVAILABLE,
    PSI_OR_DOCUMENT_UNAVAILABLE,
    DOCUMENT_UNSAVED,
    DOCUMENT_POSTIMAGE_MISMATCH,
    DOCUMENT_POSTIMAGE_CHANGED,
    DOCUMENT_PREIMAGE_MISMATCH,
    DOCUMENT_REMAINS_UNSAVED,
    DOCUMENT_SYNCHRONIZATION_FAILED,
    TARGET_INVALIDATED,
    POST_WRITE_POSTIMAGE_UNCHANGED,
    POST_WRITE_CONTENT_DIVERGED,
    WRITE_BOUNDARY_FAILED,
}

private enum class ExistingRollbackDocumentStage(
    val failure: AddDeclarationRollbackFailure,
    val unsavedReason: IntellijExistingRollbackRejection,
    val mismatchReason: IntellijExistingRollbackRejection,
) {
    ALREADY_PREIMAGE(
        AddDeclarationRollbackFailure.CONTENT_DIVERGED,
        IntellijExistingRollbackRejection.DOCUMENT_UNSAVED,
        IntellijExistingRollbackRejection.DOCUMENT_PREIMAGE_MISMATCH,
    ),
    POSTIMAGE(
        AddDeclarationRollbackFailure.CONTENT_DIVERGED,
        IntellijExistingRollbackRejection.DOCUMENT_UNSAVED,
        IntellijExistingRollbackRejection.DOCUMENT_POSTIMAGE_MISMATCH,
    ),
    PREIMAGE(
        AddDeclarationRollbackFailure.WRITE_REJECTED,
        IntellijExistingRollbackRejection.DOCUMENT_REMAINS_UNSAVED,
        IntellijExistingRollbackRejection.DOCUMENT_PREIMAGE_MISMATCH,
    ),
}

private sealed interface ExistingRollbackDocumentSynchronization {
    data object Synchronized : ExistingRollbackDocumentSynchronization

    data class Rejected(
        val failure: AddDeclarationRollbackFailure,
        val reason: IntellijExistingRollbackRejection,
    ) : ExistingRollbackDocumentSynchronization
}

private sealed interface ExistingRollbackWrite {
    data object Written : ExistingRollbackWrite

    data class Rejected(
        val reason: IntellijExistingRollbackRejection,
    ) : ExistingRollbackWrite
}

internal enum class ExistingRollbackPhysicalState {
    Preimage,
    Postimage,
    Diverged,
}

internal fun existingRollbackPhysicalState(
    current: ByteArray,
    preimage: ByteArray,
    postimage: ByteArray,
): ExistingRollbackPhysicalState = when {
    current.contentEquals(preimage) -> ExistingRollbackPhysicalState.Preimage
    current.contentEquals(postimage) -> ExistingRollbackPhysicalState.Postimage
    else -> ExistingRollbackPhysicalState.Diverged
}

/** Request-local read-action proof required before the EDT write command can restore a source. */
private data class ExistingRollbackTarget(
    val psi: KtFile,
    val document: Document,
)
