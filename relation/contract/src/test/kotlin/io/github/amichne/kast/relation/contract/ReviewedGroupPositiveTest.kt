package io.github.amichne.kast.relation.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedGroupPositiveTest {
    @Test
    fun `grouping preserves original values in qualified buckets with nested suffixes`() {
        val first = "first" to Any()
        val second = "second" to Any()
        val oracle = listOf(first, second).groupBy { it.first }
        assertEquals(listOf(first), oracle["first"])
        assertEquals(listOf(second), oracle["second"])
        val f = ReviewedGroupFixture()
        val source = f.receiver()
        val transfer = ReviewedValueTransfer.groupedReceiverElement(source, f.selector).flowRefined()
        val entry = (transfer.relation as ReviewedValueRelation.GroupPlacement).entry
        assertEquals(ReviewedGroupPlacement.ORIGINAL_ELEMENTS, entry.placement)
        assertEquals(ReviewedEntrySelection.COMPONENT, entry.selection)
        assertEquals(
            listOf(
                ValuePathSegment.MapEntryComponent(entry, MapEntrySlot.VALUE),
                ValuePathSegment.Element,
                ValuePathSegment.Field(f.base.field),
            ),
            transfer.target.valuePath.segments,
        )
        assertTrue(transfer.target.conditions.facts.single() is ReviewedGroupMembership)
        assertTrue(transfer.target.conditions.facts.single().origin.matches(source))
        assertEquals(ReviewedCallbackInvocationEvidence.Unestablished, f.selector.invocationEvidence)
    }

    @Test
    fun `whole collection grouping is content dependence rather than container identity`() {
        val f = ReviewedGroupFixture()
        val source = f.receiver(ValueAccessPath.Whole)
        val transfer = ReviewedValueTransfer.groupedReceiverElement(source, f.selector).flowRefined()
        val entry = (transfer.relation as ReviewedValueRelation.GroupPlacement).entry
        assertEquals(ReviewedEntrySelection.CONTENT, entry.selection)
        assertEquals(
            listOf(ValuePathSegment.MapEntryComponent(entry, MapEntrySlot.VALUE), ValuePathSegment.Element),
            transfer.target.valuePath.segments,
        )
        assertNotEquals(source.valuePath, transfer.target.valuePath)
    }

    @Test
    fun `transform callback owns output while selector and transform inputs remain distinct`() {
        val original = Any()
        val mapped = Any()
        assertEquals(listOf(mapped), listOf(original).groupBy({ "same" }, { mapped }).values.single())
        val f = ReviewedGroupFixture(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY_TRANSFORM)
        val transform = f.transform()
        val selectorInput =
            ReviewedValueTransfer.callbackInput(f.receiver(), f.selector, f.formal(f.selector)).flowRefined()
        val transformInput =
            ReviewedValueTransfer.callbackInput(f.receiver(), transform, f.formal(transform)).flowRefined()
        assertNotEquals(selectorInput.target.executionOwner, transformInput.target.executionOwner)
        val source = f.returned(transform)
        val result = ReviewedValueTransfer.groupedCallbackResult(source, f.selector, transform).flowRefined()
        val entry = (result.relation as ReviewedValueRelation.GroupPlacement).entry
        assertEquals(ReviewedGroupPlacement.TRANSFORMED_ELEMENTS, entry.placement)
        val callbacks = entry.callbacks as ReviewedGroupCallbacks.TransformedElements
        assertEquals(f.selector, callbacks.selector)
        assertEquals(transform, callbacks.transform)
        assertEquals(transform, result.binding)
        assertEquals(
            listOf(ValuePathSegment.MapEntryComponent(entry, MapEntrySlot.VALUE), ValuePathSegment.Element) +
                source.valuePath.segments,
            result.target.valuePath.segments,
        )
        assertTrue(result.target.conditions.facts.single() is ReviewedGroupMembership)
        assertTrue(result.retainedBytes > source.retainedBytes)
    }

    @Test
    fun `both overloads preserve qualified key content without bucket element substitution`() {
        val single = ReviewedGroupFixture()
        val transformed = ReviewedGroupFixture(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY_TRANSFORM)
        val values =
            listOf(
                ReviewedValueTransfer.groupedKeyResult(single.returned(), single.selector).flowRefined(),
                ReviewedValueTransfer.groupedKeyResult(
                        transformed.returned(),
                        transformed.selector,
                        transformed.transform(),
                    )
                    .flowRefined(),
            )
        for (value in values) {
            val entry = (value.relation as ReviewedValueRelation.GroupPlacement).entry
            assertEquals(ReviewedGroupPlacement.KEY_CONTENT, entry.placement)
            assertEquals(ReviewedEntrySelection.CONTENT, entry.selection)
            assertEquals(
                listOf(ValuePathSegment.MapEntryComponent(entry, MapEntrySlot.KEY)) + value.source.valuePath.segments,
                value.target.valuePath.segments,
            )
            assertTrue(value.target.conditions.facts.single() is ReviewedGroupKeyRepresentative)
        }
        val repeated = ReviewedValueTransfer.groupedKeyResult(single.returned(), single.selector).flowRefined()
        assertEquals(values.first(), repeated)
        assertEquals(values.first().hashCode(), repeated.hashCode())
    }

    @Test
    fun `group transform preserves upstream content meaning and conditions`() {
        val map = ReviewedMapEntryInputFixture()
        val input =
            ReviewedValueTransfer.mapPreservedKeyContent(map.receiver(ValueAccessPath.Whole), map.binding).flowRefined()
        val f = ReviewedGroupFixture(ReviewedEagerAdmissionCase.ITERABLE_GROUP_BY_TRANSFORM)
        val transform = f.transform()
        val source =
            f.returned(transform)
                .withState(valuePath = input.target.valuePath, conditions = input.target.conditions)
                .flowRefined()
        val result = ReviewedValueTransfer.groupedCallbackResult(source, f.selector, transform).flowRefined()
        val entry = (result.relation as ReviewedValueRelation.GroupPlacement).entry
        assertEquals(ReviewedEntrySelection.CONTENT, entry.selection)
        assertEquals(source.conditions.facts, result.target.conditions.facts.dropLast(1))
        assertEquals(source.valuePath.segments, result.target.valuePath.segments.drop(2))
    }
}
