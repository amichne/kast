package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedEntryPrefixTest {
    @Test
    fun `destructured entry rejects wrong prefix component and reversed source order`() {
        val f = ReviewedEntryReadFixture()
        val current = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 449)
        val wrong = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 445)
        val reversed = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1, 452)
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ordered(current, listOf(leaf(wrong))),
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
            ordered(current, listOf(leaf(reversed))),
        )
    }

    @Test
    fun `ordered entry read keeps normal prefix and slot stability as separate premises`() {
        val f = ReviewedEntryReadFixture()
        val current = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 449)
        val first = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1, 445)
        val binding = ordered(current, listOf(leaf(first))).flowRefined()
        val source = f.receiver(binding)
        val transfer = ReviewedValueTransfer.entryProject(source, binding).flowRefined()
        assertTrue(transfer.target.conditions.facts.takeLast(2).first() is ValueCondition.ReviewedEntryComponentStable)
        val prefix = transfer.target.conditions.facts.last() as ValueCondition.ReviewedEntryPrefixNormal
        assertEquals(MapEntrySlot.VALUE, prefix.slot)
        assertEquals(binding, prefix.binding)
        assertTrue(prefix.origin.matches(source))
        assertEquals(source.valuePath.segments.drop(1), transfer.target.valuePath.segments)
    }

    @Test
    fun `omitted first declaration has no invocation prefix premise`() {
        val f = ReviewedEntryReadFixture()
        val current = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 449)
        val binding = ordered(current, emptyList()).flowRefined()
        val transfer = ReviewedValueTransfer.entryProject(f.receiver(binding), binding).flowRefined()
        assertTrue(transfer.target.conditions.facts.none { it is ValueCondition.ReviewedEntryPrefixNormal })
        assertEquals(
            ReviewedEntryReadPrefix.Empty,
            (binding.entryReadOrder as ReviewedEntryReadOrder.Destructured).prefix,
        )
    }

    @Test
    fun `destructured prefix rejects foreign execution and cannot change the selected slot`() {
        val f = ReviewedEntryReadFixture()
        val current = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 449)
        val first = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1, 445)
        val foreignCall =
            ValueInvocation.fromCompiler(
                    first.invocation.enclosing,
                    first.invocation.range,
                    first.invocation.callable,
                    ValueExecutionOwner.declaration(first.invocation.enclosing),
                )
                .flowRefined()
        val foreign =
            ReviewedEntryComponentInvocation.fromCompiler(foreignCall, first.selected, first.artifact).flowRefined()
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
            ordered(current, listOf(foreign)),
        )
        val key = ReviewedEntryReadFixture(MapEntrySlot.KEY)
        val keyCurrent = key.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 449)
        val keyFirst = key.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1, 445)
        val binding = ordered(keyCurrent, listOf(leaf(keyFirst))).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY),
            ReviewedValueTransfer.entryProject(key.receiver(binding), binding),
        )
    }

    @Test
    fun `prefix proof snapshots its finite inventory and charges retained evidence`() {
        val f = ReviewedEntryReadFixture()
        val current = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 449)
        val first = leaf(f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1, 445))
        val observations = mutableListOf(first)
        val binding = ordered(current, observations).flowRefined()
        observations.clear()
        val prefix =
            (binding.entryReadOrder as ReviewedEntryReadOrder.Destructured).prefix
                as ReviewedEntryReadPrefix.FirstComponent
        assertEquals(first, prefix.invocation)
        assertTrue(binding.retainedBytes >= current.retainedBytes + first.retainedBytes)
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.FORMAL_MISMATCH),
            ordered(current, listOf(first, first)),
        )
        val repeated = ordered(current, listOf(first)).flowRefined()
        assertEquals(binding, repeated)
        assertEquals(binding.hashCode(), repeated.hashCode())
    }

    @Test
    fun `earlier destructuring group cannot supply the current groups prefix`() {
        val f = ReviewedEntryReadFixture()
        val groupA = flowRange(438, 446)
        val groupB = flowRange(447, 458)
        val earlier = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1, 440)
        val current = f.binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 452)
        assertTrue(groupA.containsValueRange(earlier.invocation.range))
        assertEquals(false, groupB.containsValueRange(earlier.invocation.range))
        assertTrue(groupB.containsValueRange(current.invocation.range))
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
            ReviewedCallValueBinding.admitDestructuredEntry(
                invocation = current.invocation,
                selected = current.selected,
                artifact = current.artifact,
                declarationRange = groupB,
                preceding = listOf(leaf(earlier)),
            ),
        )
    }

    @Test
    fun `destructuring range stays within its execution and contains the selected invocation`() {
        val current = ReviewedEntryReadFixture().binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 449)
        for (group in listOf(flowRange(433, 455), flowRange(443, 448))) {
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
                ReviewedCallValueBinding.admitDestructuredEntry(
                    invocation = current.invocation,
                    selected = current.selected,
                    artifact = current.artifact,
                    declarationRange = group,
                    preceding = emptyList(),
                ),
            )
        }
    }

    @Test
    fun `destructuring range stays in equality canonical evidence and retained storage`() {
        val current = ReviewedEntryReadFixture().binding(ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2, 449)
        val first = ordered(current, emptyList()).flowRefined()
        val second =
            ReviewedCallValueBinding.admitDestructuredEntry(
                    invocation = current.invocation,
                    selected = current.selected,
                    artifact = current.artifact,
                    declarationRange = flowRange(444, 455),
                    preceding = emptyList(),
                )
                .flowRefined()
        assertEquals(
            flowRange(443, 455),
            (first.entryReadOrder as ReviewedEntryReadOrder.Destructured).declarationRange,
        )
        assertNotEquals(first, second)
        assertNotEquals(buildString { reviewedEntryRead(first) }, buildString { reviewedEntryRead(second) })
        assertTrue(first.retainedBytes > current.retainedBytes)
        assertEquals(first.retainedBytes, second.retainedBytes)
    }

    private fun leaf(binding: ReviewedCallValueBinding) =
        ReviewedEntryComponentInvocation.fromCompiler(
                binding.invocation,
                binding.selected,
                binding.artifact,
            )
            .flowRefined()

    private fun ordered(current: ReviewedCallValueBinding, preceding: List<ReviewedEntryComponentInvocation>) =
        ReviewedCallValueBinding.admitDestructuredEntry(
            invocation = current.invocation,
            selected = current.selected,
            artifact = current.artifact,
            declarationRange = flowRange(443, 455),
            preceding = preceding,
        )
}
