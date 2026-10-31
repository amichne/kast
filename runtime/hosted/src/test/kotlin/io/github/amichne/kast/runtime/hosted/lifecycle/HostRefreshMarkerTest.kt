package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostRefreshMarkerTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `unknown marker metadata rejects before a refresh signal`() {
        val invalid =
            listOf(
                HostRefreshMarkerSnapshot(-1, 200, 10, 10),
                HostRefreshMarkerSnapshot(100, -1, 10, 10),
                HostRefreshMarkerSnapshot(100, 200, -1, 10),
                HostRefreshMarkerSnapshot(100, 200, 10, -1),
            )
        for (snapshot in invalid) {
            assertEquals(
                Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE),
                HostRefreshMarker.admit(snapshot),
            )
            assertEquals(
                Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_ROOT_UNAVAILABLE),
                admitHostRefreshSignal(snapshot),
            )
        }
    }

    @Test
    fun `zero cache metadata remains valid and preserves its admitted facts`() {
        val snapshot = HostRefreshMarkerSnapshot(0, 1, 0, 0)
        val admitted = admitHostRefreshSignal(snapshot)
        assertTrue(admitted is Refinement.Refined)
        assertEquals(snapshot, (admitted as Refinement.Refined).value.snapshot)
        assertTrue(HostRefreshMarker.admit(HostRefreshMarkerSnapshot(0, 0, 0, 0)) is Refinement.Refined)
        assertEquals(
            Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_MARKER_UNSIGNALED),
            admitHostRefreshSignal(HostRefreshMarkerSnapshot(0, 0, 0, 0)),
        )
    }

    @Test
    fun `unchanged physical marker rejects even when its length changes`() {
        assertEquals(
            Refinement.Rejected(IdeLifecycleFailure.HOST_REFRESH_MARKER_UNSIGNALED),
            admitHostRefreshSignal(HostRefreshMarkerSnapshot(100, 100, 10, 11)),
        )
        val snapshot = HostRefreshMarkerSnapshot(100, 101, 10, 10)
        val admitted = admitHostRefreshSignal(snapshot)
        assertTrue(admitted is Refinement.Refined)
        assertEquals(snapshot, (admitted as Refinement.Refined).value.snapshot)
    }

    @Test
    fun `marker diagnostics encode bounded facts without file paths`() {
        val marker = HostRefreshMarkerSnapshot(100, 200, 10, 10)
        val outcomes =
            listOf(
                HostRefreshOutcome.MarkerPrepared(marker) to "MARKER_PREPARED",
                HostRefreshOutcome.MarkerCompared(marker) to "MARKER_COMPARED",
                HostRefreshOutcome.ContentEvents(1, 1, marker) to "CONTENT_EVENTS",
            )
        for ((outcome, type) in outcomes) {
            val encoded = json.parseToJsonElement(json.encodeToString<HostRefreshOutcome>(outcome)).jsonObject
            assertEquals(type, encoded.getValue("type").jsonPrimitive.content)
            assertEquals(
                if (outcome is HostRefreshOutcome.ContentEvents) setOf("type", "before", "after", "marker")
                else setOf("type", "marker"),
                encoded.keys,
            )
            val value = encoded.getValue("marker").jsonObject
            assertEquals(
                setOf("cachedTimestampMillis", "physicalTimestampMillis", "cachedLength", "physicalLength"),
                value.keys,
            )
            assertEquals("100", value.getValue("cachedTimestampMillis").jsonPrimitive.content)
            assertEquals("200", value.getValue("physicalTimestampMillis").jsonPrimitive.content)
            assertEquals("10", value.getValue("cachedLength").jsonPrimitive.content)
            assertEquals("10", value.getValue("physicalLength").jsonPrimitive.content)
            if (outcome is HostRefreshOutcome.ContentEvents) {
                assertEquals("1", encoded.getValue("before").jsonPrimitive.content)
                assertEquals("1", encoded.getValue("after").jsonPrimitive.content)
            }
        }
    }
}
