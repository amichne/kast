package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A finite direction; it authorizes no retry, setting change, refresh or epoch migration. */
@Serializable
enum class DiagnosticRecoveryAction {
    @SerialName("increase_execution_budget") INCREASE_EXECUTION_BUDGET,
    @SerialName("restart_read") RESTART_READ,
    @SerialName("correct_request") CORRECT_REQUEST,
    @SerialName("wait_for_workspace") WAIT_FOR_WORKSPACE,
    @SerialName("adjust_retention_policy") ADJUST_RETENTION_POLICY,
    @SerialName("report_failure") REPORT_FAILURE,
}

fun DiagnosticCheckFailure.diagnosticRecoveryAction(): DiagnosticRecoveryAction =
    when (reason()) {
        DiagnosticCheckRejection.EXECUTION_TIME_GRANT_TOO_SMALL,
        DiagnosticCheckRejection.ENUMERATION_WORK_GRANT_TOO_SMALL,
        DiagnosticCheckRejection.ENUMERATION_TIME_GRANT_TOO_SMALL,
        DiagnosticCheckRejection.COMPILER_UNIT_GRANT_TOO_SMALL,
        DiagnosticCheckRejection.OUTPUT_GRANT_TOO_SMALL -> DiagnosticRecoveryAction.INCREASE_EXECUTION_BUDGET
        DiagnosticCheckRejection.CONTINUATION_UNAVAILABLE,
        DiagnosticCheckRejection.STALE_CONTINUATION,
        DiagnosticCheckRejection.STALE_GENERATION,
        DiagnosticCheckRejection.WORKSPACE_ROOT_MISMATCH -> DiagnosticRecoveryAction.RESTART_READ
        DiagnosticCheckRejection.CONTINUATION_REQUEST_MISMATCH,
        DiagnosticCheckRejection.SCOPE_REJECTED,
        DiagnosticCheckRejection.SCOPE_EMPTY -> DiagnosticRecoveryAction.CORRECT_REQUEST
        DiagnosticCheckRejection.WORKSPACE_NOT_READY,
        DiagnosticCheckRejection.WORKSPACE_INDEX_UNAVAILABLE -> DiagnosticRecoveryAction.WAIT_FOR_WORKSPACE
        DiagnosticCheckRejection.CONTINUATION_CAPACITY_EXCEEDED,
        DiagnosticCheckRejection.ENUMERATION_RETENTION_EXCEEDED -> DiagnosticRecoveryAction.ADJUST_RETENTION_POLICY
        DiagnosticCheckRejection.ENUMERATION_INDEX_MODE_UNSUPPORTED,
        DiagnosticCheckRejection.COMPILER_CONTRACT_VIOLATION,
        DiagnosticCheckRejection.SCOPE_LIMIT_EXCEEDED,
        DiagnosticCheckRejection.SCOPE_UNAVAILABLE -> DiagnosticRecoveryAction.REPORT_FAILURE
    }
