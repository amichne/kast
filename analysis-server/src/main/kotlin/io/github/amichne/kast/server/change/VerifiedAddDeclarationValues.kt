package io.github.amichne.kast.server.change

import kotlinx.serialization.Serializable
import java.nio.file.Path

enum class VerifiedAddDeclarationWireValueFailure {
    PLAN_ID_NOT_CANONICAL,
    PLAN_VERSION_NEGATIVE,
    TARGET_PATH_NOT_NORMALIZED_ABSOLUTE_KOTLIN,
    PUBLICATION_GENERATION_NEGATIVE,
    WORKSPACE_STATE_IDENTITY_BLANK,
    SOURCE_RANGE_INVALID,
    PACKAGE_NAME_INVALID,
    DECLARATION_NAME_BLANK,
    PROPOSED_DECLARATION_NOT_NORMALIZED,
    APPROVED_BY_NOT_TRIMMED_NON_BLANK,
    APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL,
    SHA256_NOT_CANONICAL,
}

sealed interface VerifiedAddDeclarationWireRefinement<out T> {
    data class Refined<T>(val value: T) : VerifiedAddDeclarationWireRefinement<T>

    data class Rejected(
        val failure: VerifiedAddDeclarationWireValueFailure,
    ) : VerifiedAddDeclarationWireRefinement<Nothing>
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationPlanId private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPlanId>`.
         *
         * Establishes a canonical lowercase SHA-256 plan identity. The closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.PLAN_ID_NOT_CANONICAL]. Raw extraction is permitted
         * only at JSON and change-journal adapter boundaries.
         */
        fun refine(raw: String): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPlanId> =
            if (raw.matches(LOWER_SHA256)) {
                VerifiedAddDeclarationWireRefinement.Refined(VerifiedAddDeclarationPlanId(raw))
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.PLAN_ID_NOT_CANONICAL,
                )
            }
    }
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationPlanVersion private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPlanVersion>`.
         *
         * Establishes a non-negative durable compare-and-set version. The closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.PLAN_VERSION_NEGATIVE]. Raw extraction is permitted
         * only at JSON and change-journal adapter boundaries.
         */
        fun refine(raw: Long): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPlanVersion> =
            if (raw >= 0L) {
                VerifiedAddDeclarationWireRefinement.Refined(VerifiedAddDeclarationPlanVersion(raw))
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.PLAN_VERSION_NEGATIVE,
                )
            }
    }
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationTargetPath private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationTargetPath>`.
         *
         * Establishes a normalized absolute Kotlin source path. The closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.TARGET_PATH_NOT_NORMALIZED_ABSOLUTE_KOTLIN]. Raw
         * extraction is permitted only at JSON and filesystem adapter boundaries.
         */
        fun refine(raw: String): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationTargetPath> {
            val path = runCatching { Path.of(raw) }.getOrNull()
            return if (
                path != null &&
                path.isAbsolute &&
                path.normalize().toString() == raw &&
                path.fileName?.toString()?.endsWith(".kt") == true
            ) {
                VerifiedAddDeclarationWireRefinement.Refined(VerifiedAddDeclarationTargetPath(raw))
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.TARGET_PATH_NOT_NORMALIZED_ABSOLUTE_KOTLIN,
                )
            }
        }
    }
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationPublicationGeneration private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition:
         * `Long -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPublicationGeneration>`.
         *
         * Establishes a non-negative published workspace generation. The closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.PUBLICATION_GENERATION_NEGATIVE]. Raw extraction is
         * permitted only at JSON and workspace-publication adapter boundaries.
         */
        fun refine(
            raw: Long,
        ): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPublicationGeneration> =
            if (raw >= 0L) {
                VerifiedAddDeclarationWireRefinement.Refined(
                    VerifiedAddDeclarationPublicationGeneration(raw),
                )
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.PUBLICATION_GENERATION_NEGATIVE,
                )
            }
    }
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationWorkspaceStateIdentity private constructor(val value: String) {
    companion object {
        /**
         * Proof transition:
         * `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationWorkspaceStateIdentity>`.
         *
         * Establishes a non-blank, trimmed publication identity. The closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.WORKSPACE_STATE_IDENTITY_BLANK]. Raw extraction is
         * permitted only at JSON and workspace-publication adapter boundaries.
         */
        fun refine(
            raw: String,
        ): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationWorkspaceStateIdentity> =
            if (raw.isNotBlank() && raw == raw.trim()) {
                VerifiedAddDeclarationWireRefinement.Refined(
                    VerifiedAddDeclarationWorkspaceStateIdentity(raw),
                )
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.WORKSPACE_STATE_IDENTITY_BLANK,
                )
            }
    }
}

@Serializable
class VerifiedAddDeclarationSourceRange private constructor(
    val startOffset: Int,
    val endOffset: Int,
) {
    companion object {
        /**
         * Proof transition:
         * `(Int, Int) -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationSourceRange>`.
         *
         * Establishes ordered non-negative UTF-16 source offsets. The closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.SOURCE_RANGE_INVALID]. Raw extraction is permitted
         * only at JSON and IntelliJ PSI adapter boundaries.
         */
        fun refine(
            startOffset: Int,
            endOffset: Int,
        ): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationSourceRange> =
            if (startOffset >= 0 && endOffset > startOffset) {
                VerifiedAddDeclarationWireRefinement.Refined(
                    VerifiedAddDeclarationSourceRange(startOffset, endOffset),
                )
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.SOURCE_RANGE_INVALID,
                )
            }
    }
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationPackageName private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPackageName>`.
         *
         * Establishes either the root package or a trimmed dot-separated package identity. The closed
         * expected failure is [VerifiedAddDeclarationWireValueFailure.PACKAGE_NAME_INVALID]. Raw
         * extraction is permitted only at JSON and Kotlin PSI adapter boundaries.
         */
        fun refine(raw: String): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPackageName> =
            if (raw.isEmpty() || raw.split('.').all(::canonicalSemanticName)) {
                VerifiedAddDeclarationWireRefinement.Refined(VerifiedAddDeclarationPackageName(raw))
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.PACKAGE_NAME_INVALID,
                )
            }
    }
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationDeclarationName private constructor(val value: String) {
    companion object {
        /**
         * Proof transition:
         * `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationDeclarationName>`.
         *
         * Establishes a non-blank, trimmed declaration identity. The closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.DECLARATION_NAME_BLANK]. Raw extraction is permitted
         * only at JSON and Kotlin PSI adapter boundaries.
         */
        fun refine(
            raw: String,
        ): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationDeclarationName> =
            if (canonicalSemanticName(raw)) {
                VerifiedAddDeclarationWireRefinement.Refined(VerifiedAddDeclarationDeclarationName(raw))
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.DECLARATION_NAME_BLANK,
                )
            }
    }
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationProposedDeclaration private constructor(val value: String) {
    companion object {
        /**
         * Proof transition:
         * `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationProposedDeclaration>`.
         *
         * Establishes one non-blank LF-normalized declaration without a trailing line break. The
         * closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.PROPOSED_DECLARATION_NOT_NORMALIZED]. Raw extraction
         * is permitted only at JSON and add-declaration planning adapter boundaries.
         */
        fun refine(
            raw: String,
        ): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationProposedDeclaration> =
            if (raw.isNotBlank() && '\r' !in raw && raw == raw.trimEnd('\n')) {
                VerifiedAddDeclarationWireRefinement.Refined(
                    VerifiedAddDeclarationProposedDeclaration(raw),
                )
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.PROPOSED_DECLARATION_NOT_NORMALIZED,
                )
            }
    }
}

@JvmInline
value class VerifiedAddDeclarationApprovedBy private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationApprovedBy>`.
         *
         * Establishes a non-blank approval actor with no discarded surrounding whitespace. The closed
         * expected failure is
         * [VerifiedAddDeclarationWireValueFailure.APPROVED_BY_NOT_TRIMMED_NON_BLANK]. Raw extraction is
         * permitted only at JSON and plan-journal approval adapter boundaries.
         */
        fun refine(raw: String): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationApprovedBy> =
            if (raw.isNotBlank() && raw == raw.trim()) {
                VerifiedAddDeclarationWireRefinement.Refined(VerifiedAddDeclarationApprovedBy(raw))
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.APPROVED_BY_NOT_TRIMMED_NON_BLANK,
                )
            }
    }
}

@JvmInline
value class VerifiedAddDeclarationApprovalEvidenceSha256 private constructor(val value: String) {
    companion object {
        /**
         * Proof transition:
         * `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationApprovalEvidenceSha256>`.
         *
         * Establishes a canonical lowercase SHA-256 approval-evidence identity. The closed expected
         * failure is
         * [VerifiedAddDeclarationWireValueFailure.APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL]. Raw
         * extraction is permitted only at JSON and plan-journal approval adapter boundaries.
         */
        fun refine(
            raw: String,
        ): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationApprovalEvidenceSha256> =
            if (raw.matches(LOWER_SHA256)) {
                VerifiedAddDeclarationWireRefinement.Refined(
                    VerifiedAddDeclarationApprovalEvidenceSha256(raw),
                )
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.APPROVAL_EVIDENCE_SHA256_NOT_CANONICAL,
                )
            }
    }
}

@Serializable
@JvmInline
value class VerifiedAddDeclarationPostimageSha256 private constructor(val value: String) {
    companion object {
        /**
         * Proof transition:
         * `String -> VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPostimageSha256>`.
         *
         * Establishes a canonical lowercase SHA-256 postimage identity. The closed expected failure is
         * [VerifiedAddDeclarationWireValueFailure.SHA256_NOT_CANONICAL]. Raw extraction is permitted
         * only at JSON and exact-file adapter boundaries.
         */
        fun refine(
            raw: String,
        ): VerifiedAddDeclarationWireRefinement<VerifiedAddDeclarationPostimageSha256> =
            if (raw.matches(LOWER_SHA256)) {
                VerifiedAddDeclarationWireRefinement.Refined(VerifiedAddDeclarationPostimageSha256(raw))
            } else {
                VerifiedAddDeclarationWireRefinement.Rejected(
                    VerifiedAddDeclarationWireValueFailure.SHA256_NOT_CANONICAL,
                )
            }
    }
}

internal val LOWER_SHA256 = Regex("[0-9a-f]{64}")

private fun canonicalSemanticName(raw: String): Boolean =
    raw.isNotBlank() && raw == raw.trim() && raw.none(Char::isISOControl)
