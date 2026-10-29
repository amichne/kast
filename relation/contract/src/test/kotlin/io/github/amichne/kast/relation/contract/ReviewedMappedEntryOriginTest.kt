package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedMappedEntryOriginTest {
    @Test
    fun `mapped return provenance cannot name an output key`() {
        val f = ReviewedMapValuesPlacementFixture()
        val transfer = f.transfer()
        val relation = transfer.relation as ReviewedValueRelation.MappedValue
        assertEquals(
            Refinement.Rejected(ValueAccessPathFailure.COMPONENT_MISMATCH),
            ValueAccessPath.fromSegments(listOf(ValuePathSegment.MapEntryComponent(relation.entry, MapEntrySlot.KEY))),
        )
        assertEquals(
            ValuePathSegment.MapEntryComponent(relation.entry, MapEntrySlot.VALUE),
            transfer.target.valuePath.segments.first(),
        )
    }

    @Test
    fun `mapped key survival cannot acquire another callback owner or return range`() {
        val f = ReviewedMapValuesPlacementFixture()
        val transfer = f.transfer()
        val entry = (transfer.relation as ReviewedValueRelation.MappedValue).entry
        val sibling = f.base.source()
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueCondition.ReviewedMappedKeySurvives.fromReviewed(sibling, entry),
        )
        val binding = transfer.binding as ReviewedCallbackValueBinding
        val anotherReturn =
            ValueSite.fromCompiler(
                    enclosing = transfer.source.enclosing,
                    range = flowRange(450, 451),
                    role = ValueRole.Return,
                    executionOwner = transfer.source.executionOwner,
                    valuePath = transfer.source.valuePath,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueCondition.ReviewedMappedKeySurvives.fromReviewed(anotherReturn, entry),
        )
        assertNotEquals(entry, ReviewedMappedEntryOrigin.fromReviewed(anotherReturn, binding).flowRefined())
    }

    @Test
    fun `mapValues placement consumes bounded depth without erasing the suffix`() {
        val f = ReviewedMapValuesPlacementFixture()
        val path =
            ValueAccessPath.fromSegments(List(ValueAccessPath.MAX_DEPTH) { ValuePathSegment.Field(f.base.field) })
                .flowRefined()
        assertEquals(
            MapValuesPlacementOutcome.TransferRejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED),
            f.read { f.returned(it, path) },
        )
        val shorter = ValueAccessPath.fromSegments(path.segments.dropLast(1)).flowRefined()
        val transfer = (f.read { f.returned(it, shorter) } as MapValuesPlacementOutcome.Observed).transfer
        assertEquals(ValueAccessPath.MAX_DEPTH, transfer.target.valuePath.depth)
        assertEquals(shorter.segments, transfer.target.valuePath.segments.drop(1))
    }

    @Test
    fun `mapped value survival remains an explicit premise and cannot become compiler authority`() {
        val f = ReviewedMapValuesPlacementFixture()
        val transfer = f.transfer()
        val entry = (transfer.relation as ReviewedValueRelation.MappedValue).entry
        val condition = transfer.target.conditions.facts.single() as ValueCondition.ReviewedMappedKeySurvives
        assertSame(entry, condition.entry)
        assertTrue(condition.origin.matches(transfer.source))
        assertEquals(f.base.occurrence.range, condition.useRange)
        assertFalse(
            transfer.source.conditions.admits(transfer.source, transfer.target, ValueTransferKind.WRAPPER_RETURN)
        )
        val repeated = transfer.source.withState(conditions = transfer.target.conditions).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(repeated, transfer.binding as ReviewedCallbackValueBinding),
        )
    }

    @Test
    fun `mutable key equality can replace an earlier mapped return`() {
        val firstKey = MutableKey("first")
        val secondKey = MutableKey("second")
        val input = linkedMapOf(firstKey to "first-value", secondKey to "second-value")
        val oracle = input.mapValues { entry ->
            if (entry.key === secondKey) secondKey.identity = firstKey.identity
            entry.value
        }
        assertEquals(listOf("second-value"), oracle.values.toList())
        assertFalse("first-value" in oracle.values)
        val transfer = ReviewedMapValuesPlacementFixture().transfer()
        assertTrue(transfer.target.conditions.facts.single() is ValueCondition.ReviewedMappedKeySurvives)
    }

    @Test
    fun `mapped origins keep structural equality hash sharing and bounded proof size`() {
        val f = ReviewedMapValuesPlacementFixture()
        val first = f.transfer()
        val second = f.transfer()
        val entry = (first.relation as ReviewedValueRelation.MappedValue).entry
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(entry, (second.relation as ReviewedValueRelation.MappedValue).entry)
        assertSame(entry, (first.target.valuePath.segments.first() as ValuePathSegment.MapEntryComponent).entry)
        assertTrue(entry.retainedBytes > first.binding.retainedBytes)
        assertTrue(first.retainedBytes > entry.retainedBytes)
        assertTrue(entry.retainedBytes < Long.MAX_VALUE)
        assertEquals(first.source.callStack, first.target.callStack)
        assertEquals(ReviewedCallbackInvocationEvidence.Unestablished, entry.binding.invocationEvidence)
    }
}

private fun ReviewedMapValuesPlacementFixture.transfer(): ReviewedValueTransfer =
    (read { returned(it) } as MapValuesPlacementOutcome.Observed).transfer

private data class MutableKey(var identity: String)
