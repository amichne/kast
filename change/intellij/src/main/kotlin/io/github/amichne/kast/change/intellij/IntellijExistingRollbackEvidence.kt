package io.github.amichne.kast.change.intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.change.recovery.AddDeclarationRollbackFailure
import org.jetbrains.kotlin.psi.KtFile

/** Reason-only physical diagnostic; raw paths and source bytes never enter the IDE log. */
internal enum class IntellijExistingRollbackRejection {
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

internal enum class ExistingRollbackDocumentStage(
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

internal sealed interface ExistingRollbackDocumentSynchronization {
    data object Synchronized : ExistingRollbackDocumentSynchronization

    data class Rejected(
        val failure: AddDeclarationRollbackFailure,
        val reason: IntellijExistingRollbackRejection,
    ) : ExistingRollbackDocumentSynchronization
}

internal sealed interface ExistingRollbackAdmission {
    data object Ready : ExistingRollbackAdmission

    data class Rejected(val reason: IntellijExistingRollbackRejection) : ExistingRollbackAdmission
}

internal sealed interface ExistingRollbackWrite {
    data object Written : ExistingRollbackWrite

    data class Rejected(val reason: IntellijExistingRollbackRejection) : ExistingRollbackWrite
}

/** Request-local read-action proof required before the EDT write command can restore a source. */
internal data class ExistingRollbackTarget(
    val psi: KtFile,
    val loaded: ExistingRollbackDocument,
)

internal data class ExistingRollbackDocument(val file: VirtualFile, val document: Document)

internal data class IntellijExistingRecoveryInput(val path: String, val preimage: String, val postimage: String)
