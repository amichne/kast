package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement

enum class AddDeclarationSourceTextFailure {
    BLANK,
    NON_CANONICAL_LINE_ENDING,
    TERMINAL_LINE_BREAK,
    CONTROL_CHARACTER,
}

/** Canonical detached Kotlin declaration source admitted before pure planning. */
@JvmInline
value class AddDeclarationSourceText private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<AddDeclarationSourceText,
         * AddDeclarationSourceTextFailure>`.
         *
         * Establishes non-blank LF-normalized declaration source without a terminal line break or
         * unsupported control characters. [AddDeclarationSourceTextFailure] is the closed expected
         * failure. Raw source text may enter only at the public change-intent boundary and may be
         * extracted only by a later admitted mutation boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<AddDeclarationSourceText, AddDeclarationSourceTextFailure> = when {
            raw.isBlank() -> Refinement.Rejected(AddDeclarationSourceTextFailure.BLANK)
            '\r' in raw ->
                Refinement.Rejected(AddDeclarationSourceTextFailure.NON_CANONICAL_LINE_ENDING)
            raw.endsWith('\n') ->
                Refinement.Rejected(AddDeclarationSourceTextFailure.TERMINAL_LINE_BREAK)
            raw.any { character ->
                character.isISOControl() && character != '\n' && character != '\t'
            } -> Refinement.Rejected(AddDeclarationSourceTextFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(AddDeclarationSourceText(raw))
        }
    }
}

/** Weaker collection of required detached semantic results presented to planning. */
data class AddDeclarationPlanningEvidenceInput(
    val relations: List<io.github.amichne.kast.relation.contract.RelationReadResult>,
    val traversals: List<io.github.amichne.kast.traversal.contract.TraversalResult>,
    val diagnostics: List<io.github.amichne.kast.diagnostic.contract.DiagnosticCheckResult>,
)

/** Exact AddDeclaration request with no source-write authority. */
data class AddDeclarationPlanRequest(
    val target: EditableMutationTarget,
    val declaration: AddDeclarationSourceText,
    val expectedSemanticDelta: ExpectedAddDeclarationDelta,
    val evidence: AddDeclarationPlanningEvidenceInput,
)

enum class AddDeclarationPlanningFailure {
    RELATION_EVIDENCE_REQUIRED,
    RELATION_EVIDENCE_INCOMPLETE,
    TRAVERSAL_EVIDENCE_REQUIRED,
    TRAVERSAL_EVIDENCE_INCOMPLETE,
    DIAGNOSTIC_EVIDENCE_REQUIRED,
    DIAGNOSTIC_EVIDENCE_INCOMPLETE,
    EVIDENCE_LEASE_MISMATCH,
    EVIDENCE_TARGET_MISMATCH,
}
