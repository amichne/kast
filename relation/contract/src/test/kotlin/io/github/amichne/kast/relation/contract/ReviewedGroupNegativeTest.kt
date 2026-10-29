package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReviewedGroupNegativeTest {
    @Test
    fun `transform overload rejects original element and selector return shortcuts`() {
        val f = ReviewedGroupFixture(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY_TRANSFORM)
        val transform = f.transform()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL),
            ReviewedValueTransfer.groupedReceiverElement(f.receiver(), f.selector),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.groupedCallbackResult(f.returned(), f.selector, transform),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.groupedKeyResult(f.returned(transform), f.selector, transform),
        )
    }

    @Test
    fun `paired callbacks reject wrong formal overload and invocation`() {
        val f = ReviewedGroupFixture(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY_TRANSFORM)
        val foreign = f.callback(1, 447, 459, f.base.occurrence(423, 459))
        val single = ReviewedGroupFixture()
        for ((selector, transform) in
            listOf(f.selector to f.selector, f.selector to foreign, single.selector to f.transform())) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
                ReviewedGroupCallbacks.TransformedElements.fromReviewed(selector, transform),
            )
        }
    }

    @Test
    fun `equal distinct key objects do not all become retained key identity`() {
        val firstKey = EqualKey("same")
        val laterKey = EqualKey("same")
        val firstValue = Any()
        val laterValue = Any()
        val oracle = listOf(firstKey to firstValue, laterKey to laterValue).groupBy({ it.first }, { it.second })
        assertNotSame(firstKey, laterKey)
        assertSame(firstKey, oracle.keys.single())
        assertEquals(listOf(firstValue, laterValue), oracle.values.single())
        val f = ReviewedGroupFixture()
        val key = ReviewedValueTransfer.groupedKeyResult(f.returned(), f.selector).flowRefined()
        val entry = (key.relation as ReviewedValueRelation.GroupPlacement).entry
        assertEquals(ReviewedEntrySelection.CONTENT, entry.selection)
        assertEquals(ReviewedGroupPlacement.KEY_CONTENT, entry.placement)
        assertEquals(ReviewedGroupKeyRepresentative::class, key.target.conditions.facts.single()::class)
        assertEquals(
            Refinement.Rejected(ValueAccessPathFailure.COMPONENT_MISMATCH),
            ValueAccessPath.fromSegments(listOf(ValuePathSegment.MapEntryComponent(entry, MapEntrySlot.VALUE))),
        )
    }

    @Test
    fun `constant transform excludes input unless its actual body returns it`() {
        val input = Any()
        val constant = Any()
        val oracle = listOf(input).groupBy({ "key" }, { constant })
        assertFalse(input in oracle.values.flatten())
        val f = ReviewedGroupFixture(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY_TRANSFORM)
        val transform = f.transform()
        val entered = ReviewedValueTransfer.callbackInput(f.receiver(), transform, f.formal(transform)).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.groupedCallbackResult(entered.target, f.selector, transform),
        )
    }

    @Test
    fun `component bucket proof cannot be reused for whole content at the same anchor`() {
        val f = ReviewedGroupFixture()
        val source = f.receiver()
        val result = ReviewedValueTransfer.groupedReceiverElement(source, f.selector).flowRefined()
        val entry = (result.relation as ReviewedValueRelation.GroupPlacement).entry
        val content = source.withState(valuePath = ValueAccessPath.Whole).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ReviewedGroupMembership.fromReviewed(content, entry),
        )
    }

    @Test
    fun `group bucket placement refuses paths beyond the existing depth grant`() {
        val f = ReviewedGroupFixture(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY_TRANSFORM)
        val transform = f.transform()
        val path =
            ValueAccessPath.fromSegments(List(ValueAccessPath.MAX_DEPTH) { ValuePathSegment.Field(f.base.field) })
                .flowRefined()
        val source = f.returned(transform).withState(valuePath = path).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED),
            ReviewedValueTransfer.groupedCallbackResult(source, f.selector, transform),
        )
    }

    private data class EqualKey(val value: String)
}
