package io.github.amichne.kast.server.change

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class VerifiedAddDeclarationOperation {
    @SerialName("add-declaration")
    ADD_DECLARATION,
}

@Serializable
enum class VerifiedAddDeclarationPlanStage {
    AWAITING_APPROVAL,
    APPROVED,
    RECOVERY_PREPARED,
    APPLY_ADMITTED,
    APPLIED_UNVERIFIED,
    VERIFIED,
}

@Serializable
enum class VerifiedAddDeclarationDeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ANNOTATION_CLASS,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

@Serializable
enum class VerifiedAddDeclarationApplyOutcome {
    VERIFIED,
    REJECTED,
    RECOVERY_REQUIRED,
    RECONCILIATION_REQUIRED,
}

@Serializable
enum class VerifiedAddDeclarationProgress {
    APPROVAL,
    REVALIDATION,
    RECOVERY_PREPARATION,
    APPLY_ADMISSION,
    SOURCE_APPLICATION,
    WORKSPACE_PUBLICATION,
    POSTCONDITION_VERIFICATION,
}

@Serializable
enum class VerifiedAddDeclarationPlanningFailure {
    WORKSPACE_MISMATCH,
    TARGET_UNAVAILABLE,
    SEMANTIC_PLAN_REJECTED,
    JOURNAL_REJECTED,
}

@Serializable
enum class VerifiedAddDeclarationRejection {
    WORKSPACE_MISMATCH,
    PLAN_NOT_FOUND,
    STALE_PLAN_VERSION,
    PLAN_STATE_INVALID,
    APPROVAL_REJECTED,
    REVALIDATION_REJECTED,
    RECOVERY_PREPARATION_REJECTED,
    APPLY_REJECTED,
    VERIFICATION_REJECTED,
}

@Serializable
enum class VerifiedAddDeclarationRecoveryAction {
    RESTORE_PREIMAGE,
    COMPLETE_POSTIMAGE,
}

@Serializable
enum class VerifiedAddDeclarationReconciliationAction {
    REFRESH_WORKSPACE,
    RETRY_PUBLICATION,
    RETRY_VERIFICATION,
}

@Serializable
data class VerifiedAddDeclarationPlanPreview(
    val targetPath: VerifiedAddDeclarationTargetPath,
    val proposedDeclaration: VerifiedAddDeclarationProposedDeclaration,
    val generation: VerifiedAddDeclarationPublicationGeneration,
)

sealed interface VerifiedAddDeclarationPlanResult {
    @Serializable
    data class Planned(
        val planId: VerifiedAddDeclarationPlanId,
        val planVersion: VerifiedAddDeclarationPlanVersion,
        val preview: VerifiedAddDeclarationPlanPreview,
    ) : VerifiedAddDeclarationPlanResult {
        val stage: VerifiedAddDeclarationPlanStage =
            VerifiedAddDeclarationPlanStage.AWAITING_APPROVAL
        val operation: VerifiedAddDeclarationOperation =
            VerifiedAddDeclarationOperation.ADD_DECLARATION
        val schemaVersion: Int = SCHEMA_VERSION
    }

    @Serializable
    data class Rejected(
        val failure: VerifiedAddDeclarationPlanningFailure,
    ) : VerifiedAddDeclarationPlanResult {
        val operation: VerifiedAddDeclarationOperation =
            VerifiedAddDeclarationOperation.ADD_DECLARATION
        val schemaVersion: Int = SCHEMA_VERSION
    }
}

@Serializable
data class VerifiedAddDeclarationPublication(
    val generation: VerifiedAddDeclarationPublicationGeneration,
    val workspaceStateIdentity: VerifiedAddDeclarationWorkspaceStateIdentity,
)

@Serializable
data class VerifiedAddDeclarationDeclarationIdentity(
    val targetPath: VerifiedAddDeclarationTargetPath,
    val sourceRange: VerifiedAddDeclarationSourceRange,
    val packageName: VerifiedAddDeclarationPackageName,
    val declarationName: VerifiedAddDeclarationDeclarationName,
    val declarationKind: VerifiedAddDeclarationDeclarationKind,
)

sealed interface VerifiedAddDeclarationApplyResult {
    sealed interface Incomplete : VerifiedAddDeclarationApplyResult {
        val planId: VerifiedAddDeclarationPlanId
        val planVersion: VerifiedAddDeclarationPlanVersion
        val stage: VerifiedAddDeclarationPlanStage
        val progress: VerifiedAddDeclarationProgress
        val outcome: VerifiedAddDeclarationApplyOutcome
        val operation: VerifiedAddDeclarationOperation
        val schemaVersion: Int
    }

    @Serializable
    data class Verified(
        val planId: VerifiedAddDeclarationPlanId,
        val planVersion: VerifiedAddDeclarationPlanVersion,
        val publication: VerifiedAddDeclarationPublication,
        val identity: VerifiedAddDeclarationDeclarationIdentity,
        val postimageSha256: VerifiedAddDeclarationPostimageSha256,
    ) : VerifiedAddDeclarationApplyResult {
        val outcome: VerifiedAddDeclarationApplyOutcome =
            VerifiedAddDeclarationApplyOutcome.VERIFIED
        val operation: VerifiedAddDeclarationOperation =
            VerifiedAddDeclarationOperation.ADD_DECLARATION
        val schemaVersion: Int = SCHEMA_VERSION
    }

    @Serializable
    data class Rejected(
        override val planId: VerifiedAddDeclarationPlanId,
        override val planVersion: VerifiedAddDeclarationPlanVersion,
        override val stage: VerifiedAddDeclarationPlanStage,
        override val progress: VerifiedAddDeclarationProgress,
        val failure: VerifiedAddDeclarationRejection,
    ) : Incomplete {
        override val outcome: VerifiedAddDeclarationApplyOutcome =
            VerifiedAddDeclarationApplyOutcome.REJECTED
        override val operation: VerifiedAddDeclarationOperation =
            VerifiedAddDeclarationOperation.ADD_DECLARATION
        override val schemaVersion: Int = SCHEMA_VERSION
    }

    @Serializable
    data class RecoveryRequired(
        override val planId: VerifiedAddDeclarationPlanId,
        override val planVersion: VerifiedAddDeclarationPlanVersion,
        override val stage: VerifiedAddDeclarationPlanStage,
        override val progress: VerifiedAddDeclarationProgress,
        val action: VerifiedAddDeclarationRecoveryAction,
    ) : Incomplete {
        override val outcome: VerifiedAddDeclarationApplyOutcome =
            VerifiedAddDeclarationApplyOutcome.RECOVERY_REQUIRED
        override val operation: VerifiedAddDeclarationOperation =
            VerifiedAddDeclarationOperation.ADD_DECLARATION
        override val schemaVersion: Int = SCHEMA_VERSION
    }

    @Serializable
    data class ReconciliationRequired(
        override val planId: VerifiedAddDeclarationPlanId,
        override val planVersion: VerifiedAddDeclarationPlanVersion,
        override val stage: VerifiedAddDeclarationPlanStage,
        override val progress: VerifiedAddDeclarationProgress,
        val action: VerifiedAddDeclarationReconciliationAction,
    ) : Incomplete {
        override val outcome: VerifiedAddDeclarationApplyOutcome =
            VerifiedAddDeclarationApplyOutcome.RECONCILIATION_REQUIRED
        override val operation: VerifiedAddDeclarationOperation =
            VerifiedAddDeclarationOperation.ADD_DECLARATION
        override val schemaVersion: Int = SCHEMA_VERSION
    }
}
