package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A direction derived from a finite rejection; it grants no retry, refresh, or mutation capability. */
@Serializable
enum class ReadRecoveryAction {
    @SerialName("reacquire_authority") REACQUIRE_AUTHORITY,
    @SerialName("restart_read") RESTART_READ,
    @SerialName("correct_request") CORRECT_REQUEST,
    @SerialName("adjust_budget_or_scope") ADJUST_BUDGET_OR_SCOPE,
    @SerialName("wait_for_workspace") WAIT_FOR_WORKSPACE,
    @SerialName("save_source") SAVE_SOURCE,
    @SerialName("report_failure") REPORT_FAILURE,
}

fun SourceReadFailure.recoveryAction(): ReadRecoveryAction =
    when (val rejection = reason()) {
        is SourceReadFailureDetail.RequestRejected -> ReadRecoveryAction.CORRECT_REQUEST
        is SourceReadFailureDetail.ReferenceRejected -> rejection.reason.recoveryAction()
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
        RelationReadRejection.WORKSPACE_INDEX_UNAVAILABLE -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        RelationReadRejection.SCOPE_REJECTED -> ReadRecoveryAction.CORRECT_REQUEST
        RelationReadRejection.OUTSIDE_SCOPE -> ReadRecoveryAction.CORRECT_REQUEST
        RelationReadRejection.AMBIGUOUS_SUBJECT -> ReadRecoveryAction.CORRECT_REQUEST
        RelationReadRejection.COMPILER_IDENTITY_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
        RelationReadRejection.COMPILER_CONTRACT_VIOLATION -> ReadRecoveryAction.REPORT_FAILURE
        RelationReadRejection.REVALIDATION_WRONG_KIND -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_UNRETAINED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_EXPIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_CAPACITY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_WORK_LIMIT_REACHED -> ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE
        RelationReadRejection.REVALIDATION_TIME_LIMIT_REACHED -> ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE
        RelationReadRejection.REVALIDATION_RETIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_CAPTURE_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
        RelationReadRejection.REVALIDATION_WORKSPACE_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_OWNER_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_WORKSPACE_NOT_READY -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        RelationReadRejection.REVALIDATION_BASIS_MOVED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_CONTENT_CHANGED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_CONTENT_UNCOMMITTED -> ReadRecoveryAction.SAVE_SOURCE
        RelationReadRejection.REVALIDATION_SCOPE_REJECTED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_DECLARATION_MISSING -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_UNSUPPORTED_DECLARATION -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_AMBIGUOUS -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        RelationReadRejection.REVALIDATION_COMPILER_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
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
        TraversalRunRejection.WORKSPACE_INDEX_UNAVAILABLE -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        TraversalRunRejection.SCOPE_REJECTED -> ReadRecoveryAction.CORRECT_REQUEST
        TraversalRunRejection.OUTSIDE_SCOPE -> ReadRecoveryAction.CORRECT_REQUEST
        TraversalRunRejection.AMBIGUOUS_SUBJECT -> ReadRecoveryAction.CORRECT_REQUEST
        TraversalRunRejection.RELATION_UNSUPPORTED -> ReadRecoveryAction.CORRECT_REQUEST
        TraversalRunRejection.DEPTH_LIMIT_EXCEEDED -> ReadRecoveryAction.CORRECT_REQUEST
        TraversalRunRejection.COMPILER_IDENTITY_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
        TraversalRunRejection.COMPILER_CONTRACT_VIOLATION -> ReadRecoveryAction.REPORT_FAILURE
        TraversalRunRejection.READER_CONTRACT_VIOLATION -> ReadRecoveryAction.REPORT_FAILURE
        TraversalRunRejection.TRAVERSAL_CONTRACT_VIOLATION -> ReadRecoveryAction.REPORT_FAILURE
        TraversalRunRejection.CONTINUATION_CURSOR_MOVED -> ReadRecoveryAction.RESTART_READ
        TraversalRunRejection.REVALIDATION_WRONG_KIND -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_UNRETAINED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_EXPIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_CAPACITY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_WORK_LIMIT_REACHED -> ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE
        TraversalRunRejection.REVALIDATION_TIME_LIMIT_REACHED -> ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE
        TraversalRunRejection.REVALIDATION_RETIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_CAPTURE_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
        TraversalRunRejection.REVALIDATION_WORKSPACE_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_OWNER_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_WORKSPACE_NOT_READY -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        TraversalRunRejection.REVALIDATION_BASIS_MOVED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_CONTENT_CHANGED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_CONTENT_UNCOMMITTED -> ReadRecoveryAction.SAVE_SOURCE
        TraversalRunRejection.REVALIDATION_SCOPE_REJECTED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_DECLARATION_MISSING -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_UNSUPPORTED_DECLARATION -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_AMBIGUOUS -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        TraversalRunRejection.REVALIDATION_COMPILER_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
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
        is QueryRunRejection.ReferenceRejected -> rejection.reason.recoveryAction()
        is QueryRunRejection.StepReferenceRejected -> rejection.reason.recoveryAction()
        is QueryRunRejection.SourceRejected -> ReadRecoveryAction.CORRECT_REQUEST
        is QueryRunRejection.ExecutionRejected ->
            when (rejection.reason) {
                QueryExecutionRejectionDocument.CONTINUATION_UNAVAILABLE,
                QueryExecutionRejectionDocument.RESULT_UNAVAILABLE,
                QueryExecutionRejectionDocument.RESULT_STALE_BASIS -> ReadRecoveryAction.RESTART_READ
                QueryExecutionRejectionDocument.CONTINUATION_MISMATCH,
                QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE,
                QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE,
                QueryExecutionRejectionDocument.REQUEST_REJECTED,
                QueryExecutionRejectionDocument.BUDGET_REJECTED -> ReadRecoveryAction.CORRECT_REQUEST
                QueryExecutionRejectionDocument.REFERENCE_STALE -> ReadRecoveryAction.REACQUIRE_AUTHORITY
                QueryExecutionRejectionDocument.DISCOVERY_REJECTED,
                QueryExecutionRejectionDocument.INTERNAL_CONTRACT_VIOLATION -> ReadRecoveryAction.REPORT_FAILURE
            }
    }

private fun SourceReferenceFailure.recoveryAction(): ReadRecoveryAction =
    when (this) {
        SourceReferenceFailure.REVALIDATION_WRONG_KIND -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_UNRETAINED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_EXPIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_CAPACITY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_WORK_LIMIT_REACHED -> ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE
        SourceReferenceFailure.REVALIDATION_TIME_LIMIT_REACHED -> ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE
        SourceReferenceFailure.REVALIDATION_RETIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_CAPTURE_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
        SourceReferenceFailure.REVALIDATION_WORKSPACE_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_OWNER_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_WORKSPACE_NOT_READY -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        SourceReferenceFailure.REVALIDATION_BASIS_MOVED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_CONTENT_CHANGED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_CONTENT_UNCOMMITTED -> ReadRecoveryAction.SAVE_SOURCE
        SourceReferenceFailure.REVALIDATION_SCOPE_REJECTED -> ReadRecoveryAction.CORRECT_REQUEST
        SourceReferenceFailure.REVALIDATION_DECLARATION_MISSING -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_UNSUPPORTED_DECLARATION -> ReadRecoveryAction.CORRECT_REQUEST
        SourceReferenceFailure.REVALIDATION_AMBIGUOUS -> ReadRecoveryAction.CORRECT_REQUEST
        SourceReferenceFailure.REVALIDATION_COMPILER_IDENTITY_CHANGED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.REVALIDATION_COMPILER_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
        SourceReferenceFailure.WRONG_FAMILY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.MALFORMED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.INVALID_PAYLOAD_ENCODING -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.PAYLOAD_DIGEST_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.INVALID_DOCUMENT -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.FOREIGN_WORKSPACE -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.INCOMPATIBLE_AUTHORITY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.STALE_AUTHORITY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.UNSUPPORTED_VERSION -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.LIVE_AUTHORITY_REQUIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.UNAVAILABLE -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.TOKEN_TOO_LONG -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.SNAPSHOT_REJECTED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.SELECTOR_REJECTED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        SourceReferenceFailure.SELECTOR_TOO_DEEP -> ReadRecoveryAction.REACQUIRE_AUTHORITY
    }

private fun QueryReferenceRejectionReason.recoveryAction(): ReadRecoveryAction =
    when (this) {
        QueryReferenceRejectionReason.REVALIDATION_WRONG_KIND -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_UNRETAINED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_EXPIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_CAPACITY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_WORK_LIMIT_REACHED -> ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE
        QueryReferenceRejectionReason.REVALIDATION_TIME_LIMIT_REACHED -> ReadRecoveryAction.ADJUST_BUDGET_OR_SCOPE
        QueryReferenceRejectionReason.REVALIDATION_RETIRED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_CAPTURE_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
        QueryReferenceRejectionReason.REVALIDATION_WORKSPACE_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_OWNER_MISMATCH -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_WORKSPACE_NOT_READY -> ReadRecoveryAction.WAIT_FOR_WORKSPACE
        QueryReferenceRejectionReason.REVALIDATION_BASIS_MOVED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_CONTENT_CHANGED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_CONTENT_UNCOMMITTED -> ReadRecoveryAction.SAVE_SOURCE
        QueryReferenceRejectionReason.REVALIDATION_SCOPE_REJECTED -> ReadRecoveryAction.CORRECT_REQUEST
        QueryReferenceRejectionReason.REVALIDATION_DECLARATION_MISSING -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_UNSUPPORTED_DECLARATION -> ReadRecoveryAction.CORRECT_REQUEST
        QueryReferenceRejectionReason.REVALIDATION_AMBIGUOUS -> ReadRecoveryAction.CORRECT_REQUEST
        QueryReferenceRejectionReason.REVALIDATION_COMPILER_IDENTITY_CHANGED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.REVALIDATION_COMPILER_UNAVAILABLE -> ReadRecoveryAction.REPORT_FAILURE
        QueryReferenceRejectionReason.WRONG_KIND -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.MALFORMED -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.INCOMPATIBLE_WORKSPACE -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.STALE_GENERATION -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.STALE_AUTHORITY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.INCOMPATIBLE_AUTHORITY -> ReadRecoveryAction.REACQUIRE_AUTHORITY
        QueryReferenceRejectionReason.INCOMPATIBLE_REFERENCE_VERSION -> ReadRecoveryAction.REACQUIRE_AUTHORITY
    }
