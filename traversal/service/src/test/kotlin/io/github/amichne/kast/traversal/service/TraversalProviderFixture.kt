package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationRequest

/** Scripted detached observations establish only composition progress, never native compiler claims. */
internal fun traversalCalleeState(
    request: RelationRequest,
    inventory: List<RelationProviderLocator.Callee>,
    consumed: Int,
): RelationProviderState {
    var state = traversalCalleeInventory(request, inventory)
    repeat(consumed) { state = state.consume() }
    return state
}

internal fun RelationFact.traversalCalleeLocator(): RelationProviderLocator.Callee =
    RelationProviderLocator.Callee.Reference(
        occurrence.file,
        occurrence.range,
        RelationProviderItemDescriptor.parse(canonicalProjection()).refined(),
    )

internal fun traversalFilteredLocator(
    request: RelationRequest,
    descriptor: String,
    offset: Int,
): RelationProviderLocator.Callee {
    val located = RelationOccurrence.fromBoundary(request.subject.file, offset, offset + 1).refined()
    return RelationProviderLocator.Callee.Reference(
        located.file,
        located.range,
        RelationProviderItemDescriptor.parse(descriptor).refined(),
    )
}

internal fun traversalPendingCalleeState(request: RelationRequest, facts: List<RelationFact>): RelationProviderState {
    val consumed =
        if (facts.isEmpty()) listOf(traversalFilteredLocator(request, "filtered-provider-item", 100))
        else facts.map { it.traversalCalleeLocator() }
    val inventory = consumed + traversalFilteredLocator(request, "unfinished-provider-item", 200)
    return traversalCalleeState(request, inventory, consumed.size)
}

internal fun traversalCalleeInventory(
    request: RelationRequest,
    inventory: List<RelationProviderLocator.Callee>,
): RelationProviderState =
    when (val position = request.position) {
        RelationReadPosition.Start -> RelationProviderState.callees(inventory)
        is RelationReadPosition.Resume -> position.continuation.providerState
    }
