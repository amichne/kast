package io.github.amichne.kast.fixtureprobe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProbeUndoDocumentObservationTest {
    private val before = ProbeDigest.observe("before".toByteArray())
    private val other = ProbeDigest.observe("other".toByteArray())

    @Test
    fun matchingUndoImageRecordsExactEvidenceBeforeReturningSuccess() {
        val observations = mutableListOf<ProbeUndoDocumentObservation>()
        val result = verifyProbeUndoDocument(before, before, observations::add)
        assertEquals(ProbeResult.Accepted(Unit), result)
        assertEquals(listOf(ProbeUndoDocumentObservation.Matched(before.value, before.value)), observations)
        val encoded =
            Json.encodeToJsonElement(ProbeUndoDocumentObservation.serializer(), observations.single()).jsonObject
        assertEquals(setOf("type", "expectedDocumentSha256", "observedDocumentSha256"), encoded.keys)
        assertEquals("matched", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(before.value, encoded.getValue("expectedDocumentSha256").jsonPrimitive.content)
        assertEquals(before.value, encoded.getValue("observedDocumentSha256").jsonPrimitive.content)
    }

    @Test
    fun mismatchedUndoImageRecordsExactEvidenceBeforeReturningOriginalFailure() {
        val observations = mutableListOf<ProbeUndoDocumentObservation>()
        val result = verifyProbeUndoDocument(before, other, observations::add)
        assertEquals(ProbeResult.Rejected(ProbeFailure.UNDO_IMAGE_MISMATCH), result)
        assertEquals(listOf(ProbeUndoDocumentObservation.Mismatched(before.value, other.value)), observations)
        val encoded =
            Json.encodeToJsonElement(ProbeUndoDocumentObservation.serializer(), observations.single()).jsonObject
        assertEquals(setOf("type", "expectedDocumentSha256", "observedDocumentSha256"), encoded.keys)
        assertEquals("mismatched", encoded.getValue("type").jsonPrimitive.content)
        assertEquals(before.value, encoded.getValue("expectedDocumentSha256").jsonPrimitive.content)
        assertEquals(other.value, encoded.getValue("observedDocumentSha256").jsonPrimitive.content)
    }
}
