package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName

internal class ReviewedGroupFixture(
    val case: ReviewedEagerAdmissionCase = ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY
) {
    val base = ReviewedValueFixture()
    val selector = callback(0, 434, 444)
    val elementPath = flowPath(ValuePathSegment.Element, ValuePathSegment.Field(base.field))

    fun transform(): ReviewedCallbackValueBinding = callback(1, 447, 459)

    fun callback(
        position: Int,
        start: Int,
        end: Int,
        occurrence: RelationOccurrence = base.occurrence,
    ): ReviewedCallbackValueBinding {
        val selected = ReviewedLibraryCallable.admit(case.target(base), base.position(position)).flowRefined()
        return ReviewedCallbackValueBinding.admit(
                enclosing = base.enclosing,
                body = base.body(start, end),
                occurrence = occurrence,
                supplier = base.supplier,
                selected = selected,
                artifact =
                    ReviewedLibraryArtifact.admit(
                            selected,
                            base.digest(ReviewedPositionFixture.BINARY),
                            base.digest(ReviewedPositionFixture.SOURCES),
                        )
                        .flowRefined(),
            )
            .flowRefined()
    }

    fun receiver(path: ValueAccessPath = elementPath): ValueSite {
        val endpoint =
            RelationEndpoint.resolve(base.enclosing.lease, base.enclosing.scope, selector.target).flowRefined()
        val call = ValueInvocation.fromCompiler(base.enclosing, selector.occurrence.range, endpoint).flowRefined()
        return ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = flowRange(424, 433),
                role = ValueRole.Receiver(call, ValueReceiverKind.EXTENSION),
                valuePath = path,
            )
            .flowRefined()
    }

    fun returned(binding: ReviewedCallbackValueBinding = selector): ValueSite =
        ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = flowRange(binding.body.range.startInclusive + 2, binding.body.range.endExclusive - 1),
                role = ValueRole.Return,
                executionOwner = ValueExecutionOwner.reviewedCallback(binding),
                valuePath = base.fieldPath,
            )
            .flowRefined()

    fun formal(binding: ReviewedCallbackValueBinding): ValueFormalParameter =
        ValueFormalParameter.fromCompiler(
                callable = base.enclosing,
                position = base.position(0),
                name = SymbolDiscoveryCandidateName.parse("value").flowRefined(),
                range = flowRange(binding.body.range.startInclusive + 1, binding.body.range.startInclusive + 2),
                executionOwner = ValueExecutionOwner.reviewedCallback(binding),
            )
            .flowRefined()
}
