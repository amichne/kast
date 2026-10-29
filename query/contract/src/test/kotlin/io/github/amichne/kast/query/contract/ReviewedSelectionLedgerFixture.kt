package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName

internal class ReviewedSelectionLedgerFixture {
    val components = ReviewedComponentLedgerFixture()
    val base = components.base
    private val owner = components.owner
    val filter = components.callback("filter", "(T)->kotlin/Boolean", 1)
    val nonNull = components.callback("mapNotNull", "(T)->R?", 2)

    fun filtered(whole: Boolean = false): ReviewedValueTransfer =
        ReviewedValueTransfer.filteredResult(receiver(filter, whole), filter).componentValue()

    fun filterInput(): ReviewedValueTransfer = input(filter)

    fun nonNullInput(): ReviewedValueTransfer = input(nonNull)

    fun nonNullResult(): ReviewedValueTransfer {
        val source =
            ValueSite.fromCompiler(
                    owner,
                    componentRange(50, 60),
                    ValueRole.Return,
                    ValueExecutionOwner.reviewedCallback(nonNull),
                    valuePath = components.suffix,
                )
                .componentValue()
        return ReviewedValueTransfer.callbackResult(source, nonNull).componentValue()
    }

    private fun input(binding: ReviewedCallbackValueBinding): ReviewedValueTransfer {
        val formal =
            ValueFormalParameter.fromCompiler(
                    owner,
                    ValueArgumentPosition.parse(0).componentValue(),
                    SymbolDiscoveryCandidateName.parse("element").componentValue(),
                    componentRange(41, 42),
                    ValueExecutionOwner.reviewedCallback(binding),
                )
                .componentValue()
        return ReviewedValueTransfer.callbackInput(receiver(binding), binding, formal).componentValue()
    }

    private fun receiver(binding: ReviewedCallbackValueBinding, whole: Boolean = false): ValueSite {
        val target = RelationEndpoint.resolve(owner.lease, owner.scope, binding.target).componentValue()
        val invocation = ValueInvocation.fromCompiler(owner, binding.occurrence.range, target).componentValue()
        return ValueSite.fromCompiler(
                owner,
                componentRange(30, 35),
                ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION),
                valuePath =
                    if (whole) ValueAccessPath.Whole
                    else
                        ValueAccessPath.fromSegments(listOf(ValuePathSegment.Element) + components.suffix.segments)
                            .componentValue(),
            )
            .componentValue()
    }
}
