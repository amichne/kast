package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.io.ByteArrayInputStream
import kotlinx.serialization.json.JsonPrimitive
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
}
