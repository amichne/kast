package io.github.amichne.kast.runtime.hosted

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HostedSmartModeWaitCodecTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `each finite wait outcome retains its required fields in encoded output`() {
        val cases =
            listOf(
                HostedSmartModeWaitOutcome.STARTED to "STARTED",
                HostedSmartModeWaitOutcome.READY to "READY",
                HostedSmartModeWaitOutcome.TIMED_OUT to "TIMED_OUT",
                HostedSmartModeWaitOutcome.DISPOSED to "DISPOSED",
                HostedSmartModeWaitOutcome.CANCELLED to "CANCELLED",
                HostedSmartModeWaitOutcome.PLATFORM_RUNTIME_FAILURE to "PLATFORM_RUNTIME_FAILURE",
                HostedSmartModeWaitOutcome.PLATFORM_LINKAGE_FAILURE to "PLATFORM_LINKAGE_FAILURE",
            )
        assertEquals(HostedSmartModeWaitOutcome.entries.toSet(), cases.map { it.first }.toSet())
        for ((outcome, expected) in cases) {
            val encoded = json.encodeToString(HostedSmartModeWaitObservation(outcome, 0, 0, 15_000))
            val fields = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(setOf("outcome", "elapsedNanos", "statusPolls", "limitMillis"), fields.keys)
            assertEquals(expected, fields.getValue("outcome").jsonPrimitive.content)
            assertEquals("0", fields.getValue("elapsedNanos").jsonPrimitive.content)
            assertEquals("0", fields.getValue("statusPolls").jsonPrimitive.content)
            assertEquals("15000", fields.getValue("limitMillis").jsonPrimitive.content)
        }
    }

    @Test
    fun `unknown wait outcome rejects rather than becoming ready`() {
        val encoded =
            json.encodeToString(HostedSmartModeWaitObservation(HostedSmartModeWaitOutcome.READY, 0, 0, 15_000))
        val incompatible = encoded.replace("READY", "UNRECOGNIZED")
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<HostedSmartModeWaitObservation>(incompatible)
        }
    }
}
