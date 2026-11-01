package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** The selected implicit result whose role must be revalidated. */
enum class ValueLoopResultPhase {
    ITERATOR,
    ELEMENT,
}

internal fun ValueSiteRoleClaim.LoopResult.admitLoopRole(
    actual: ValueRole,
    owner: SymbolSelector,
): Refinement<Unit, ValueSiteRevalidationFailure> {
    if (actual !is ValueRole.LoopResult) return Refinement.Rejected(ValueSiteRevalidationFailure.ROLE_MISMATCH)
    val result = actual.result
    val actualPhase =
        when (result) {
            is ValueLoopResult.Iterator -> ValueLoopResultPhase.ITERATOR
            is ValueLoopResult.Element -> ValueLoopResultPhase.ELEMENT
        }
    if (phase != actualPhase) return Refinement.Rejected(ValueSiteRevalidationFailure.ROLE_MISMATCH)
    val call = result.invocation
    if (
        call.basis != owner.lease.identity ||
            !call.enclosing.matchesLoopSelector(owner) ||
            call.range != invocationAnchor
    )
        return Refinement.Rejected(ValueSiteRevalidationFailure.INVOCATION_MISMATCH)
    val protocol = result.protocol
    val matches =
        protocol.iterator.callable.matchesLoopSelector(expectedIterator) &&
            protocol.hasNext.callable.matchesLoopSelector(expectedHasNext) &&
            protocol.next.callable.matchesLoopSelector(expectedNext)
    return if (matches) Refinement.Refined(Unit)
    else Refinement.Rejected(ValueSiteRevalidationFailure.CALLABLE_MISMATCH)
}

private fun RelationEndpoint.matchesLoopSelector(selector: SymbolSelector): Boolean =
    compilerIdentity == selector.compilerIdentity && file == selector.file && range == selector.range
