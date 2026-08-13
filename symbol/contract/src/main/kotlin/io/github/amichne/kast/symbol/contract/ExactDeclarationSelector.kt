package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadLease

private const val MAX_EXACT_DECLARATION_IDENTITY_LENGTH = 1024
private const val MAX_EXACT_DECLARATION_RUNTIME_TYPE_LENGTH = 512

enum class SymbolDiscoverySelectionFailure {
    NEGATIVE_ORDINAL,
    ORDINAL_OUT_OF_RANGE,
    FILE_IS_NOT_A_DECLARATION,
}

/**
 * Proof that one declaration candidate was selected by position from one exact discovery batch.
 * The batch-owned transition prevents callers from reconstructing selection authority from display
 * names, qualified names, file paths, or source offsets.
 */
class SymbolDiscoverySelection private constructor(
    val lease: SemanticReadLease,
    val scope: SymbolSearchScope,
    val candidate: SymbolDiscoveryCandidate,
) {
    companion object {
        /**
         * Proof transition:
         * SymbolDiscoveryBatch + Int to
         * Refinement<SymbolDiscoverySelection, SymbolDiscoverySelectionFailure>.
         *
         * Establishes that the selected value is the declaration candidate stored at [rawOrdinal]
         * in the exact generation/scope-bound batch. [SymbolDiscoverySelectionFailure] is the closed
         * expected failure. Raw ordinals may be extracted only at a bounded result-presentation or
         * transport boundary.
         */
        fun select(
            batch: SymbolDiscoveryBatch,
            rawOrdinal: Int,
        ): Refinement<SymbolDiscoverySelection, SymbolDiscoverySelectionFailure> {
            if (rawOrdinal < 0) {
                return Refinement.Rejected(SymbolDiscoverySelectionFailure.NEGATIVE_ORDINAL)
            }
            val candidate = batch.candidates.getOrNull(rawOrdinal)
                            ?: return Refinement.Rejected(
                                SymbolDiscoverySelectionFailure.ORDINAL_OUT_OF_RANGE,
                            )
            if (candidate.location !is SymbolDiscoveryCandidateLocation.Declaration) {
                return Refinement.Rejected(
                    SymbolDiscoverySelectionFailure.FILE_IS_NOT_A_DECLARATION,
                )
            }
            return Refinement.Refined(
                SymbolDiscoverySelection(
                    lease = batch.lease,
                    scope = batch.scope,
                    candidate = candidate,
                ),
            )
        }
    }
}

enum class ExactDeclarationEvidenceFailure {
    INVALID_RANGE,
    INVALID_NAME,
    INVALID_QUALIFIED_IDENTITY,
    INVALID_RUNTIME_TYPE,
}

@ConsistentCopyVisibility
data class ExactDeclarationTextRange private constructor(
    val startInclusive: Int,
    val endExclusive: Int,
) {
    companion object {
        /**
         * Proof transition:
         * Int + Int to Refinement<ExactDeclarationTextRange, ExactDeclarationEvidenceFailure>.
         *
         * Establishes a non-negative, non-empty, ordered detached source range.
         * [ExactDeclarationEvidenceFailure] is the closed expected failure. Raw offsets may be
         * extracted only at the request-local PSI lookup or source-navigation boundary.
         */
        fun parse(
            rawStartInclusive: Int,
            rawEndExclusive: Int,
        ): Refinement<ExactDeclarationTextRange, ExactDeclarationEvidenceFailure> =
            if (rawStartInclusive !in 0 until rawEndExclusive) {
                Refinement.Rejected(ExactDeclarationEvidenceFailure.INVALID_RANGE)
            } else {
                Refinement.Refined(
                    ExactDeclarationTextRange(rawStartInclusive, rawEndExclusive),
                )
            }
    }
}

sealed interface ExactDeclarationQualifiedIdentity {
    @ConsistentCopyVisibility
    data class Available internal constructor(
        val value: String,
    ) : ExactDeclarationQualifiedIdentity

    data object Unavailable : ExactDeclarationQualifiedIdentity

    companion object {
        /**
         * Proof transition:
         * String? to
         * Refinement<ExactDeclarationQualifiedIdentity, ExactDeclarationEvidenceFailure>.
         *
         * Establishes an explicit unavailable state or a bounded non-blank qualified identity
         * without control characters. [ExactDeclarationEvidenceFailure] is the closed expected
         * failure. Raw qualified names may be extracted only at the PSI lookup or display boundary.
         */
        fun fromBoundary(
            raw: String?,
        ): Refinement<ExactDeclarationQualifiedIdentity, ExactDeclarationEvidenceFailure> =
            when {
                raw == null -> Refinement.Refined(Unavailable)
                raw.isBlank() -> Refinement.Rejected(
                    ExactDeclarationEvidenceFailure.INVALID_QUALIFIED_IDENTITY,
                )
                raw.length > MAX_EXACT_DECLARATION_IDENTITY_LENGTH ||
                raw.any(Char::isISOControl) ->
                    Refinement.Rejected(
                        ExactDeclarationEvidenceFailure.INVALID_QUALIFIED_IDENTITY,
                    )
                else -> Refinement.Refined(Available(raw))
            }
    }
}

@JvmInline
value class ExactDeclarationRuntimeType private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition:
         * String to Refinement<ExactDeclarationRuntimeType, ExactDeclarationEvidenceFailure>.
         *
         * Establishes a bounded non-blank JVM declaration type name without control characters.
         * [ExactDeclarationEvidenceFailure] is the closed expected failure. Raw class names may be
         * extracted only at the request-local PSI lookup or diagnostic boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<ExactDeclarationRuntimeType, ExactDeclarationEvidenceFailure> =
            if (
                raw.isBlank() ||
                raw.length > MAX_EXACT_DECLARATION_RUNTIME_TYPE_LENGTH ||
                raw.any(Char::isISOControl)
            ) {
                Refinement.Rejected(ExactDeclarationEvidenceFailure.INVALID_RUNTIME_TYPE)
            } else {
                Refinement.Refined(ExactDeclarationRuntimeType(raw))
            }
    }
}

/**
 * Detached native evidence for one declaration. Creation validates representation invariants but
 * does not itself claim that PSI lookup succeeded; only an IntelliJ adapter may issue it from a
 * live, scope-checked declaration.
 */
@ConsistentCopyVisibility
data class ExactDeclarationEvidence private constructor(
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
    val name: SymbolDiscoveryCandidateName,
    val qualifiedIdentity: ExactDeclarationQualifiedIdentity,
    val runtimeType: ExactDeclarationRuntimeType,
) {
    companion object {
        /**
         * Proof transition:
         * detached file + raw PSI declaration fields to
         * Refinement<ExactDeclarationEvidence, ExactDeclarationEvidenceFailure>.
         *
         * Establishes strongly represented file, range, name, qualified-identity state, and runtime
         * declaration type. [ExactDeclarationEvidenceFailure] is the closed expected failure. Raw
         * values may enter only from the request-local IntelliJ PSI lookup boundary.
         */
        fun fromBoundary(
            file: SymbolDiscoveryFileIdentity,
            rawStartInclusive: Int,
            rawEndExclusive: Int,
            rawName: String,
            rawQualifiedIdentity: String?,
            rawRuntimeType: String,
        ): Refinement<ExactDeclarationEvidence, ExactDeclarationEvidenceFailure> {
            val range = when (
                val parsed = ExactDeclarationTextRange.parse(
                    rawStartInclusive,
                    rawEndExclusive,
                )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val name = when (val parsed = SymbolDiscoveryCandidateName.parse(rawName)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(ExactDeclarationEvidenceFailure.INVALID_NAME)
            }
            val qualifiedIdentity = when (
                val parsed = ExactDeclarationQualifiedIdentity.fromBoundary(rawQualifiedIdentity)
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            val runtimeType = when (val parsed = ExactDeclarationRuntimeType.parse(rawRuntimeType)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return parsed
            }
            return Refinement.Refined(
                ExactDeclarationEvidence(
                    file,
                    range,
                    name,
                    qualifiedIdentity,
                    runtimeType,
                ),
            )
        }
    }
}

enum class ExactDeclarationSelectorIssueFailure {
    FILE_MISMATCH,
    NAME_MISMATCH,
    START_OFFSET_MISMATCH,
}

/**
 * Opaque, detached selector whose identity is bound to one root, generation, scope, file, source
 * range, name, qualified-identity state, and IntelliJ declaration implementation type.
 */
class ExactDeclarationSelector private constructor(
    val lease: SemanticReadLease,
    val scope: SymbolSearchScope,
    val file: SymbolDiscoveryFileIdentity,
    val range: ExactDeclarationTextRange,
    val name: SymbolDiscoveryCandidateName,
    val qualifiedIdentity: ExactDeclarationQualifiedIdentity,
    val runtimeType: ExactDeclarationRuntimeType,
    val fingerprint: ExactDeclarationFingerprint,
) {
    companion object {
        /**
         * Proof transition:
         * SymbolDiscoverySelection + ExactDeclarationEvidence to
         * Refinement<ExactDeclarationSelector, ExactDeclarationSelectorIssueFailure>.
         *
         * Establishes that live native evidence identifies the same selected file, name, and exact
         * starting offset, then seals all detached declaration evidence under a deterministic
         * fingerprint. [ExactDeclarationSelectorIssueFailure] is the closed expected failure.
         * Evidence may enter only from the request-local IntelliJ selector-resolution adapter.
         */
        fun issue(
            selection: SymbolDiscoverySelection,
            evidence: ExactDeclarationEvidence,
        ): Refinement<ExactDeclarationSelector, ExactDeclarationSelectorIssueFailure> {
            if (evidence.file != selection.candidate.location.file) {
                return Refinement.Rejected(ExactDeclarationSelectorIssueFailure.FILE_MISMATCH)
            }
            if (evidence.name != selection.candidate.name) {
                return Refinement.Rejected(ExactDeclarationSelectorIssueFailure.NAME_MISMATCH)
            }
            val location = selection.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
            if (evidence.range.startInclusive != location.offset.value) {
                return Refinement.Rejected(
                    ExactDeclarationSelectorIssueFailure.START_OFFSET_MISMATCH,
                )
            }
            return Refinement.Refined(
                ExactDeclarationSelector(
                    lease = selection.lease,
                    scope = selection.scope,
                    file = evidence.file,
                    range = evidence.range,
                    name = evidence.name,
                    qualifiedIdentity = evidence.qualifiedIdentity,
                    runtimeType = evidence.runtimeType,
                    fingerprint = exactDeclarationFingerprint(
                        selection.lease,
                        selection.scope,
                        evidence,
                    ),
                ),
            )
        }
    }
}

enum class ExactDeclarationRevalidationFailure {
    DECLARATION_MOVED_OR_CHANGED,
}

/**
 * Proof that an exact selector resolved to identical native declaration evidence in its current
 * generation and scope.
 */
class RevalidatedExactDeclaration private constructor(
    val selector: ExactDeclarationSelector,
) {
    companion object {
        /**
         * Proof transition:
         * ExactDeclarationSelector + ExactDeclarationEvidence to
         * Refinement<RevalidatedExactDeclaration, ExactDeclarationRevalidationFailure>.
         *
         * Establishes byte-for-byte fingerprint identity between the issued selector and current
         * native declaration evidence. [ExactDeclarationRevalidationFailure] is the closed expected
         * failure. Evidence may enter only from the request-local IntelliJ revalidation adapter.
         */
        fun validate(
            selector: ExactDeclarationSelector,
            evidence: ExactDeclarationEvidence,
        ): Refinement<RevalidatedExactDeclaration, ExactDeclarationRevalidationFailure> =
            if (
                exactDeclarationFingerprint(
                    selector.lease,
                    selector.scope,
                    evidence,
                ) == selector.fingerprint
            ) {
                Refinement.Refined(RevalidatedExactDeclaration(selector))
            } else {
                Refinement.Rejected(
                    ExactDeclarationRevalidationFailure.DECLARATION_MOVED_OR_CHANGED,
                )
            }
    }
}
