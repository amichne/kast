package io.github.amichne.kast.server.change

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.AddFilePlanResult
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.ExactAddFileProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest

enum class VerifiedAddFileValueFailure {
    TARGET_NOT_NORMALIZED_ABSOLUTE_KOTLIN,
    CONTENT_NOT_NORMALIZED_KOTLIN,
    PLAN_ID_NOT_CANONICAL,
    RECOVERY_ID_NOT_CANONICAL,
    PLAN_VERSION_NEGATIVE,
    APPROVED_BY_NOT_TRIMMED_NON_BLANK,
    APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL,
    APPLY_MODE_UNSUPPORTED,
}

sealed interface VerifiedAddFileRefinement<out T> {
    data class Refined<T>(val value: T) : VerifiedAddFileRefinement<T>
    data class Rejected(val failure: VerifiedAddFileValueFailure) : VerifiedAddFileRefinement<Nothing>
}

sealed interface VerifiedAddFileAdmission<out T> {
    data class Admitted<T>(val value: T) : VerifiedAddFileAdmission<T>
    data class Rejected(val failure: VerifiedAddFileFailure) : VerifiedAddFileAdmission<Nothing>
}

@JvmInline
value class VerifiedAddFileTargetPath private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> VerifiedAddFileRefinement<VerifiedAddFileTargetPath>`.
         *
         * Establishes a normalized absolute Kotlin target path. The closed expected failure is
         * [VerifiedAddFileValueFailure.TARGET_NOT_NORMALIZED_ABSOLUTE_KOTLIN]. Raw extraction is
         * permitted only at JSON and IntelliJ filesystem boundaries.
         */
        fun refine(raw: String): VerifiedAddFileRefinement<VerifiedAddFileTargetPath> {
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return VerifiedAddFileRefinement.Rejected(VerifiedAddFileValueFailure.TARGET_NOT_NORMALIZED_ABSOLUTE_KOTLIN)
            }
            return if (
                path.isAbsolute &&
                path.normalize() == path &&
                path.nameCount > 0 &&
                path.fileName.toString().endsWith(".kt")
            ) {
                VerifiedAddFileRefinement.Refined(VerifiedAddFileTargetPath(path.toString()))
            } else {
                VerifiedAddFileRefinement.Rejected(VerifiedAddFileValueFailure.TARGET_NOT_NORMALIZED_ABSOLUTE_KOTLIN)
            }
        }
    }
}

@JvmInline
value class VerifiedAddFileContent private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> VerifiedAddFileRefinement<VerifiedAddFileContent>`.
         *
         * Establishes non-blank LF-normalized Kotlin source text. The closed expected failure is
         * [VerifiedAddFileValueFailure.CONTENT_NOT_NORMALIZED_KOTLIN]. Raw extraction is permitted
         * only at JSON, secure content-read, and IntelliJ file-creation boundaries.
         */
        fun refine(raw: String): VerifiedAddFileRefinement<VerifiedAddFileContent> =
            if (raw.isNotBlank() && '\r' !in raw) {
                VerifiedAddFileRefinement.Refined(VerifiedAddFileContent(raw))
            } else {
                VerifiedAddFileRefinement.Rejected(
                    VerifiedAddFileValueFailure.CONTENT_NOT_NORMALIZED_KOTLIN,
                )
            }
    }
}

@JvmInline
value class VerifiedAddFilePlanId private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> VerifiedAddFileRefinement<VerifiedAddFilePlanId>`.
         *
         * Establishes the distinct canonical `af-`-prefixed lowercase SHA-256 identity of one
         * add-file plan. The closed expected failure is
         * [VerifiedAddFileValueFailure.PLAN_ID_NOT_CANONICAL]. Raw extraction is permitted only at
         * JSON, persistence, and digest boundaries.
         */
        fun refine(raw: String): VerifiedAddFileRefinement<VerifiedAddFilePlanId> =
            if (raw.matches(ADD_FILE_PLAN_ID)) {
                VerifiedAddFileRefinement.Refined(VerifiedAddFilePlanId(raw))
            } else {
                VerifiedAddFileRefinement.Rejected(VerifiedAddFileValueFailure.PLAN_ID_NOT_CANONICAL)
            }
    }
}

@JvmInline
value class VerifiedAddFileRecoveryId private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `VerifiedAddFilePlanId -> VerifiedAddFileRecoveryId`.
         *
         * Establishes a distinct typed recovery capability for the exact originating plan while
         * retaining its canonical serialized identity. Raw extraction remains confined to JSON and
         * operation-specific persistence boundaries.
         */
        fun fromPlan(planId: VerifiedAddFilePlanId): VerifiedAddFileRecoveryId =
            VerifiedAddFileRecoveryId(planId.value)

        /**
         * Proof transition: `String -> VerifiedAddFileRefinement<VerifiedAddFileRecoveryId>`.
         *
         * Establishes the distinct canonical `af-`-prefixed recovery-capability identity. The
         * closed expected failure is [VerifiedAddFileValueFailure.RECOVERY_ID_NOT_CANONICAL]. Raw
         * extraction is permitted only at JSON and operation-specific persistence boundaries.
         */
        fun refine(raw: String): VerifiedAddFileRefinement<VerifiedAddFileRecoveryId> =
            if (raw.matches(ADD_FILE_PLAN_ID)) {
                VerifiedAddFileRefinement.Refined(VerifiedAddFileRecoveryId(raw))
            } else {
                VerifiedAddFileRefinement.Rejected(
                    VerifiedAddFileValueFailure.RECOVERY_ID_NOT_CANONICAL,
                )
            }
    }
}

@JvmInline
value class VerifiedAddFilePlanVersion private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> VerifiedAddFileRefinement<VerifiedAddFilePlanVersion>`.
         *
         * Establishes a non-negative lifecycle compare-and-set version. The closed expected failure
         * is [VerifiedAddFileValueFailure.PLAN_VERSION_NEGATIVE]. Raw extraction is permitted only
         * at JSON and operation-specific plan persistence boundaries.
         */
        fun refine(raw: Long): VerifiedAddFileRefinement<VerifiedAddFilePlanVersion> =
            if (raw >= 0L) {
                VerifiedAddFileRefinement.Refined(VerifiedAddFilePlanVersion(raw))
            } else {
                VerifiedAddFileRefinement.Rejected(VerifiedAddFileValueFailure.PLAN_VERSION_NEGATIVE)
            }
    }
}

@JvmInline
value class VerifiedAddFileApprovedBy private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> VerifiedAddFileRefinement<VerifiedAddFileApprovedBy>`.
         *
         * Establishes a non-blank approval actor without discarded whitespace. The closed expected
         * failure is [VerifiedAddFileValueFailure.APPROVED_BY_NOT_TRIMMED_NON_BLANK]. Raw extraction
         * is permitted only at JSON and operation-specific approval boundaries.
         */
        fun refine(raw: String): VerifiedAddFileRefinement<VerifiedAddFileApprovedBy> =
            if (raw.isNotBlank() && raw == raw.trim()) {
                VerifiedAddFileRefinement.Refined(VerifiedAddFileApprovedBy(raw))
            } else {
                VerifiedAddFileRefinement.Rejected(
                    VerifiedAddFileValueFailure.APPROVED_BY_NOT_TRIMMED_NON_BLANK,
                )
            }
    }
}

@JvmInline
value class VerifiedAddFileApprovalEvidenceSha256 private constructor(val value: String) {
    companion object {
        /**
         * Proof transition:
         * `String -> VerifiedAddFileRefinement<VerifiedAddFileApprovalEvidenceSha256>`.
         *
         * Establishes a canonical lowercase SHA-256 approval identity. The closed expected failure
         * is [VerifiedAddFileValueFailure.APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL]. Raw extraction is
         * permitted only at JSON and operation-specific approval persistence boundaries.
         */
        fun refine(raw: String): VerifiedAddFileRefinement<VerifiedAddFileApprovalEvidenceSha256> =
            if (raw.matches(ADD_FILE_LOWER_SHA256)) {
                VerifiedAddFileRefinement.Refined(VerifiedAddFileApprovalEvidenceSha256(raw))
            } else {
                VerifiedAddFileRefinement.Rejected(
                    VerifiedAddFileValueFailure.APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL,
                )
            }
    }
}

class VerifiedAddFileIntent(
    val workspaceRoot: NormalizedPath,
    val targetPath: VerifiedAddFileTargetPath,
    val content: VerifiedAddFileContent,
)

class VerifiedAddFilePlanRequest(
    val workspaceRoot: NormalizedPath,
    val targetPath: VerifiedAddFileTargetPath,
    val proposedContent: VerifiedAddFileContent,
)

class VerifiedAddFileApprovalEvidence(
    val approvedBy: VerifiedAddFileApprovedBy,
    val evidenceSha256: VerifiedAddFileApprovalEvidenceSha256,
)

enum class VerifiedAddFileApplyMode {
    APPLY,
    RECOVER;

    companion object {
        /**
         * Proof transition: `String -> VerifiedAddFileRefinement<VerifiedAddFileApplyMode>`.
         *
         * Establishes one closed mutation intent: fresh approved apply or recovery-only replay.
         * The closed failure is [VerifiedAddFileValueFailure.APPLY_MODE_UNSUPPORTED]. Raw wire text
         * may be extracted only at the JSON-RPC request boundary.
         */
        fun refine(raw: String): VerifiedAddFileRefinement<VerifiedAddFileApplyMode> =
            entries.firstOrNull { it.name == raw }
                ?.let(VerifiedAddFileRefinement<VerifiedAddFileApplyMode>::Refined)
                ?: VerifiedAddFileRefinement.Rejected(
                    VerifiedAddFileValueFailure.APPLY_MODE_UNSUPPORTED,
                )
    }
}

class VerifiedAddFileApplyRequest(
    val workspaceRoot: NormalizedPath,
    val planId: VerifiedAddFilePlanId,
    val expectedVersion: VerifiedAddFilePlanVersion,
    val mode: VerifiedAddFileApplyMode,
    val approvalEvidence: VerifiedAddFileApprovalEvidence,
)

class VerifiedAddFileApprovalChallenge private constructor(
    val workspaceRoot: NormalizedPath,
    val planId: VerifiedAddFilePlanId,
    val expectedVersion: VerifiedAddFilePlanVersion,
    val planned: VerifiedAddFilePlan,
) {
    companion object {
        /**
         * Proof transition:
         * `(VerifiedAddFilePlanId, VerifiedAddFilePlanVersion, VerifiedAddFilePlan)`
         * to `VerifiedAddFileApprovalChallenge`.
         *
         * Establishes that the approval challenge is derived from the persisted exact plan and
         * its non-null admitted workspace. Raw identity/version extraction remains limited to
         * persistence and approval-statement boundaries.
         */
        fun persisted(
            planId: VerifiedAddFilePlanId,
            expectedVersion: VerifiedAddFilePlanVersion,
            planned: VerifiedAddFilePlan,
        ): VerifiedAddFileApprovalChallenge = VerifiedAddFileApprovalChallenge(
            planned.intent.workspaceRoot, planId, expectedVersion, planned,
        )
    }
}

class ApprovedVerifiedAddFilePlan private constructor(
    val planned: VerifiedAddFilePlan,
    val approvalEvidence: VerifiedAddFileApprovalEvidence,
) {
    companion object {
        /**
         * Proof transition:
         * `(VerifiedAddFileApprovalChallenge, VerifiedAddFileApprovalEvidence)`
         * to `VerifiedAddFileAdmission<ApprovedVerifiedAddFilePlan>`.
         *
         * Establishes approval of the exact workspace, plan identity, and compare-and-set version.
         * The closed expected failure is [VerifiedAddFileFailure.APPROVAL_REJECTED]. Raw values are
         * extracted only while hashing the canonical statement at this approval boundary.
         */
        fun admit(
            challenge: VerifiedAddFileApprovalChallenge,
            evidence: VerifiedAddFileApprovalEvidence,
        ): VerifiedAddFileAdmission<ApprovedVerifiedAddFilePlan> {
            if (evidence.approvedBy.value != CANONICAL_ADD_FILE_APPROVER) {
                return VerifiedAddFileAdmission.Rejected(VerifiedAddFileFailure.APPROVAL_REJECTED)
            }
            val statement = buildString {
                append(CANONICAL_ADD_FILE_APPROVER)
                append("\n")
                append("workspaceRoot=")
                append(challenge.workspaceRoot.value)
                append("\n")
                append("planId=")
                append(challenge.planId.value)
                append("\n")
                append("expectedVersion=")
                append(challenge.expectedVersion.value)
                append("\n")
            }
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(statement.toByteArray(Charsets.UTF_8))
                .toLowerHex()
            return if (evidence.evidenceSha256.value == expected) {
                VerifiedAddFileAdmission.Admitted(ApprovedVerifiedAddFilePlan(challenge.planned, evidence))
            } else {
                VerifiedAddFileAdmission.Rejected(VerifiedAddFileFailure.APPROVAL_REJECTED)
            }
        }
    }
}

class VerifiedAddFileTargetAbsenceProof(
    val proof: ExactAddFileProof,
)

class VerifiedAddFilePlan private constructor(
    val intent: VerifiedAddFileIntent,
    val exact: AddFilePlanResult,
    val absence: VerifiedAddFileTargetAbsenceProof,
) {
    companion object {
        /**
         * Proof transition:
         * `(VerifiedAddFileIntent, AddFilePlanResult) -> VerifiedAddFileAdmission<VerifiedAddFilePlan>`.
         *
         * Establishes exact path/content agreement and retains the compiler-backed absent-target,
         * authored source-owner, package, declaration, and postimage proof. Raw content is exposed only
         * to the operation-specific source application boundary. The closed expected failure is
         * [VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID].
         */
        fun admit(
            intent: VerifiedAddFileIntent,
            exact: AddFilePlanResult,
        ): VerifiedAddFileAdmission<VerifiedAddFilePlan> =
            if (
                exact.proof.targetPath.value == intent.targetPath.value &&
                exact.proposedContent == intent.content.value
            ) {
                VerifiedAddFileAdmission.Admitted(
                    VerifiedAddFilePlan(intent, exact, VerifiedAddFileTargetAbsenceProof(exact.proof)),
                )
            } else {
                VerifiedAddFileAdmission.Rejected(VerifiedAddFileFailure.PACKAGE_OR_DECLARATION_INVALID)
            }
    }
}

class RevalidatedVerifiedAddFilePlan private constructor(
    val planned: VerifiedAddFilePlan,
    val current: AddFilePlanResult,
) {
    companion object {
        /**
         * Proof transition:
         * `(VerifiedAddFilePlan, AddFilePlanResult)`
         * to `VerifiedAddFileAdmission<RevalidatedVerifiedAddFilePlan>`.
         *
         * Establishes that exact owner, absence, semantic context, package, declarations, and postimage
         * still equal the approved plan. The closed expected failure is
         * [VerifiedAddFileFailure.PLAN_REVALIDATION_FAILED].
         */
        fun admit(
            planned: VerifiedAddFilePlan,
            current: AddFilePlanResult,
        ): VerifiedAddFileAdmission<RevalidatedVerifiedAddFilePlan> =
            if (
                current.proof == planned.exact.proof &&
                current.proposedContent == planned.exact.proposedContent &&
                current.postimage.copyBytes().contentEquals(planned.exact.postimage.copyBytes())
            ) {
                VerifiedAddFileAdmission.Admitted(RevalidatedVerifiedAddFilePlan(planned, current))
            } else {
                VerifiedAddFileAdmission.Rejected(VerifiedAddFileFailure.PLAN_REVALIDATION_FAILED)
            }
    }
}

private val ADD_FILE_LOWER_SHA256 = Regex("[0-9a-f]{64}")
private val ADD_FILE_PLAN_ID = Regex("af-[0-9a-f]{64}")
private const val CANONICAL_ADD_FILE_APPROVER = "kast-public-cli"
private const val HEX = "0123456789abcdef"

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append(HEX[value ushr 4])
        append(HEX[value and 0x0f])
    }
}
