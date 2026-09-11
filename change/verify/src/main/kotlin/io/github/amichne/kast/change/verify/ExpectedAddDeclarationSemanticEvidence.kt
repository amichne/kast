package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.DurableAddDeclarationPlanningEvidence
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** The original compiler anchor and exact planned evidence scopes retained for either authority variant. */
internal data class ExpectedAddDeclarationSemanticEvidence(
    val prior: CompilerGroundedSymbolEvidence,
    val scope: SymbolSearchScope,
    val authority: SemanticReadAuthority,
    val planned: DurableAddDeclarationPlanningEvidence,
    val diagnosticScopes: List<Set<String>>,
) {
    fun matchesTarget(subject: RelationEndpoint): Boolean {
        if (
            subject.file != prior.file ||
                subject.name != prior.name ||
                subject.qualifiedIdentity != prior.qualifiedIdentity
        )
            return false
        return subject.kind == prior.kind && subject.scope == scope
    }
}
