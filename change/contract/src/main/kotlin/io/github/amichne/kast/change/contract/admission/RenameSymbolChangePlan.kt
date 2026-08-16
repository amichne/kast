package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning

data class RenameSymbolPlanRequest(
    val target: EditableMutationTarget,
    val newName: KotlinIdentifier,
    val occurrences: RenameSymbolOccurrenceSet,
    val evidence: AddDeclarationPlanningEvidenceInput,
)

sealed interface RenameSymbolPlanningFailure {
    data object NEW_NAME_UNCHANGED : RenameSymbolPlanningFailure
    data object REFERENCE_EVIDENCE_REQUIRED : RenameSymbolPlanningFailure
    data object REFERENCE_EVIDENCE_AMBIGUOUS : RenameSymbolPlanningFailure
    data object OCCURRENCE_EVIDENCE_MISMATCH : RenameSymbolPlanningFailure

    data class Evidence(
        val failure: ChangePlanningFailure,
    ) : RenameSymbolPlanningFailure
}

/** Strong rename input with complete target-bound semantic planning evidence. */
class AdmittedRenameSymbolPlanInput private constructor(
    val target: EditableMutationTarget,
    val newName: KotlinIdentifier,
    val occurrences: RenameSymbolOccurrenceSet,
    val evidence: CompleteChangePlanningEvidence,
) {
    companion object {
        /**
         * Proof transition: `RenameSymbolPlanRequest -> Refinement<
         * AdmittedRenameSymbolPlanInput, RenameSymbolPlanningFailure>`.
         *
         * Establishes a changed identifier plus complete normalized semantic evidence for the
         * exact compiler-grounded target. [RenameSymbolPlanningFailure] is the closed expected
         * failure. Raw names and compiler observations must cross their typed boundaries before
         * this pure transition.
         */
        fun admit(
            request: RenameSymbolPlanRequest,
        ): Refinement<AdmittedRenameSymbolPlanInput, RenameSymbolPlanningFailure> {
            if (request.newName == request.occurrences.currentName) {
                return Refinement.Rejected(RenameSymbolPlanningFailure.NEW_NAME_UNCHANGED)
            }
            return when (val evidence = CompleteChangePlanningEvidence.admit(
                request.target,
                request.evidence,
            )) {
                is Refinement.Refined -> admitOccurrenceEvidence(request, evidence.value)
                is Refinement.Rejected -> Refinement.Rejected(
                    RenameSymbolPlanningFailure.Evidence(evidence.failure),
                )
            }
        }

        private fun admitOccurrenceEvidence(
            request: RenameSymbolPlanRequest,
            evidence: CompleteChangePlanningEvidence,
        ): Refinement<AdmittedRenameSymbolPlanInput, RenameSymbolPlanningFailure> {
            val references = evidence.relations.filter {
                it.batch.request.meaning == RelationMeaning.References
            }
            if (references.isEmpty()) {
                return Refinement.Rejected(
                    RenameSymbolPlanningFailure.REFERENCE_EVIDENCE_REQUIRED,
                )
            }
            if (references.size > 1) {
                return Refinement.Rejected(
                    RenameSymbolPlanningFailure.REFERENCE_EVIDENCE_AMBIGUOUS,
                )
            }
            val expected = references.single().batch.facts.mapTo(linkedSetOf()) { fact ->
                OccurrenceEvidenceKey(
                    fact.occurrence.file.stableValue,
                    fact.occurrence.range.startInclusive,
                    fact.occurrence.range.endExclusive,
                )
            }
            val actual = request.occurrences.occurrences
                .filter { it.role == RenameSymbolOccurrenceRole.REFERENCE }
                .mapTo(linkedSetOf()) { occurrence ->
                    OccurrenceEvidenceKey(
                        occurrence.source.stableValue,
                        occurrence.range.startInclusive,
                        occurrence.range.endExclusive,
                    )
                }
            if (actual != expected) {
                return Refinement.Rejected(
                    RenameSymbolPlanningFailure.OCCURRENCE_EVIDENCE_MISMATCH,
                )
            }
            return Refinement.Refined(
                AdmittedRenameSymbolPlanInput(
                    request.target,
                    request.newName,
                    request.occurrences,
                    evidence,
                ),
            )
        }
    }
}

private data class OccurrenceEvidenceKey(
    val file: String,
    val startInclusive: Int,
    val endExclusive: Int,
)

enum class RenameSymbolObligation : ChangeVerificationObligation {
    TARGET_PREIMAGE_UNCHANGED,
    GENERATION_UNCHANGED,
    OWNER_AND_PROVENANCE_UNCHANGED,
    DECLARED_WRITE_SET_CLOSED,
    EXPECTED_POSTIMAGE_OBSERVED,
    TARGET_IDENTITY_RENAMED,
    REFERENCES_RETARGETED,
    UNRELATED_CODE_PRESERVED,
    COMPILER_DIAGNOSTICS_CLEAR,
    RESULT_GENERATION_PUBLISHED,
}

/** Pure deterministic RenameSymbol plan. */
class RenameSymbolChangePlan private constructor(
    override val planId: ChangePlanId,
    override val intent: ChangeIntent.RenameSymbol,
    val target: EditableMutationTarget,
    val evidence: CompleteChangePlanningEvidence,
    override val writes: PlannedMutationWriteSet,
    val requiredVerification: List<RenameSymbolObligation>,
) : ChangePlan {
    override val priorLease = target.lease
    override val workspaceState = target.workspaceState

    companion object {
        /**
         * Proof transition: `AdmittedRenameSymbolPlanInput -> RenameSymbolChangePlan`.
         *
         * Establishes a deterministic detached plan whose identity covers the exact target,
         * current name, replacement name, occurrence set, source preimage, evidence, and complete
         * rename obligations. There is no expected failure because the admitted input carries all
         * invariants. Raw replacement text may leave only after separate mutation admission.
         */
        fun issue(input: AdmittedRenameSymbolPlanInput): RenameSymbolChangePlan {
            val intent = ChangeIntent.RenameSymbol(
                input.target,
                input.newName,
                input.occurrences,
            )
            val mutations = input.occurrences.occurrences.map { occurrence ->
                SourceTextMutation.Replace(
                    occurrence.range,
                    occurrence.expectedName,
                    input.newName,
                )
            }
            val writes = PlannedMutationWriteSet.singleton(
                PlannedMutationWrite(
                    input.target.file,
                    input.target.sourceRoot,
                    input.target.content,
                    mutations,
                ),
            )
            val canonical = buildString {
                appendPlanningField("RENAME_SYMBOL")
                appendPlanningField(input.target.lease.workspaceRoot.value)
                appendPlanningField(input.target.lease.generation.value.toString())
                appendPlanningField(input.target.workspaceState.value)
                appendPlanningField(input.target.file.path.value)
                appendPlanningField(input.target.content.value)
                appendPlanningField(input.target.selector.fingerprint.value)
                appendPlanningField(input.occurrences.currentName.value)
                appendPlanningField(input.newName.value)
                input.occurrences.occurrences.forEach { occurrence ->
                    appendPlanningField(occurrence.source.path.value)
                    appendPlanningField(occurrence.range.startInclusive.toString())
                    appendPlanningField(occurrence.range.endExclusive.toString())
                    appendPlanningField(occurrence.role.name)
                }
                appendPlanningField(input.evidence.fingerprint.value)
                RenameSymbolObligation.entries.forEach { appendPlanningField(it.name) }
            }
            return RenameSymbolChangePlan(
                ChangePlanId.fromCanonicalIdentity(canonical),
                intent,
                input.target,
                input.evidence,
                writes,
                RenameSymbolObligation.entries,
            )
        }
    }
}

sealed interface RenameSymbolPlanResult {
    data class Planned(
        val plan: RenameSymbolChangePlan,
    ) : RenameSymbolPlanResult

    data class Rejected(
        val failure: RenameSymbolPlanningFailure,
    ) : RenameSymbolPlanResult
}

fun interface RenameSymbolPlanOperations {
    /**
     * Proof transition: `RenameSymbolPlanRequest -> RenameSymbolPlanResult`.
     *
     * Planned carries one deterministic semantic rename plan; rejection is closed by
     * [RenameSymbolPlanningFailure]. No source-write or platform capability crosses this pure
     * planning boundary.
     */
    fun plan(request: RenameSymbolPlanRequest): RenameSymbolPlanResult
}
