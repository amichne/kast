package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.PublishedWorkspace

data class HostedAddDeclarationSemanticEvidence(
    val anchor: SymbolSelector,
    val delta: ObservedAddDeclarationDelta,
)

enum class CompilerReobservedMutationAnchorFailure {
    FILE_MISMATCH,
    NAME_MISMATCH,
    START_OFFSET_MISMATCH,
    COMPILER_EVIDENCE_MISMATCH,
}

/** Live K2 evidence for the mutation anchor, proven equivalent to its prior compiler meaning. */
class CompilerReobservedMutationAnchor private constructor(
    val evidence: CompilerGroundedSymbolEvidence,
) {
    companion object {
        /**
         * Allows the declaration range to grow after an intended edit, but requires the current
         * compiler signature, kind, qualified identity, file, name, and start location to remain
         * exactly the prior anchor's. A PSI match cannot manufacture this proof.
         */
        fun admit(
            prior: SymbolSelector,
            current: CompilerGroundedSymbolEvidence,
        ): Refinement<
            CompilerReobservedMutationAnchor,
            CompilerReobservedMutationAnchorFailure,
        > {
            if (current.file != prior.file) {
                return Refinement.Rejected(
                    CompilerReobservedMutationAnchorFailure.FILE_MISMATCH,
                )
            }
            if (current.name != prior.name) {
                return Refinement.Rejected(
                    CompilerReobservedMutationAnchorFailure.NAME_MISMATCH,
                )
            }
            if (current.range.startInclusive != prior.range.startInclusive) {
                return Refinement.Rejected(
                    CompilerReobservedMutationAnchorFailure.START_OFFSET_MISMATCH,
                )
            }
            if (
                current.qualifiedIdentity != prior.qualifiedIdentity ||
                current.kind != prior.kind ||
                current.signature != prior.signature ||
                current.compilerIdentity != prior.compilerIdentity
            ) {
                return Refinement.Rejected(
                    CompilerReobservedMutationAnchorFailure.COMPILER_EVIDENCE_MISMATCH,
                )
            }
            return Refinement.Refined(CompilerReobservedMutationAnchor(current))
        }
    }
}

enum class HostedAddDeclarationSemanticObservationFailure {
    PROJECT_UNAVAILABLE,
    ROOT_OR_GENERATION_MISMATCH,
    TARGET_UNAVAILABLE,
    DECLARATION_MISSING_OR_AMBIGUOUS,
    EVIDENCE_REJECTED,
}

sealed interface HostedAddDeclarationSemanticObservation {
    data class Observed(val evidence: HostedAddDeclarationSemanticEvidence) :
        HostedAddDeclarationSemanticObservation
    data class Rejected(val failure: HostedAddDeclarationSemanticObservationFailure) :
        HostedAddDeclarationSemanticObservation
}

/** Narrow physical compiler observation used only by hosted add-declaration verification. */
fun interface HostedAddDeclarationSemanticObserver {
    fun observe(
        workspace: PublishedWorkspace,
        plan: AddDeclarationChangePlan,
    ): HostedAddDeclarationSemanticObservation
}
