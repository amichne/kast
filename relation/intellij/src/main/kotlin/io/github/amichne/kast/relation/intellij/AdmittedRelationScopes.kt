package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call

/** Both scope obligations are proven in one read attempt; exact equal inputs retain the first proof. */
internal class AdmittedRelationScopes<Scope> private constructor(val search: Scope, val subject: Scope) {
    companion object {
        fun <Scope> compile(
            request: RelationRequest,
            observation: IntellijReadObservation,
            compiler: (SymbolSearchScope, SymbolDiscoveryConstraints) -> Refinement<Scope, RelationCompilerRejection>,
        ): Refinement<AdmittedRelationScopes<Scope>, RelationCompilerRejection> {
            val searchScope = request.searchScope
            val searchConstraints = request.searchConstraints
            val search =
                when (
                    val result =
                        observation.call(IntellijReadCall.RELATION_SCOPE_COMPILE) {
                            compiler(searchScope, searchConstraints)
                        }
                ) {
                    is Refinement.Refined -> result.value
                    is Refinement.Rejected -> return result
                }
            val subject =
                if (searchScope == request.subject.scope && searchConstraints == request.subject.constraints) {
                    search
                } else
                    when (
                        val result =
                            observation.call(IntellijReadCall.RELATION_SCOPE_COMPILE) {
                                compiler(request.subject.scope, request.subject.constraints)
                            }
                    ) {
                        is Refinement.Refined -> result.value
                        is Refinement.Rejected -> return result
                    }
            return Refinement.Refined(AdmittedRelationScopes(search, subject))
        }
    }
}
