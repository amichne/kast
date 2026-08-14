package io.github.amichne.kast.server.change

import io.github.amichne.kast.api.contract.NormalizedPath

sealed interface VerifiedAddDeclarationBinding {
    data object Unavailable : VerifiedAddDeclarationBinding

    data class Native(
        val operations: NativeVerifiedAddDeclarationOperations,
    ) : VerifiedAddDeclarationBinding
}

interface NativeVerifiedAddDeclarationOperations {
    /**
     * Effect transition: `VerifiedAddDeclarationPlanRequest -> VerifiedAddDeclarationPlanResult`.
     *
     * A planned result carries the native planner's canonical journal identity, initial state version,
     * and exact preview. The closed result family retains expected planning rejection. IntelliJ,
     * filesystem, and journal effects are permitted only inside the injected indexer implementation.
     */
    suspend fun plan(request: VerifiedAddDeclarationPlanRequest): VerifiedAddDeclarationPlanResult

    /**
     * Effect transition: `VerifiedAddDeclarationApplyRequest -> VerifiedAddDeclarationApplyResult`.
     *
     * A verified result carries the journal's terminal version and post-publication PSI/K2 receipt.
     * The closed result family retains rejection, recovery, and reconciliation states. IntelliJ,
     * filesystem, workspace transition, and journal effects are permitted only inside the injected
     * indexer implementation.
     */
    suspend fun apply(request: VerifiedAddDeclarationApplyRequest): VerifiedAddDeclarationApplyResult
}

/**
 * Proof aggregate of one admitted operation-specific planning request.
 *
 * Construction accepts only already-refined path and declaration types, so indexer composition tests
 * can construct the capability input without bypassing raw JSON validation.
 */
class VerifiedAddDeclarationPlanRequest(
    val workspaceRoot: NormalizedPath,
    val targetPath: VerifiedAddDeclarationTargetPath,
    val proposedDeclaration: VerifiedAddDeclarationProposedDeclaration,
)

/** Proof aggregate of an already-refined approval actor and its canonical evidence identity. */
class VerifiedAddDeclarationApprovalEvidence(
    val approvedBy: VerifiedAddDeclarationApprovedBy,
    val evidenceSha256: VerifiedAddDeclarationApprovalEvidenceSha256,
)

/**
 * Proof aggregate of one admitted durable apply request.
 *
 * Construction accepts only already-refined lifecycle and approval types; raw wire primitives remain
 * owned by the server admission boundary.
 */
class VerifiedAddDeclarationApplyRequest(
    val workspaceRoot: NormalizedPath,
    val planId: VerifiedAddDeclarationPlanId,
    val expectedVersion: VerifiedAddDeclarationPlanVersion,
    val approvalEvidence: VerifiedAddDeclarationApprovalEvidence,
)

enum class VerifiedAddDeclarationRequestFailure {
    MALFORMED_WIRE_REQUEST,
    WORKSPACE_ROOT_NOT_NORMALIZED_ABSOLUTE,
    TARGET_PATH_NOT_NORMALIZED_ABSOLUTE_KOTLIN,
    TARGET_OUTSIDE_WORKSPACE,
    PROPOSED_DECLARATION_NOT_NORMALIZED,
    PLAN_ID_NOT_CANONICAL,
    EXPECTED_VERSION_NEGATIVE,
    APPROVED_BY_BLANK,
    APPROVED_BY_NOT_TRIMMED,
    APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL,
}

sealed interface VerifiedAddDeclarationRequestAdmission<out T> {
    data class Admitted<T>(val request: T) : VerifiedAddDeclarationRequestAdmission<T>

    data class Rejected(
        val failure: VerifiedAddDeclarationRequestFailure,
    ) : VerifiedAddDeclarationRequestAdmission<Nothing>
}
