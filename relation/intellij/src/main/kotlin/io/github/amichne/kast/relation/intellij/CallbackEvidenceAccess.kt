package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackEvidenceDomain
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolSearchScope

/** Exact callback proof can read model-owned sources. Result eligibility keeps the selected scope. */
internal class CallbackEvidenceAccess private constructor(val sources: CompiledRelationScope) {
    companion object {
        fun compile(
            selected: CompiledRelationScope,
            compiler:
                (SymbolSearchScope, SymbolDiscoveryConstraints) -> Refinement<
                        CompiledRelationScope,
                        RelationCompilerRejection,
                    >,
        ): Refinement<CallbackEvidenceAccess, RelationCompilerRejection> {
            when (selected.request.meaning) {
                RelationMeaning.Callers,
                RelationMeaning.Callees -> Unit
                RelationMeaning.References,
                RelationMeaning.TypeUses,
                RelationMeaning.Implementations,
                RelationMeaning.Inheritors,
                RelationMeaning.Overrides -> return Refinement.Refined(CallbackEvidenceAccess(selected))
            }
            // Supplier absence needs every imported source owner, including reverse module dependencies.
            // Binary contracts keep their separate compiler-origin admission and never read dependency bodies.
            val universe = CallbackEvidenceDomain.forRequest(selected.request)
            return when (val compiled = compiler(universe.sources, universe.constraints)) {
                is Refinement.Refined -> Refinement.Refined(CallbackEvidenceAccess(compiled.value))
                is Refinement.Rejected -> compiled
            }
        }
    }
}
