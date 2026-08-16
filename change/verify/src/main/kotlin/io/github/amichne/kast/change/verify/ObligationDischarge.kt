package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.AddDeclarationObligation
import io.github.amichne.kast.change.contract.AddFileObligation
import io.github.amichne.kast.change.contract.ChangeVerificationObligation
import io.github.amichne.kast.change.contract.RenameSymbolObligation
import io.github.amichne.kast.change.contract.ReplaceDeclarationObligation

enum class ChangeObligationProofBasis {
    APPLIED_MUTATION,
    RESULT_SOURCE,
    RESULT_RELATIONS,
    RESULT_DIAGNOSTICS,
    RESULT_DECLARATION,
    RESULT_PUBLICATION,
}

typealias AddDeclarationObligationProofBasis = ChangeObligationProofBasis

@ConsistentCopyVisibility
data class DischargedChangeObligation internal constructor(
    val obligation: ChangeVerificationObligation,
    val basis: ChangeObligationProofBasis,
)

/** Exhaustive plan obligation set discharged by exact G0-to-G1 proof. */
class DischargedChangeObligations private constructor(
    proofs: List<DischargedChangeObligation>,
) {
    val proofs: List<DischargedChangeObligation> = proofs.toList()
    val values: List<ChangeVerificationObligation> = this.proofs.map { it.obligation }

    companion object {
        /**
         * Proof transition: `CompleteChangeVerification -> DischargedChangeObligations`.
         *
         * Reifies every variant-specific obligation against its already accepted G0-to-G1 proof
         * basis. There is no expected failure because [CompleteChangeVerification] establishes an
         * exhaustive obligation set. Only the receipt boundary may expose these detached proofs.
         */
        internal fun issue(
            verification: CompleteChangeVerification,
        ): DischargedChangeObligations = DischargedChangeObligations(
            verification.obligations.map { obligation ->
                DischargedChangeObligation(obligation, obligation.proofBasis())
            },
        )
    }
}

typealias DischargedAddDeclarationObligation = DischargedChangeObligation
typealias DischargedAddDeclarationObligations = DischargedChangeObligations

private fun ChangeVerificationObligation.proofBasis(): ChangeObligationProofBasis = when (this) {
    AddFileObligation.TARGET_ABSENT_AT_G0,
    AddFileObligation.GENERATION_UNCHANGED,
    AddFileObligation.OWNER_AND_PROVENANCE_UNCHANGED,
    AddFileObligation.DECLARED_WRITE_SET_CLOSED,
    AddDeclarationObligation.TARGET_PREIMAGE_UNCHANGED,
    AddDeclarationObligation.GENERATION_UNCHANGED,
    AddDeclarationObligation.OWNER_AND_PROVENANCE_UNCHANGED,
    AddDeclarationObligation.DECLARED_WRITE_SET_CLOSED,
    RenameSymbolObligation.TARGET_PREIMAGE_UNCHANGED,
    RenameSymbolObligation.GENERATION_UNCHANGED,
    RenameSymbolObligation.OWNER_AND_PROVENANCE_UNCHANGED,
    RenameSymbolObligation.DECLARED_WRITE_SET_CLOSED,
    ReplaceDeclarationObligation.TARGET_PREIMAGE_UNCHANGED,
    ReplaceDeclarationObligation.GENERATION_UNCHANGED,
    ReplaceDeclarationObligation.OWNER_AND_PROVENANCE_UNCHANGED,
    ReplaceDeclarationObligation.DECLARED_WRITE_SET_CLOSED,
        -> ChangeObligationProofBasis.APPLIED_MUTATION
    AddDeclarationObligation.EXPECTED_POSTIMAGE_OBSERVED,
    AddFileObligation.EXPECTED_POSTIMAGE_OBSERVED,
    AddFileObligation.UNRELATED_CODE_PRESERVED,
    RenameSymbolObligation.EXPECTED_POSTIMAGE_OBSERVED,
    RenameSymbolObligation.UNRELATED_CODE_PRESERVED,
    ReplaceDeclarationObligation.EXPECTED_POSTIMAGE_OBSERVED,
    ReplaceDeclarationObligation.UNRELATED_CODE_PRESERVED,
        -> ChangeObligationProofBasis.RESULT_SOURCE
    AddDeclarationObligation.OUTBOUND_BINDINGS_PRESERVED,
    AddDeclarationObligation.EXISTING_BINDINGS_PRESERVED,
    RenameSymbolObligation.REFERENCES_RETARGETED,
        -> ChangeObligationProofBasis.RESULT_RELATIONS
    AddDeclarationObligation.COMPILER_DIAGNOSTICS_CLEAR,
    AddFileObligation.COMPILER_DIAGNOSTICS_CLEAR,
    RenameSymbolObligation.COMPILER_DIAGNOSTICS_CLEAR,
    ReplaceDeclarationObligation.COMPILER_DIAGNOSTICS_CLEAR,
        -> ChangeObligationProofBasis.RESULT_DIAGNOSTICS
    AddDeclarationObligation.DECLARATION_IDENTITY_OBSERVED,
    AddFileObligation.FILE_IDENTITY_CREATED,
    AddDeclarationObligation.COMPILER_COLLISION_REMAINS_ABSENT,
    RenameSymbolObligation.TARGET_IDENTITY_RENAMED,
    ReplaceDeclarationObligation.REPLACEMENT_DECLARATION_OBSERVED,
        -> ChangeObligationProofBasis.RESULT_DECLARATION
    AddDeclarationObligation.RESULT_GENERATION_PUBLISHED,
    AddFileObligation.RESULT_GENERATION_PUBLISHED,
    RenameSymbolObligation.RESULT_GENERATION_PUBLISHED,
    ReplaceDeclarationObligation.RESULT_GENERATION_PUBLISHED,
        -> ChangeObligationProofBasis.RESULT_PUBLICATION
}
