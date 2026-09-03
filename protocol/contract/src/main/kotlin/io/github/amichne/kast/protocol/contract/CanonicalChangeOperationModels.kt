package io.github.amichne.kast.protocol.contract

/** Closed public mutation intent; no generic edit variant exists. */
sealed interface ChangeIntentDocument {
    data class AddFile(
        val relativePath: ProtocolText,
        val content: ProtocolText,
    ) : ChangeIntentDocument

    data class AddDeclaration(
        val exactTarget: ProtocolText,
        val declaration: ProtocolText,
    ) : ChangeIntentDocument

    data class ReplaceDeclaration(
        val exactTarget: ProtocolText,
        val replacement: ProtocolText,
    ) : ChangeIntentDocument

    data class RenameSymbol(
        val exactTarget: ProtocolText,
        val newName: ProtocolText,
    ) : ChangeIntentDocument
}

data class ChangePlanRequest(
    val intent: ChangeIntentDocument,
) : OperationRequest

data class ChangePlanResult(
    val planIdentity: ProtocolText,
) : OperationResult

enum class ChangePlanQualification : OperationQualification {
    OPTIONAL_EVIDENCE_INCOMPLETE,
}

enum class ChangePlanRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    EXACT_SYMBOL_REQUIRED,
    EDITABLE_TARGET_REQUIRED,
    RELATION_READ_REQUIRED,
    TOPOLOGY_BUILD_REQUIRED,
    REQUIRED_TRAVERSAL_INCOMPLETE,
    DIAGNOSTIC_CHECK_REQUIRED,
    RECOVERY_REQUIRED,
    INTENT_REJECTED,
}

data class ChangeApplyRequest(
    val planIdentity: ProtocolText,
) : OperationRequest

data class ChangeApplyResult(
    val receiptIdentity: ProtocolText,
) : OperationResult

enum class ChangeApplyQualification : OperationQualification {
    RECOVERY_REQUIRED,
}

enum class ChangeApplyRejection : OperationRejection {
    PLAN_NOT_FOUND,
    ROOT_MISMATCH,
    GENERATION_STALE,
    CONTENT_CHANGED,
    WRITE_SCOPE_REJECTED,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
    RESULTING_GENERATION_UNAVAILABLE,
    OBLIGATION_FAILED,
    DIAGNOSTIC_REGRESSION,
    SEMANTIC_DELTA_REJECTED,
}

data class ChangeRecoverRequest(
    val planIdentity: ProtocolText,
) : OperationRequest

data class ChangeRecoverResult(
    val state: ChangeRecoveryDocumentState,
) : OperationResult

enum class ChangeRecoveryDocumentState {
    PRIOR_STATE,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
}

enum class ChangeRecoverQualification : OperationQualification {
    MANUAL_RECOVERY_REQUIRED,
}

enum class ChangeRecoverRejection : OperationRejection {
    PLAN_NOT_FOUND,
    JOURNAL_UNAVAILABLE,
    RECOVERY_FAILED,
}
