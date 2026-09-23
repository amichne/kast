package io.github.amichne.kast.appserver.acceptance.hostedchange

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeReconnectObservationTest {
    @Test
    fun `controller handshake keeps its exact typed response shape`() {
        val response = Json.parseToJsonElement(NativeControllerProtocol.initializeResponse()).jsonObject
        assertEquals(setOf("id", "result"), response.keys)
        assertEquals(0, response.getValue("id").jsonPrimitive.int)
        assertEquals(emptySet<String>(), response.getValue("result").jsonObject.keys)
        val notification = Json.parseToJsonElement(NativeControllerProtocol.initializedNotification()).jsonObject
        assertEquals(setOf("method"), notification.keys)
        assertEquals("initialized", notification.getValue("method").jsonPrimitive.content)
    }

    @Test
    fun `reconnect effect retains success and rejection stages without payloads`() {
        val seen = mutableListOf<NativeReconnectObservation>()
        assertEquals(
            7,
            runBlocking {
                observeReconnect(NativeReconnectStage.ATTACH, { seen += it }) { 7 }
            },
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                observeReconnect(NativeReconnectStage.THREAD_START, { seen += it }) {
                    throw IllegalStateException("fixture failure")
                }
            }
        }
        assertEquals(
            listOf(
                NativeReconnectObservation(NativeReconnectStage.ATTACH, NativeReconnectOutcome.STARTED),
                NativeReconnectObservation(NativeReconnectStage.ATTACH, NativeReconnectOutcome.COMPLETE),
                NativeReconnectObservation(NativeReconnectStage.THREAD_START, NativeReconnectOutcome.STARTED),
                NativeReconnectObservation(NativeReconnectStage.THREAD_START, NativeReconnectOutcome.REJECTED),
            ),
            seen,
        )
        for (observation in seen) {
            val document = Json.parseToJsonElement(observation.encode()).jsonObject
            assertEquals(setOf("event", "stage", "outcome"), document.keys)
            assertEquals("kast_native_reconnect_stage", document.getValue("event").jsonPrimitive.content)
        }
    }
}
