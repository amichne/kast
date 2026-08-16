package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.AddDeclarationObligation

enum class AddDeclarationObligationProofBasis {
    APPLIED_MUTATION,
    RESULT_SOURCE,
    RESULT_RELATIONS,
    RESULT_DIAGNOSTICS,
    RESULT_DECLARATION,
    RESULT_PUBLICATION,
}

@ConsistentCopyVisibility
data class DischargedAddDeclarationObligation internal constructor(
    val obligation: AddDeclarationObligation,
    val basis: AddDeclarationObligationProofBasis,
)

/** Exhaustive plan obligation set discharged by exact G0-to-G1 proof. */
class DischargedAddDeclarationObligations private constructor(
    proofs: List<DischargedAddDeclarationObligation>,
) {
    val proofs: List<DischargedAddDeclarationObligation> = proofs.toList()
    val values: List<AddDeclarationObligation> = this.proofs.map { it.obligation }

    companion object {
        /**
         * Proof transition: `CompleteAddDeclarationVerification ->
         * DischargedAddDeclarationObligations`.
         *
         * Reifies every planned obligation against its already re-evaluated G0-to-G1 proof basis.
         * There is no expected failure because [CompleteAddDeclarationVerification] establishes the
         * exhaustive obligation set and every required basis. Raw extraction is prohibited; only
         * the receipt boundary may expose these detached proof records.
         */
        internal fun issue(
            verification: CompleteAddDeclarationVerification,
        ): DischargedAddDeclarationObligations = DischargedAddDeclarationObligations(
            verification.plan.requiredVerification.obligations.map { obligation ->
                DischargedAddDeclarationObligation(obligation, obligation.proofBasis())
            },
        )
    }
}

private fun AddDeclarationObligation.proofBasis(): AddDeclarationObligationProofBasis = when (this) {
    AddDeclarationObligation.TARGET_PREIMAGE_UNCHANGED,
    AddDeclarationObligation.GENERATION_UNCHANGED,
    AddDeclarationObligation.OWNER_AND_PROVENANCE_UNCHANGED,
    AddDeclarationObligation.DECLARED_WRITE_SET_CLOSED,
        -> AddDeclarationObligationProofBasis.APPLIED_MUTATION
    AddDeclarationObligation.EXPECTED_POSTIMAGE_OBSERVED ->
        AddDeclarationObligationProofBasis.RESULT_SOURCE
    AddDeclarationObligation.OUTBOUND_BINDINGS_PRESERVED,
    AddDeclarationObligation.EXISTING_BINDINGS_PRESERVED,
        -> AddDeclarationObligationProofBasis.RESULT_RELATIONS
    AddDeclarationObligation.COMPILER_DIAGNOSTICS_CLEAR ->
        AddDeclarationObligationProofBasis.RESULT_DIAGNOSTICS
    AddDeclarationObligation.DECLARATION_IDENTITY_OBSERVED,
    AddDeclarationObligation.COMPILER_COLLISION_REMAINS_ABSENT,
        -> AddDeclarationObligationProofBasis.RESULT_DECLARATION
    AddDeclarationObligation.RESULT_GENERATION_PUBLISHED ->
        AddDeclarationObligationProofBasis.RESULT_PUBLICATION
}
