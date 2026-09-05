package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.topology.contract.TopologyBindingFailure

internal enum class TopologySourceRole {
    CONSTRUCTOR, FUNCTION, PROPERTY, TYPE_ALIAS, CLASS_LIKE, UNSUPPORTED;

    companion object {
        fun from(kind: CompilerSymbolKind): TopologySourceRole = when (kind) {
            CompilerSymbolKind.CONSTRUCTOR -> CONSTRUCTOR
            CompilerSymbolKind.FUNCTION -> FUNCTION
            CompilerSymbolKind.PROPERTY -> PROPERTY
            CompilerSymbolKind.TYPE_ALIAS -> TYPE_ALIAS
            CompilerSymbolKind.CLASSLIKE -> CLASS_LIKE
        }
    }
}

/** Agreement among the registry role and both native source roles. */
internal class TopologyBindingRole private constructor(val kind: CompilerSymbolKind) {
    companion object {
        fun admit(
            registry: CompilerSymbolKind,
            declared: TopologySourceRole,
            resolved: TopologySourceRole,
        ): Refinement<TopologyBindingRole, TopologyBindingFailure> = when {
            declared == TopologySourceRole.UNSUPPORTED || resolved == TopologySourceRole.UNSUPPORTED ->
                Refinement.Rejected(TopologyBindingFailure.ORIGIN_NOT_ADMITTED)
            declared != resolved || declared != TopologySourceRole.from(registry) ->
                Refinement.Rejected(TopologyBindingFailure.ROLE_MISMATCH)
            else -> Refinement.Refined(TopologyBindingRole(registry))
        }
    }
}
