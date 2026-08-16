package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement

enum class DeclarationSourceTextFailure {
    BLANK,
    NON_CANONICAL_LINE_ENDING,
    TERMINAL_LINE_BREAK,
    CONTROL_CHARACTER,
}

/** Exact current declaration source extracted from a compiler-grounded target. */
@JvmInline
value class ExistingDeclarationSourceText private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<ExistingDeclarationSourceText,
         * DeclarationSourceTextFailure>`.
         *
         * Establishes non-blank LF-normalized current declaration source without a terminal line
         * break or unsupported control characters. [DeclarationSourceTextFailure] is the closed
         * expected failure. Raw text may enter only from compiler-backed declaration extraction
         * and leave only at exact preimage admission.
         */
        fun parse(
            raw: String,
        ): Refinement<ExistingDeclarationSourceText, DeclarationSourceTextFailure> = when {
            raw.isBlank() -> Refinement.Rejected(DeclarationSourceTextFailure.BLANK)
            '\r' in raw -> Refinement.Rejected(
                DeclarationSourceTextFailure.NON_CANONICAL_LINE_ENDING,
            )
            raw.endsWith('\n') -> Refinement.Rejected(
                DeclarationSourceTextFailure.TERMINAL_LINE_BREAK,
            )
            raw.any { character ->
                character.isISOControl() && character != '\n' && character != '\t'
            } -> Refinement.Rejected(DeclarationSourceTextFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(ExistingDeclarationSourceText(raw))
        }
    }
}

/** Proposed whole declaration source admitted before semantic parsing by IntelliJ. */
@JvmInline
value class ReplacementDeclarationSourceText private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<ReplacementDeclarationSourceText,
         * DeclarationSourceTextFailure>`.
         *
         * Establishes non-blank LF-normalized replacement declaration source without a terminal
         * line break or unsupported control characters. [DeclarationSourceTextFailure] is the
         * closed expected failure. Raw text may enter only at the change-intent boundary and leave
         * only at the authority-bound IntelliJ declaration parser.
         */
        fun parse(
            raw: String,
        ): Refinement<ReplacementDeclarationSourceText, DeclarationSourceTextFailure> = when {
            raw.isBlank() -> Refinement.Rejected(DeclarationSourceTextFailure.BLANK)
            '\r' in raw -> Refinement.Rejected(
                DeclarationSourceTextFailure.NON_CANONICAL_LINE_ENDING,
            )
            raw.endsWith('\n') -> Refinement.Rejected(
                DeclarationSourceTextFailure.TERMINAL_LINE_BREAK,
            )
            raw.any { character ->
                character.isISOControl() && character != '\n' && character != '\t'
            } -> Refinement.Rejected(DeclarationSourceTextFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(ReplacementDeclarationSourceText(raw))
        }
    }
}

enum class ReplaceDeclarationTargetFailure {
    RANGE_LENGTH_MISMATCH,
}

/** Exact declaration target whose selected range retains its compiler-extracted preimage. */
class ReplaceDeclarationTarget private constructor(
    val target: EditableMutationTarget,
    val expected: ExistingDeclarationSourceText,
) {
    companion object {
        /**
         * Proof transition: `(EditableMutationTarget, ExistingDeclarationSourceText) ->
         * Refinement<ReplaceDeclarationTarget, ReplaceDeclarationTargetFailure>`.
         *
         * Establishes that the compiler-grounded declaration range has exactly the UTF-16 length
         * of the extracted current declaration. [ReplaceDeclarationTargetFailure] closes range
         * mismatch. Raw offsets or source may leave only at mutation admission.
         */
        fun admit(
            target: EditableMutationTarget,
            expected: ExistingDeclarationSourceText,
        ): Refinement<ReplaceDeclarationTarget, ReplaceDeclarationTargetFailure> =
            if (
                target.range.endExclusive - target.range.startInclusive != expected.value.length
            ) {
                Refinement.Rejected(ReplaceDeclarationTargetFailure.RANGE_LENGTH_MISMATCH)
            } else {
                Refinement.Refined(ReplaceDeclarationTarget(target, expected))
            }
    }
}

data class ReplaceDeclarationPlanRequest(
    val target: ReplaceDeclarationTarget,
    val replacement: ReplacementDeclarationSourceText,
    val evidence: AddDeclarationPlanningEvidenceInput,
)

sealed interface ReplaceDeclarationPlanningFailure {
    data object REPLACEMENT_UNCHANGED : ReplaceDeclarationPlanningFailure

    data class Evidence(
        val failure: ChangePlanningFailure,
    ) : ReplaceDeclarationPlanningFailure
}

/** Strong replacement input with exact preimage and complete target-bound semantic evidence. */
class AdmittedReplaceDeclarationPlanInput private constructor(
    val target: ReplaceDeclarationTarget,
    val replacement: ReplacementDeclarationSourceText,
    val evidence: CompleteChangePlanningEvidence,
) {
    companion object {
        /**
         * Proof transition: `ReplaceDeclarationPlanRequest -> Refinement<
         * AdmittedReplaceDeclarationPlanInput, ReplaceDeclarationPlanningFailure>`.
         *
         * Establishes a changed whole declaration plus complete normalized semantic evidence for
         * its exact compiler-grounded target. [ReplaceDeclarationPlanningFailure] is the closed
         * expected failure. Raw source and compiler observations must cross their typed boundaries
         * before this pure transition.
         */
        fun admit(
            request: ReplaceDeclarationPlanRequest,
        ): Refinement<AdmittedReplaceDeclarationPlanInput, ReplaceDeclarationPlanningFailure> {
            if (request.replacement.value == request.target.expected.value) {
                return Refinement.Rejected(
                    ReplaceDeclarationPlanningFailure.REPLACEMENT_UNCHANGED,
                )
            }
            return when (val evidence = CompleteChangePlanningEvidence.admit(
                request.target.target,
                request.evidence,
            )) {
                is Refinement.Refined -> Refinement.Refined(
                    AdmittedReplaceDeclarationPlanInput(
                        request.target,
                        request.replacement,
                        evidence.value,
                    ),
                )
                is Refinement.Rejected -> Refinement.Rejected(
                    ReplaceDeclarationPlanningFailure.Evidence(evidence.failure),
                )
            }
        }
    }
}

enum class ReplaceDeclarationObligation : ChangeVerificationObligation {
    TARGET_PREIMAGE_UNCHANGED,
    GENERATION_UNCHANGED,
    OWNER_AND_PROVENANCE_UNCHANGED,
    DECLARED_WRITE_SET_CLOSED,
    EXPECTED_POSTIMAGE_OBSERVED,
    REPLACEMENT_DECLARATION_OBSERVED,
    UNRELATED_CODE_PRESERVED,
    COMPILER_DIAGNOSTICS_CLEAR,
    RESULT_GENERATION_PUBLISHED,
}

/** Pure deterministic whole-declaration replacement plan. */
class ReplaceDeclarationChangePlan private constructor(
    override val planId: ChangePlanId,
    override val intent: ChangeIntent.ReplaceDeclaration,
    val target: ReplaceDeclarationTarget,
    val evidence: CompleteChangePlanningEvidence,
    override val writes: PlannedMutationWriteSet,
    val requiredVerification: List<ReplaceDeclarationObligation>,
) : ChangePlan {
    override val priorLease = target.target.lease
    override val workspaceState = target.target.workspaceState

    companion object {
        /**
         * Proof transition: `AdmittedReplaceDeclarationPlanInput ->
         * ReplaceDeclarationChangePlan`.
         *
         * Establishes a detached singleton plan binding exact current and replacement declaration
         * source, the selected range, source preimage, complete evidence, and exhaustive
         * obligations. There is no expected failure because the admitted input carries every
         * invariant. Raw declaration source may leave only after mutation-authority admission.
         */
        fun issue(input: AdmittedReplaceDeclarationPlanInput): ReplaceDeclarationChangePlan {
            val selected = input.target.target
            val intent = ChangeIntent.ReplaceDeclaration(input.target, input.replacement)
            val writes = PlannedMutationWriteSet.singleton(
                PlannedMutationWrite(
                    selected.file,
                    selected.sourceRoot,
                    PlannedSourcePrecondition.Existing(selected.content),
                    listOf(
                        SourceTextMutation.ReplaceDeclaration(
                            selected.range,
                            input.target.expected,
                            input.replacement,
                        ),
                    ),
                ),
            )
            val canonical = buildString {
                appendPlanningField("REPLACE_DECLARATION")
                appendPlanningField(selected.lease.workspaceRoot.value)
                appendPlanningField(selected.lease.generation.value.toString())
                appendPlanningField(selected.workspaceState.value)
                appendPlanningField(selected.file.path.value)
                appendPlanningField(selected.content.value)
                appendPlanningField(selected.selector.fingerprint.value)
                appendPlanningField(selected.range.startInclusive.toString())
                appendPlanningField(selected.range.endExclusive.toString())
                appendPlanningField(input.target.expected.value)
                appendPlanningField(input.replacement.value)
                appendPlanningField(input.evidence.fingerprint.value)
                ReplaceDeclarationObligation.entries.forEach { appendPlanningField(it.name) }
            }
            return ReplaceDeclarationChangePlan(
                ChangePlanId.fromCanonicalIdentity(canonical),
                intent,
                input.target,
                input.evidence,
                writes,
                ReplaceDeclarationObligation.entries,
            )
        }
    }
}

sealed interface ReplaceDeclarationPlanResult {
    data class Planned(
        val plan: ReplaceDeclarationChangePlan,
    ) : ReplaceDeclarationPlanResult

    data class Rejected(
        val failure: ReplaceDeclarationPlanningFailure,
    ) : ReplaceDeclarationPlanResult
}

fun interface ReplaceDeclarationPlanOperations {
    /**
     * Proof transition: `ReplaceDeclarationPlanRequest -> ReplaceDeclarationPlanResult`.
     *
     * Planned carries one deterministic exact declaration replacement plan; rejection is closed
     * by [ReplaceDeclarationPlanningFailure]. No source-write or platform capability crosses this
     * pure boundary.
     */
    fun plan(request: ReplaceDeclarationPlanRequest): ReplaceDeclarationPlanResult
}
