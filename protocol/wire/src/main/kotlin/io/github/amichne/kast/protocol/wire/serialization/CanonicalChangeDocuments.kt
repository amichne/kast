@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ChangePlanResultDocument(
    val planIdentity: String,
    val changes: List<ChangeFilePreviewDocument>,
)

@Serializable
internal data class ChangeFilePreviewDocument(
    val path: String,
    val kind: ChangeFilePreviewKindDocument,
    val diff: String,
)

@Serializable
internal enum class ChangeFilePreviewKindDocument {
    @SerialName("add") ADD,
    @SerialName("delete") DELETE,
    @SerialName("update") UPDATE,
}

@Serializable
internal enum class ChangePlanQualificationDocument {
    @SerialName("optional_evidence_incomplete") OPTIONAL_EVIDENCE_INCOMPLETE
}

@Serializable
internal enum class ChangePlanRejectionDocument {
    @SerialName("workspace_not_ready") WORKSPACE_NOT_READY,
    @SerialName("exact_symbol_required") EXACT_SYMBOL_REQUIRED,
    @SerialName("editable_target_required") EDITABLE_TARGET_REQUIRED,
    @SerialName("relation_read_required") RELATION_READ_REQUIRED,
    @SerialName("topology_build_required") TOPOLOGY_BUILD_REQUIRED,
    @SerialName("required_traversal_incomplete") REQUIRED_TRAVERSAL_INCOMPLETE,
    @SerialName("diagnostic_check_required") DIAGNOSTIC_CHECK_REQUIRED,
    @SerialName("recovery_required") RECOVERY_REQUIRED,
    @SerialName("intent_rejected") INTENT_REJECTED,
}

@Serializable
internal data class ChangeApplyResultDocument(
    val receiptIdentity: String,
    val changes: List<ChangeFilePreviewDocument>,
)

@Serializable
internal enum class ChangeApplyQualificationDocument {
    @SerialName("recovery_required") RECOVERY_REQUIRED
}

@Serializable
internal enum class ChangeApplyRejectionDocument {
    @SerialName("plan_not_found") PLAN_NOT_FOUND,
    @SerialName("root_mismatch") ROOT_MISMATCH,
    @SerialName("generation_stale") GENERATION_STALE,
    @SerialName("content_changed") CONTENT_CHANGED,
    @SerialName("write_scope_rejected") WRITE_SCOPE_REJECTED,
    @SerialName("rolled_back") ROLLED_BACK,
    @SerialName("recovery_required") RECOVERY_REQUIRED,
    @SerialName("resulting_generation_unavailable") RESULTING_GENERATION_UNAVAILABLE,
    @SerialName("obligation_failed") OBLIGATION_FAILED,
    @SerialName("diagnostic_regression") DIAGNOSTIC_REGRESSION,
    @SerialName("semantic_delta_rejected") SEMANTIC_DELTA_REJECTED,
}

@Serializable internal data class ChangeRecoverResultDocument(val state: ChangeRecoveryStateDocument)

@Serializable
internal enum class ChangeRecoveryStateDocument {
    @SerialName("prior_state") PRIOR_STATE,
    @SerialName("rolled_back") ROLLED_BACK,
    @SerialName("recovery_required") RECOVERY_REQUIRED,
}

@Serializable
internal enum class ChangeRecoverQualificationDocument {
    @SerialName("manual_recovery_required") MANUAL_RECOVERY_REQUIRED
}

@Serializable
internal enum class ChangeRecoverRejectionDocument {
    @SerialName("plan_not_found") PLAN_NOT_FOUND,
    @SerialName("journal_unavailable") JOURNAL_UNAVAILABLE,
    @SerialName("recovery_failed") RECOVERY_FAILED,
}
