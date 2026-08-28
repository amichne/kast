package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.PublishedWorkspace

data class HostedAddDeclarationSemanticEvidence(
    val anchor: SymbolSelector,
    val delta: ObservedAddDeclarationDelta,
)

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
