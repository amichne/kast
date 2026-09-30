package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationProviderElementClass
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Explicit detached work for collector-only cases; it establishes no native discovery or compiler claim. */
internal fun detachedRelationInventory(
    request: RelationRequest,
    descriptors: List<String>,
    ranges: List<ExactDeclarationTextRange> = descriptors.indices.map { exactFixtureRange(it * 2, it * 2 + 1) },
): RelationProviderState {
    check(descriptors.size == ranges.size)
    val items =
        descriptors.indices.map { ordinal ->
            Triple(
                request.subject.file,
                ranges[ordinal],
                RelationProviderItemDescriptor.parse(descriptors[ordinal]).fixtureValue(),
            )
        }
    return when (request.providerCursor.provider) {
        RelationProviderKind.INTELLIJ_REFERENCES_V2 ->
            RelationProviderState.references(
                items.map { (file, range, descriptor) ->
                    RelationProviderLocator.Reference(file, range, descriptor)
                }
            )
        RelationProviderKind.INTELLIJ_DEFINITIONS_V2 ->
            RelationProviderState.definitions(
                items.map { (file, range, descriptor) ->
                    RelationProviderLocator.Definition.Normalized(
                        file,
                        range,
                        descriptor,
                        RelationProviderElementClass.parse("fixture.Declaration").fixtureValue(),
                        file,
                    )
                }
            )
        RelationProviderKind.INTELLIJ_CALLEES_V2 ->
            RelationProviderState.callees(
                items.map { (file, range, descriptor) ->
                    RelationProviderLocator.Callee.Reference(file, range, descriptor)
                }
            )
        RelationProviderKind.PUBLISHED_TOPOLOGY_V1 ->
            error("A native inventory fixture cannot establish published topology")
    }
}

internal fun exactFixtureRange(start: Int, end: Int): ExactDeclarationTextRange =
    ExactDeclarationTextRange.parse(start, end).fixtureValue()

private fun <Value> Refinement<Value, *>.fixtureValue(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Detached fixture input rejected: $failure")
    }
