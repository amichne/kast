package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A direction derived from a finite rejection; it grants no retry, refresh, or mutation capability. */
@Serializable
enum class ReadRecoveryAction {
    @SerialName("reacquire_authority") REACQUIRE_AUTHORITY,
    @SerialName("restart_read") RESTART_READ,
    @SerialName("correct_request") CORRECT_REQUEST,
    @SerialName("wait_for_workspace") WAIT_FOR_WORKSPACE,
    @SerialName("save_source") SAVE_SOURCE,
    @SerialName("report_failure") REPORT_FAILURE,
}

fun SourceReadFailure.recoveryAction(): ReadRecoveryAction =
    when (reason()) {
        is SourceReadFailureDetail.RequestRejected -> ReadRecoveryAction.CORRECT_REQUEST
        is SourceReadFailureDetail.ReferenceRejected -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        is SourceReadFailureDetail.InternalContractFailure -> ReadRecoveryAction.REPORT_FAILURE
        SourceReadRejection.WORKSPACE_NOT_READY -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        SourceReadRejection.WORKSPACE_ROOT_MISMATCH,
        SourceReadRejection.STALE_GENERATION,
        SourceReadRejection.SOURCE_STATE_MISMATCH,
        SourceReadRejection.CANDIDATE_STALE,
        SourceReadRejection.SOURCE_SELECTOR_STALE,
        SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH,
        SourceReadRejection.SOURCE_UNAVAILABLE,
        SourceReadRejection.ANCHOR_NOT_FOUND -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReadRejection.DOCUMENT_DIRTY,
        SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED -> ReadRecoveryAction.SAVE_SOURCE
        SourceReadRejection.OUTSIDE_SOURCE_SCOPE,
        SourceReadRejection.AMBIGUOUS_ANCHOR,
        SourceReadRejection.REGION_NOT_APPLICABLE,
        SourceReadRejection.REGION_ABSENT,
        SourceReadRejection.CONTINUATION_REQUEST_MISMATCH -> ReadRecoveryAction.CORRECT_REQUEST
        SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE,
        SourceReadRejection.CONTRACT_VIOLATION -> ReadRecoveryAction.REPORT_FAILURE
        SourceReadRejection.CONTINUATION_UNAVAILABLE -> ReadRecoveryAction.RESTART_READ
    }

fun RelationReadFailure.recoveryAction(): ReadRecoveryAction =
    when (reason()) {
        RelationReadRejection.WORKSPACE_NOT_READY -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        RelationReadRejection.SELECTOR_WRONG_KIND,
        RelationReadRejection.SELECTOR_MALFORMED,
        RelationReadRejection.SELECTOR_WORKSPACE_MISMATCH,
        RelationReadRejection.SELECTOR_STALE,
        RelationReadRejection.CONTINUATION_MALFORMED,
        RelationReadRejection.CONTINUATION_GENERATION_MISMATCH,
        RelationReadRejection.CONTINUATION_CURSOR_MOVED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.RELATION_UNSUPPORTED,
        RelationReadRejection.CONTINUATION_REQUEST_MISMATCH,
        RelationReadRejection.CONTINUATION_SUBJECT_MISMATCH,
        RelationReadRejection.CONTINUATION_RELATION_MISMATCH,
        RelationReadRejection.CONTINUATION_SCOPE_MISMATCH -> ReadRecoveryAction.CORRECT_REQUEST
        RelationReadRejection.CONTINUATION_UNAVAILABLE -> ReadRecoveryAction.RESTART_READ
    }

fun TraversalRunFailure.recoveryAction(): ReadRecoveryAction =
    when (reason()) {
        TraversalRunRejection.WORKSPACE_NOT_READY -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        TraversalRunRejection.SELECTOR_WRONG_KIND,
        TraversalRunRejection.SELECTOR_MALFORMED,
        TraversalRunRejection.SELECTOR_WORKSPACE_MISMATCH,
        TraversalRunRejection.SELECTOR_STALE,
        TraversalRunRejection.CONTINUATION_MALFORMED,
        TraversalRunRejection.CONTINUATION_GENERATION_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.CONTINUATION_REQUEST_MISMATCH,
        TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED,
        TraversalRunRejection.PLAN_REJECTED,
        TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH,
        TraversalRunRejection.CONTINUATION_RELATION_MISMATCH,
        TraversalRunRejection.CONTINUATION_SCOPE_MISMATCH -> ReadRecoveryAction.CORRECT_REQUEST
        TraversalRunRejection.CONTINUATION_UNAVAILABLE -> ReadRecoveryAction.RESTART_READ
    }

fun QueryRunFailure.recoveryAction(): ReadRecoveryAction =
    when (val rejection = reason()) {
        QueryRunRejection.WorkspaceNotReady -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        is QueryRunRejection.ReferenceRejected -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        is QueryRunRejection.PlanRejected,
        is QueryRunRejection.SourceRejected -> ReadRecoveryAction.CORRECT_REQUEST
        is QueryRunRejection.ExecutionRejected ->
            when (rejection.reason) {
                QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE -> ReadRecoveryAction.RESTART_READ
                QueryExecutionRejectionDocument.CONTINUATION_MISMATCH,
                QueryExecutionRejectionDocument.REQUEST_REJECTED,
                QueryExecutionRejectionDocument.BUDGET_REJECTED -> ReadRecoveryAction.CORRECT_REQUEST
                QueryExecutionRejectionDocument.REFERENCE_STALE -> ReadRecoveryAction.REACQUIRE_AUTHORITY
                QueryExecutionRejectionDocument.DISCOVERY_REJECTED,
                QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION -> ReadRecoveryAction.REPORT_FAILURE
            }
    }
