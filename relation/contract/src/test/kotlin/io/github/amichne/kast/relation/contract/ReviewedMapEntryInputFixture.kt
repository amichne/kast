package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName

internal class ReviewedMapEntryInputFixture {
    private val placement = ReviewedMapValuesPlacementFixture()
    val base = placement.base
    val binding =
        (placement.read { placement.returned(it) } as MapValuesPlacementOutcome.Observed).transfer.binding
            as ReviewedCallbackValueBinding

    fun selectedPath(slot: MapEntrySlot): ValueAccessPath = selectedValue(slot).valuePath

    fun selectedValue(slot: MapEntrySlot): ValueSite {
        val producer = ReviewedPositionFixture()
        val tupleSlot = if (slot == MapEntrySlot.KEY) PairSlot.FIRST else PairSlot.SECOND
        return producer.associate(tupleSlot).target
    }

    fun receiver(
        path: ValueAccessPath = selectedPath(MapEntrySlot.VALUE),
        kind: ValueReceiverKind = ValueReceiverKind.EXTENSION,
        callRange: ExactDeclarationTextRange = binding.occurrence.range,
    ): ValueSite {
        val target = RelationEndpoint.resolve(base.enclosing.lease, base.enclosing.scope, binding.target).flowRefined()
        val invocation = ValueInvocation.fromCompiler(base.enclosing, callRange, target).flowRefined()
        return ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = flowRange(424, 433),
                role = ValueRole.Receiver(invocation, kind),
                valuePath = path,
            )
            .flowRefined()
    }

    fun formal(): ValueFormalParameter =
        ValueFormalParameter.fromCompiler(
                callable = base.enclosing,
                position = base.position(0),
                name = SymbolDiscoveryCandidateName.parse("entry").flowRefined(),
                range = flowRange(435, 436),
                executionOwner = ValueExecutionOwner.reviewedCallback(binding),
            )
            .flowRefined()
}
