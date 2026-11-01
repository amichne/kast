package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedEntryContentTest {
    @Test
    fun `whole map cannot claim exact component input`() {
        assertFalse(wholeInput(ImpactReviewedEntrySelectionDocument.COMPONENT).admitsEvidence())
    }

    @Test
    fun `entry selection must be explicit and finite in encoded evidence`() {
        val relation =
            wholeInput(ImpactReviewedEntrySelectionDocument.CONTENT).binding.relation
                as ImpactReviewedValueRelationDocument.EntryInput
        val encoded = Json.encodeToString(ImpactReviewedInputEntryOriginDocument.serializer(), relation.entry)
        assertEquals("CONTENT", Json.parseToJsonElement(encoded).jsonObject.getValue("selection").jsonPrimitive.content)
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(
                ImpactReviewedInputEntryOriginDocument.serializer(),
                encoded.replace(",\"selection\":\"CONTENT\"", ""),
            )
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(
                ImpactReviewedInputEntryOriginDocument.serializer(),
                encoded.replace("CONTENT", "EXACT_OBJECT"),
            )
        }
    }

    @Test
    fun `whole map content enters a selected slot as derived evidence`() {
        val transfer = wholeInput(ImpactReviewedEntrySelectionDocument.CONTENT)
        assertTrue(transfer.admitsEvidence())
        assertEquals(ImpactRelationshipDocument.DERIVED, transfer.binding.relation.relationship())
        assertTrue(transfer.source.valuePath.values.isEmpty())
        assertEquals(1, transfer.target.valuePath.values.size)
        assertEquals(transfer.source.conditions, transfer.target.conditions)
    }

    private fun wholeInput(selection: ImpactReviewedEntrySelectionDocument): ImpactReviewedValueTransferDocument {
        val fixture = ImpactReviewedEntryFixture()
        val entry = fixture.entry.copy(selection = selection)
        return fixture.input.copy(
            source = fixture.input.source.copy(valuePath = fixture.base.bounded(emptyList())),
            target =
                fixture.input.target.copy(
                    valuePath =
                        fixture.base.bounded(
                            listOf(
                                ImpactValuePathSegmentDocument.MapEntryComponent(
                                    entry,
                                    ImpactMapEntrySlotDocument.VALUE,
                                )
                            )
                        )
                ),
            binding =
                fixture.input.binding.copy(
                    relation = ImpactReviewedValueRelationDocument.EntryInput(entry, ImpactMapEntrySlotDocument.VALUE)
                ),
        )
    }
}
