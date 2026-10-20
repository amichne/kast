package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolSelector

internal fun admitProducerOccurrence(
    request: ValueProducerSeedRequest,
    site: ValueSite,
    origin: ValueProducerOrigin,
    selection: ValueInvocationSelection,
): Refinement<Unit, ValueProducerSeedFailure> {
    val invocation = origin.invocation
    return when {
        site.basis != request.enclosing.lease.identity || invocation.basis != site.basis ->
            Refinement.Rejected(ValueProducerSeedFailure.BASIS_MISMATCH)
        !site.enclosing.sameDeclaration(request.enclosing) ||
            !invocation.enclosing.sameDeclaration(request.enclosing) ->
            Refinement.Rejected(ValueProducerSeedFailure.ENCLOSING_MISMATCH)
        !origin.matchesExpected(request.expectedCallable) ->
            Refinement.Rejected(ValueProducerSeedFailure.CALLABLE_MISMATCH)
        site.range != invocation.range ||
            request.anchor != selection.selectedRange ||
            selection.invocation != invocation -> Refinement.Rejected(ValueProducerSeedFailure.ANCHOR_MISMATCH)
        site.role != ValueRole.ExpressionResult -> Refinement.Rejected(ValueProducerSeedFailure.ROLE_MISMATCH)
        site.executionOwner != invocation.executionOwner ->
            Refinement.Rejected(ValueProducerSeedFailure.EXECUTION_OWNER_MISMATCH)
        else -> Refinement.Refined(Unit)
    }
}

private fun ValueProducerOrigin.matchesExpected(selector: SymbolSelector): Boolean =
    when (this) {
        is ValueProducerOrigin.Declared -> invocation.callable.sameDeclaration(selector)
        is ValueProducerOrigin.SourceFunction -> property.endpoint.sameDeclaration(selector)
    }

private fun RelationEndpoint.sameDeclaration(selector: SymbolSelector): Boolean =
    compilerIdentity == selector.compilerIdentity && file == selector.file && range == selector.range
