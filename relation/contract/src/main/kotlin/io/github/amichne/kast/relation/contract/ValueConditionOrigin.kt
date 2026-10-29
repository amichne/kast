package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity

/** The subject of a conditional fact, without recursively embedding its preceding conditions. */
@ConsistentCopyVisibility
data class ValueConditionOrigin
private constructor(
    val execution: ValueExecutionOwner,
    val range: ExactDeclarationTextRange,
    val role: ValueRole,
    val valuePath: ValueAccessPath,
    val callStack: ValueCallStack,
) {
    val basis: SemanticReadIdentity
        get() = execution.enclosing.lease.identity

    fun matches(site: ValueSite): Boolean = this == fromSite(site)

    companion object {
        internal fun fromSite(site: ValueSite): ValueConditionOrigin =
            ValueConditionOrigin(site.executionOwner, site.range, site.role, site.valuePath, site.callStack)
    }
}
