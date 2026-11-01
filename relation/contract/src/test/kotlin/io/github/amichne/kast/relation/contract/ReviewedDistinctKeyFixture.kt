package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName

internal class ReviewedDistinctKeyFixture(generation: Long = 1) {
    val positions = ReviewedPositionFixture(generation = generation)
    val base = positions.base
    private val target = base.target("kotlin.collections.listOf", null, listOf("T"), 1)
    private val endpoint = RelationEndpoint.resolve(base.enclosing.lease, base.enclosing.scope, target).flowRefined()
    val invocation = ValueInvocation.fromCompiler(base.enclosing, flowRange(424, 433), endpoint).flowRefined()
    val varargFormal = ReviewedVarargFormal.fromCompiler(invocation, target, base.position(0)).flowRefined()
    val artifact =
        ReviewedStringListArtifact.admit(
                varargFormal,
                base.digest(ReviewedPositionFixture.BINARY),
                base.digest(ReviewedPositionFixture.SOURCES),
            )
            .flowRefined()
    val ranges = listOf(flowRange(425, 427), flowRange(429, 431))
    val receiver = positions.collectionReceiver(ValueAccessPath.Whole)
    private val tuple = positions.construct(PairSlot.SECOND)
    val origin = (tuple.target.valuePath.segments.first() as ValuePathSegment.PairComponent).origin
    val returned =
        ValueSite.fromCompiler(
                base.enclosing,
                origin.binding.invocation.range,
                ValueRole.Return,
                ValueExecutionOwner.reviewedCallback(positions.callback),
                tuple.target.valuePath,
            )
            .flowRefined()
    val formal =
        ValueFormalParameter.fromCompiler(
                base.enclosing,
                base.position(0),
                SymbolDiscoveryCandidateName.parse("item").flowRefined(),
                flowRange(434, 435),
                ValueExecutionOwner.reviewedCallback(positions.callback),
            )
            .flowRefined()

    fun arguments(values: List<String>): List<ReviewedStringVarargArgument> = values.mapIndexed { index, value ->
        ReviewedStringVarargArgument.fromCompiler(varargFormal, base.position(index), ranges[index], value)
            .flowRefined()
    }

    fun input(values: List<String> = listOf("a", "b")): ReviewedStringListInput =
        ReviewedStringListInput.fromCompiler(varargFormal, artifact, 2, ranges, arguments(values)).flowRefined()

    fun keyUse(
        role: ValueRole = ValueRole.Receiver(origin.binding.invocation, ValueReceiverKind.EXTENSION)
    ): ValueSite =
        ValueSite.fromCompiler(
                base.enclosing,
                flowRange(440, 441),
                role,
                ValueExecutionOwner.reviewedCallback(positions.callback),
            )
            .flowRefined()

    fun key(): ReviewedDirectFormalKey =
        ReviewedDirectFormalKey.fromCompiler(positions.callback, formal, origin, keyUse(), listOf(returned.range))
            .flowRefined()
}
