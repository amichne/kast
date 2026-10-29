package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedWholeMapContentTest {
    @Test
    fun `whole map supplies two distinct content branches without map identity`() {
        val f = ReviewedMapEntryInputFixture()
        val source = f.receiver(ValueAccessPath.Whole)
        val branches =
            MapEntrySlot.entries.map { slot ->
                ReviewedValueTransfer.entryContentInput(source, f.binding, f.formal(), slot).flowRefined()
            }
        assertNotEquals(branches[0], branches[1])
        for ((slot, transfer) in MapEntrySlot.entries.zip(branches)) {
            val relation = transfer.relation as ReviewedValueRelation.EntryInput
            assertEquals(slot, relation.slot)
            assertEquals(ReviewedEntrySelection.CONTENT, relation.selection)
            assertEquals(
                listOf(ValuePathSegment.MapEntryComponent(relation.entry, slot)),
                transfer.target.valuePath.segments,
            )
            assertEquals(source.conditions, transfer.target.conditions)
            assertEquals(source.callStack, transfer.target.callStack)
        }
    }

    @Test
    fun `content APIs reject selected components and wrong receiver kinds`() {
        val f = ReviewedMapEntryInputFixture()
        for (source in listOf(f.receiver(), f.receiver(ValueAccessPath.Whole, ValueReceiverKind.DISPATCH))) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                ReviewedValueTransfer.entryContentInput(source, f.binding, f.formal(), MapEntrySlot.VALUE),
            )
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                ReviewedValueTransfer.mapPreservedKeyContent(source, f.binding),
            )
        }
    }

    @Test
    fun `content input rejects a foreign callback formal`() {
        val f = ReviewedMapEntryInputFixture()
        val original = f.formal()
        val foreignOwner = flowEndpoint("foreign", 400, 459, parameters = listOf("kotlin/Any"))
        val foreign =
            ValueFormalParameter.fromCompiler(
                    callable = foreignOwner,
                    position = original.position,
                    name = original.name,
                    range = original.range,
                    executionOwner = ValueExecutionOwner.declaration(foreignOwner),
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.entryContentInput(
                f.receiver(ValueAccessPath.Whole),
                f.binding,
                foreign,
                MapEntrySlot.VALUE,
            ),
        )
    }

    @Test
    fun `preserved key content remains conditional and cannot become a value slot`() {
        val f = ReviewedMapEntryInputFixture()
        val transfer =
            ReviewedValueTransfer.mapPreservedKeyContent(f.receiver(ValueAccessPath.Whole), f.binding).flowRefined()
        val relation = transfer.relation as ReviewedValueRelation.PreservedKey
        assertEquals(ReviewedEntrySelection.CONTENT, relation.selection)
        assertTrue(transfer.target.conditions.facts.last() is ValueCondition.ReviewedPreservedKeySurvives)
        assertTrue(
            ValueAccessPath.fromSegments(listOf(ValuePathSegment.MapEntryComponent(relation.entry, MapEntrySlot.VALUE)))
                is Refinement.Rejected
        )
        val selected =
            ReviewedValueTransfer.mapPreservedKey(f.receiver(f.selectedPath(MapEntrySlot.KEY)), f.binding).flowRefined()
        assertEquals(
            ReviewedEntrySelection.COMPONENT,
            (selected.relation as ReviewedValueRelation.PreservedKey).selection,
        )
        assertNotEquals(selected.relation, relation)
    }

    @Test
    fun `getter removal and mapped return preserve content through conditional origins`() {
        val f = ReviewedEntryReadFixture()
        val source = f.input.receiver(ValueAccessPath.Whole)
        val entered =
            ReviewedValueTransfer.entryContentInput(source, f.input.binding, f.input.formal(), MapEntrySlot.VALUE)
                .flowRefined()
        val getter = f.binding()
        val receiver =
            ValueSite.fromCompiler(
                    enclosing = entered.target.enclosing,
                    range = flowRange(445, 446),
                    role = ValueRole.Receiver(getter.invocation, ValueReceiverKind.DISPATCH),
                    executionOwner = entered.target.executionOwner,
                    valuePath = entered.target.valuePath,
                    conditions = entered.target.conditions,
                    callStack = entered.target.callStack,
                )
                .flowRefined()
        val projected = ReviewedValueTransfer.entryProject(receiver, getter).flowRefined()
        assertEquals(ValueAccessPath.Whole, projected.target.valuePath)
        assertEquals(
            ReviewedEntrySelection.CONTENT,
            (projected.relation as ReviewedValueRelation.EntryProjection).selection,
        )
        val returned =
            ValueSite.fromCompiler(
                    enclosing = projected.target.enclosing,
                    range = projected.target.range,
                    role = ValueRole.Return,
                    executionOwner = projected.target.executionOwner,
                    valuePath = projected.target.valuePath,
                    conditions = projected.target.conditions,
                    callStack = projected.target.callStack,
                )
                .flowRefined()
        val mapped = ReviewedValueTransfer.callbackResult(returned, f.input.binding).flowRefined()
        assertEquals(ReviewedEntrySelection.CONTENT, (mapped.relation as ReviewedValueRelation.MappedValue).selection)
        val next =
            f.input.receiver(mapped.target.valuePath).withState(conditions = mapped.target.conditions).flowRefined()
        val again = ReviewedValueTransfer.callbackInput(next, f.input.binding, f.input.formal()).flowRefined()
        assertEquals(ReviewedEntrySelection.CONTENT, (again.relation as ReviewedValueRelation.EntryInput).selection)
    }

    @Test
    fun `selected exact entry input keeps component classification`() {
        val f = ReviewedMapEntryInputFixture()
        val source = f.receiver()
        val first = ReviewedValueTransfer.callbackInput(source, f.binding, f.formal()).flowRefined()
        val repeated = ReviewedValueTransfer.callbackInput(source, f.binding, f.formal()).flowRefined()
        assertEquals(ReviewedEntrySelection.COMPONENT, (first.relation as ReviewedValueRelation.EntryInput).selection)
        assertEquals(first, repeated)
        assertEquals(first.hashCode(), repeated.hashCode())
        assertTrue(first.retainedBytes > 0)
    }
}
