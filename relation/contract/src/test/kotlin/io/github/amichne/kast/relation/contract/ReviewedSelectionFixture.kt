package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName

internal class ReviewedSelectionFixture(case: ReviewedEagerAdmissionCase) {
    val base = ReviewedValueFixture()
    private val selected = ReviewedLibraryCallable.admit(case.target(base), base.position(0)).flowRefined()
    val binding =
        ReviewedCallbackValueBinding.admit(
                enclosing = base.enclosing,
                body = base.body,
                occurrence = base.occurrence,
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
    val elementPath = flowPath(ValuePathSegment.Element, ValuePathSegment.Field(base.field))

    fun receiver(path: ValueAccessPath = elementPath): ValueSite {
        val target = RelationEndpoint.resolve(base.enclosing.lease, base.enclosing.scope, binding.target).flowRefined()
        val call = ValueInvocation.fromCompiler(base.enclosing, binding.occurrence.range, target).flowRefined()
        return ValueSite.fromCompiler(
                enclosing = base.enclosing,
                range = flowRange(424, 433),
                role = ValueRole.Receiver(call, ValueReceiverKind.EXTENSION),
                valuePath = path,
            )
            .flowRefined()
    }

    fun formal(): ValueFormalParameter =
        ValueFormalParameter.fromCompiler(
                callable = base.enclosing,
                position = base.position(0),
                name = SymbolDiscoveryCandidateName.parse("value").flowRefined(),
                range = flowRange(435, 436),
                executionOwner = ValueExecutionOwner.reviewedCallback(binding),
            )
            .flowRefined()

    fun returned(): ValueSite = base.source(owner = ValueExecutionOwner.reviewedCallback(binding))
}
