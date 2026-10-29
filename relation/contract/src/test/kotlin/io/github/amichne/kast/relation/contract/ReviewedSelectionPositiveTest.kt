package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedSelectionPositiveTest {
    @Test
    fun `filter preserves selected element suffix with membership and normal result premises`() {
        val kept = Any()
        val original = listOf(kept)
        val oracle = original.filter { true }
        assertEquals(original, oracle)
        assertNotSame(original, oracle)
        val f = ReviewedSelectionFixture(ReviewedEagerAdmissionCase.ITERABLE_FILTER)
        val source = f.receiver()
        val transfer = ReviewedValueTransfer.filteredResult(source, f.binding).flowRefined()
        assertEquals(source.valuePath, transfer.target.valuePath)
        assertEquals(ReviewedValueRelation.FilteredElements(ReviewedCollectionSelection.ELEMENT), transfer.relation)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertEquals(f.binding.occurrence.range, transfer.target.range)
        assertEquals(
            listOf(ValueCondition.ReviewedCollectionNormalResult::class, ReviewedPredicateAccepts::class),
            transfer.target.conditions.facts.map { it::class },
        )
        assertTrue(transfer.target.conditions.facts.all { it.origin.matches(source) })
        assertEquals(ReviewedCallbackInvocationEvidence.Unestablished, f.binding.invocationEvidence)
    }

    @Test
    fun `whole filter input stays content and still traverses predicate formal`() {
        val f = ReviewedSelectionFixture(ReviewedEagerAdmissionCase.ITERABLE_FILTER)
        val source = f.receiver(ValueAccessPath.Whole)
        val result = ReviewedValueTransfer.filteredResult(source, f.binding).flowRefined()
        assertEquals(ReviewedValueRelation.FilteredElements(ReviewedCollectionSelection.CONTENT), result.relation)
        val input = ReviewedValueTransfer.callbackInput(source, f.binding, f.formal()).flowRefined()
        assertEquals(
            ReviewedValueRelation.CallbackCorrespondence(
                ReviewedValueSemantics.CONDITIONAL_RECEIVER_CONTENT_TO_CALLBACK_PARAMETER
            ),
            input.relation,
        )
        assertTrue(input.target.role is ValueRole.Formal)
    }

    @Test
    fun `actual nullable callback result occupies output element and keeps nonnull premise`() {
        val kept = Any()
        val original = listOf(kept, null)
        assertEquals(listOf(kept), original.mapNotNull { it })
        val f = ReviewedSelectionFixture(ReviewedEagerAdmissionCase.ITERABLE_MAP_NOT_NULL)
        val source = f.returned()
        val transfer = ReviewedValueTransfer.callbackResult(source, f.binding).flowRefined()
        assertEquals(listOf(ValuePathSegment.Element) + source.valuePath.segments, transfer.target.valuePath.segments)
        assertEquals(ReviewedValueRelation.NonNullCallbackResult, transfer.relation)
        val premise = transfer.target.conditions.facts.single() as ReviewedCallbackResultNonNull
        assertTrue(premise.origin.matches(source))
        assertEquals(f.binding, premise.binding)
        assertEquals(source.callStack, transfer.target.callStack)
        assertEquals(ReviewedCallbackInvocationEvidence.Unestablished, f.binding.invocationEvidence)
        val repeated = ReviewedValueTransfer.callbackResult(source, f.binding).flowRefined()
        assertEquals(transfer, repeated)
        assertEquals(transfer.hashCode(), repeated.hashCode())
        assertTrue(transfer.retainedBytes > source.retainedBytes)
    }
}
