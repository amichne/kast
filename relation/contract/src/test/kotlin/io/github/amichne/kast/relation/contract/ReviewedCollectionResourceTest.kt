package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewedCollectionResourceTest {
    @Test
    fun `tuple prepend rejects excessive depth without dropping the tracked suffix`() {
        val f = ReviewedPositionFixture()
        val binding = f.ordinary(ReviewedLibraryOperation.TO)
        val full =
            ValueAccessPath.fromSegments(List(ValueAccessPath.MAX_DEPTH) { ValuePathSegment.Field(f.base.field) })
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED),
            ReviewedValueTransfer.tupleConstruct(tupleSource(binding, full), binding),
        )
        val available = ValueAccessPath.fromSegments(full.segments.drop(1)).flowRefined()
        val admitted = ReviewedValueTransfer.tupleConstruct(tupleSource(binding, available), binding).flowRefined()
        assertEquals(ValueAccessPath.MAX_DEPTH, admitted.target.valuePath.depth)
        assertEquals(available.segments, admitted.target.valuePath.segments.drop(1))
    }

    @Test
    fun `tuple and map positions cannot import another semantic epoch`() {
        val f = ReviewedPositionFixture()
        val stale = ReviewedPositionFixture(generation = 2)
        val binding = f.ordinary(ReviewedLibraryOperation.TO)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.tupleConstruct(stale.construct(PairSlot.FIRST).source, binding),
        )
        val source = f.getterSource(f.construct(PairSlot.FIRST).target.valuePath)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.tupleProject(source, stale.getter(PairSlot.FIRST)),
        )
        val foreign = stale.associate(PairSlot.FIRST).target.valuePath.segments.first()
        assertEquals(
            Refinement.Rejected(ValueAccessPathFailure.BASIS_MISMATCH),
            ValueAccessPath.fromSegments(listOf(foreign, ValuePathSegment.Field(f.base.field))),
        )
    }

    @Test
    fun `tuple construction and association reject another callback owner`() {
        val f = ReviewedPositionFixture()
        val other = ReviewedPositionFixture(ReviewedLibraryOperation.ITERABLE_SORTED_BY)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.tupleConstruct(
                other.construct(PairSlot.FIRST).source,
                f.ordinary(ReviewedLibraryOperation.TO),
            ),
        )
        val otherReturn = other.returned(other.construct(PairSlot.FIRST).target.valuePath)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.associatedReturn(otherReturn, f.callback),
        )
    }

    @Test
    fun `ordinary tuple selection rejects another receiver parameter context and facade`() {
        val fixture = ReviewedValueFixture()
        val file =
            SymbolDiscoveryFileIdentity.External(
                DetachedVirtualFileUrl.parse("jar:///library.jar!/kotlin/TuplesKt.class").flowRefined()
            )
        val targets =
            listOf(
                fixture.target(qualified = "kotlin.to", receiver = "B", parameters = listOf("B"), file = file),
                fixture.target(qualified = "kotlin.to", receiver = "A", parameters = listOf("A"), file = file),
                fixture.target(
                    qualified = "kotlin.to",
                    receiver = "A",
                    parameters = listOf("B"),
                    contextReceivers = listOf("fixture/Context"),
                    file = file,
                ),
                fixture.target(qualified = "kotlin.to", receiver = "A", parameters = listOf("B")),
            )
        for (target in targets) {
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                ReviewedOrdinaryLibraryCallable.admit(target),
            )
        }
    }

    private fun tupleSource(binding: ReviewedCallValueBinding, path: ValueAccessPath): ValueSite =
        ValueSite.fromCompiler(
                enclosing = binding.enclosing,
                range = flowRange(440, 441),
                role = ValueRole.Receiver(binding.invocation, ValueReceiverKind.EXTENSION),
                executionOwner = binding.invocation.executionOwner,
                valuePath = path,
            )
            .flowRefined()
}
