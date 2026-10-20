package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolSelector

/** Full proof correspondence is local to source-function producer admission, not global invocation equality. */
internal fun ValueProducerOrigin.SourceFunction.admitSeedProof(
    request: ValueProducerSeedRequest,
    site: ValueSite,
    selection: ValueInvocationSelection,
): Refinement<Unit, ValueProducerSeedFailure> =
    when {
        !invocation.enclosing.matchesSeedSelector(request.enclosing) ||
            !site.enclosing.sameSeedProof(invocation.enclosing) ->
            Refinement.Rejected(ValueProducerSeedFailure.ENCLOSING_MISMATCH)
        !property.endpoint.matchesSeedSelector(request.expectedCallable) ->
            Refinement.Rejected(ValueProducerSeedFailure.CALLABLE_MISMATCH)
        !invocation.executionOwner.enclosing.matchesSeedSelector(request.enclosing) ||
            !site.executionOwner.enclosing.sameSeedProof(invocation.executionOwner.enclosing) ||
            site.executionOwner.body != invocation.executionOwner.body ->
            Refinement.Rejected(ValueProducerSeedFailure.EXECUTION_OWNER_MISMATCH)
        !invocation.hasSameSeedProof(selection.invocation) ->
            Refinement.Rejected(ValueProducerSeedFailure.ANCHOR_MISMATCH)
        else -> Refinement.Refined(Unit)
    }

private fun ValueFunctionInvocation.hasSameSeedProof(other: ValueCallOccurrence): Boolean =
    other is ValueFunctionInvocation &&
        identity == other.identity &&
        enclosing.sameSeedProof(other.enclosing) &&
        signature == other.signature &&
        callable == other.callable &&
        executionOwner.enclosing.sameSeedProof(other.executionOwner.enclosing) &&
        executionOwner.body == other.executionOwner.body

private fun RelationEndpoint.matchesSeedSelector(selector: SymbolSelector): Boolean =
    lease == selector.lease &&
        scope == selector.scope &&
        constraints == selector.constraints &&
        compilerIdentity == selector.compilerIdentity &&
        signature == selector.signature &&
        kind == selector.kind &&
        file == selector.file &&
        range == selector.range &&
        name == selector.name &&
        qualifiedIdentity == selector.qualifiedIdentity

private fun RelationEndpoint.sameSeedProof(other: RelationEndpoint): Boolean =
    lease == other.lease &&
        scope == other.scope &&
        constraints == other.constraints &&
        compilerIdentity == other.compilerIdentity &&
        signature == other.signature &&
        kind == other.kind &&
        file == other.file &&
        range == other.range &&
        name == other.name &&
        qualifiedIdentity == other.qualifiedIdentity
