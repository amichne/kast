package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.provider.KastProviderQualification
import io.github.amichne.kast.appserver.provider.KastQualificationFailure
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NativeReadTransportTest {
    @Test
    fun `EOF is distinct from an incomplete request`() {
        assertNull(boundedReadRequest(ByteArrayInputStream(byteArrayOf())))
        val failure =
            assertThrows<NativeRejected> {
                boundedReadRequest(ByteArrayInputStream("{}".toByteArray()))
            }
        assertEquals(NativeFailure.INPUT_REJECTED, failure.failure)
    }

    @Test
    fun `one frame leaves the next frame in the pipe`() {
        val input = ByteArrayInputStream("{\"sequence\":1}\n{\"sequence\":2}\n".toByteArray())
        assertEquals(JsonPrimitive(1), boundedReadRequest(input)?.get("sequence"))
        assertEquals(JsonPrimitive(2), boundedReadRequest(input)?.get("sequence"))
        assertNull(boundedReadRequest(input))
    }

    @Test
    fun `a source sized request cannot bypass the frame bound`() {
        val input = ByteArrayInputStream(ByteArray(2 * 1024 * 1024 + 1) { ' '.code.toByte() })
        val failure = assertThrows<NativeRejected> { boundedReadRequest(input) }
        assertEquals(NativeFailure.INPUT_REJECTED, failure.failure)
    }

    @Test
    fun `qualification rejection retains every exact cause and emits bounded evidence`() {
        for (cause in KastQualificationFailure.entries) {
            val observations = mutableListOf<NativeProviderQualificationObservation>()
            val rejected =
                assertThrows<NativeProviderQualificationRejected> {
                    KastProviderQualification.Rejected(cause).nativeQualified(observations::add)
                }
            assertEquals(cause, rejected.failure)
            assertEquals(1, observations.size)
            val document = Json.parseToJsonElement(observations.single().encodeObservation()).jsonObject
            assertEquals(setOf("outcome", "cause", "stage"), document.keys)
            assertEquals("rejected", document.getValue("outcome").jsonPrimitive.content)
            assertEquals("PROVIDER_QUALIFICATION", document.getValue("stage").jsonPrimitive.content)
            assertEquals(cause.name, document.getValue("cause").jsonPrimitive.content)
        }
    }

    @Test
    fun `admitted qualification signal has no failure or provider payload`() {
        val document =
            Json.parseToJsonElement(NativeProviderQualificationObservation.Admitted().encodeObservation()).jsonObject
        assertEquals(setOf("outcome", "stage"), document.keys)
        assertEquals("admitted", document.getValue("outcome").jsonPrimitive.content)
        assertEquals("PROVIDER_QUALIFICATION", document.getValue("stage").jsonPrimitive.content)
    }
}
