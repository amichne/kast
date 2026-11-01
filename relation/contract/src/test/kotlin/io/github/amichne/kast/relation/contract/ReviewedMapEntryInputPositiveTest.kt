package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedMapEntryInputPositiveTest {
    @Test
    fun `selected map value reaches the actual entry formal without exchanging key`() {
        assertSelected(MapEntrySlot.VALUE)
    }

    @Test
    fun `selected map key reaches the actual entry formal without exchanging value`() {
        assertSelected(MapEntrySlot.KEY)
    }

    private fun assertSelected(slot: MapEntrySlot) {
        val key = String(charArrayOf('k'))
        val value = String(charArrayOf('v'))
        val entry = mapOf(key to value).entries.single()
        assertNotEquals(entry.key, entry.value)
        assertSame(key, entry.key)
        assertSame(value, entry.value)
        val f = ReviewedMapEntryInputFixture()
        val selected = f.selectedValue(slot)
        val path = selected.valuePath
        val source = f.receiver(path).withState(conditions = selected.conditions).flowRefined()
        val formal = f.formal()
        val result = ReviewedValueTransfer.callbackInput(source, f.binding, formal)
        assertTrue(result is Refinement.Refined, "Missing selected entry input: $result")
        val transfer = (result as Refinement.Refined).value
        assertSame(source, transfer.source)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Parameter(formal)), transfer.target.role)
        assertEquals(formal.executionOwner, transfer.target.executionOwner)
        assertEquals(formal.range, transfer.target.range)
        val component = transfer.target.valuePath.segments.first() as ValuePathSegment.MapEntryComponent
        assertEquals(slot, component.slot)
        assertEquals(path.segments.drop(1), transfer.target.valuePath.segments.drop(1))
        assertEquals(source.conditions, transfer.target.conditions)
        assertEquals(source.callStack, transfer.target.callStack)
    }
}
