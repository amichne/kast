package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedCollectionPositionTest {
    @Test
    fun `same typed tuple components cannot swap through property projection`() {
        val f = ReviewedPositionFixture()
        val first = f.construct(PairSlot.FIRST)
        val second = f.construct(PairSlot.SECOND)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY),
            ReviewedValueTransfer.tupleProject(
                f.getterSource(first.target.valuePath, PairSlot.SECOND),
                f.getter(PairSlot.SECOND),
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY),
            ReviewedValueTransfer.tupleProject(f.getterSource(second.target.valuePath), f.getter(PairSlot.FIRST)),
        )
        val projected =
            ReviewedValueTransfer.tupleProject(f.getterSource(first.target.valuePath), f.getter(PairSlot.FIRST))
                .flowRefined()
        assertEquals(f.base.fieldPath, projected.target.valuePath)
        assertTrue(projected.relation is ReviewedValueRelation.TupleProjection)
    }

    @Test
    fun `tuple component proof cannot infer whole container identity`() {
        val f = ReviewedPositionFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY),
            ReviewedValueTransfer.tupleProject(f.getterSource(ValueAccessPath.Whole), f.getter(PairSlot.FIRST)),
        )
    }

    @Test
    fun `association preserves key value positions under a unique key premise`() {
        val f = ReviewedPositionFixture()
        val key = f.associate(PairSlot.FIRST)
        val value = f.associate(PairSlot.SECOND)
        val keySegment = key.target.valuePath.segments.first() as ValuePathSegment.MapEntryComponent
        val valueSegment = value.target.valuePath.segments.first() as ValuePathSegment.MapEntryComponent
        assertEquals(MapEntrySlot.KEY, keySegment.slot)
        assertEquals(MapEntrySlot.VALUE, valueSegment.slot)
        assertEquals(keySegment.entry, valueSegment.entry)
        assertEquals(f.base.fieldPath.segments, key.target.valuePath.segments.drop(1))
        assertEquals(
            keySegment.entry,
            (key.target.conditions.facts.last() as ValueCondition.ReviewedEntryKeyUnique).entry,
        )
        assertFalse(key.target.conditions.facts.isEmpty())
        assertNotEquals(key.relation, value.relation)
    }

    @Test
    fun `association rejects a formal shortcut and whole pair inference`() {
        val f = ReviewedPositionFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.associatedReturn(f.returned(ValueAccessPath.Whole), f.callback),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(f.returned(f.construct(PairSlot.FIRST).target.valuePath), f.callback),
        )
    }

    @Test
    fun `another tuple supplier cannot share map entry correlation`() {
        val f = ReviewedPositionFixture()
        val first = f.associate(PairSlot.FIRST)
        val other = f.associate(PairSlot.SECOND, start = 441)
        val firstEntry = (first.target.valuePath.segments.first() as ValuePathSegment.MapEntryComponent).entry
        val otherEntry = (other.target.valuePath.segments.first() as ValuePathSegment.MapEntryComponent).entry
        assertNotEquals(firstEntry, otherEntry)
        assertNotEquals(
            (firstEntry as ReviewedMapEntryCorrelation).tupleOrigin,
            (otherEntry as ReviewedMapEntryCorrelation).tupleOrigin,
        )
    }

    @Test
    fun `equal but distinct keys keep the earlier key and later value independently`() {
        val firstKey = OracleKey("equal")
        val secondKey = OracleKey("equal")
        val firstValue = OracleValue("first")
        val secondValue = OracleValue("second")
        val actual = linkedMapOf(firstKey to firstValue).apply { put(secondKey, secondValue) }
        assertEquals(1, actual.size)
        assertSame(firstKey, actual.keys.single())
        assertSame(secondValue, actual.values.single())
        val f = ReviewedPositionFixture()
        val transfer = f.associate(PairSlot.SECOND)
        assertTrue(transfer.target.conditions.facts.single() is ValueCondition.ReviewedEntryKeyUnique)
    }

    @Test
    fun `distinct key oracle preserves both values but unknown input keeps the premise`() {
        val expected = setOf(OracleValue("first"), OracleValue("second"))
        val actual = linkedMapOf(OracleKey("a") to OracleValue("first"), OracleKey("b") to OracleValue("second"))
        assertEquals(expected, actual.values.toSet())
        val f = ReviewedPositionFixture()
        val transfer = f.associate(PairSlot.FIRST)
        assertTrue(transfer.target.conditions.facts.single() is ValueCondition.ReviewedEntryKeyUnique)
    }

    @Test
    fun `sorted selector result cannot become an output element`() {
        val f = ReviewedPositionFixture(ReviewedLibraryOperation.ITERABLE_SORTED_BY)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(f.returned(f.base.fieldPath), f.callback),
        )
        val source = f.collectionReceiver()
        val transfer = ReviewedValueTransfer.sortedResult(source, f.callback).flowRefined()
        assertEquals(source.valuePath, transfer.target.valuePath)
        assertEquals(ReviewedValueRelation.ReorderedElements(ReviewedCollectionSelection.ELEMENT), transfer.relation)
        assertTrue(transfer.target.conditions.facts.single() is ValueCondition.ReviewedCollectionNormalResult)
        assertEquals(ReviewedCallbackInvocationEvidence.Unestablished, f.callback.invocationEvidence)
    }

    @Test
    fun `Pair constructor keeps each declared argument position`() {
        val f = ReviewedPositionFixture()
        val binding = f.ordinary(ReviewedLibraryOperation.PAIR_CONSTRUCTOR)
        for ((index, slot) in listOf(0 to PairSlot.FIRST, 1 to PairSlot.SECOND)) {
            val source =
                ValueSite.fromCompiler(
                        enclosing = f.base.enclosing,
                        range = flowRange(441, 442),
                        role = ValueRole.Argument(binding.invocation, f.base.position(index)),
                        executionOwner = binding.invocation.executionOwner,
                        valuePath = f.base.fieldPath,
                    )
                    .flowRefined()
            val transfer = ReviewedValueTransfer.tupleConstruct(source, binding).flowRefined()
            assertEquals(slot, (transfer.target.valuePath.segments.first() as ValuePathSegment.PairComponent).slot)
            assertEquals(source.valuePath.segments, transfer.target.valuePath.segments.drop(1))
        }
    }

    @Test
    fun `ordinary call cannot borrow another selected operation or archive`() {
        val f = ReviewedPositionFixture()
        val call = f.ordinary(ReviewedLibraryOperation.TO)
        val other =
            ReviewedOrdinaryLibraryCallable.admit(f.ordinaryTarget(ReviewedLibraryOperation.PAIR_CONSTRUCTOR))
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedCallValueBinding.admit(call.invocation, other, call.artifact),
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
            ReviewedLibraryArtifact.admit(call.selected, f.base.digest("a".repeat(64)), call.artifact.sourceArtifact),
        )
    }

    private data class OracleKey(val value: String)

    private data class OracleValue(val value: String)
}
