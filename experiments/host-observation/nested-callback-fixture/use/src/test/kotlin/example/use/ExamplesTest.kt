package example.use

import example.contract.Decoder
import example.flow.Attempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ExamplesTest {
    @Test
    fun directReturnsDecodedValue() {
        val decoder = object : Decoder {
            override fun decode(value: String): String = "decoded:$value"
        }

        assertEquals("decoded:direct", direct(decoder, "direct"))
    }

    @Test
    fun nestedReturnsCapturedValue() {
        val decoder = object : Decoder {
            override fun decode(value: String): String = "decoded:$value"
        }

        assertEquals(Attempt.Value("decoded:nested"), nested(decoder, "nested"))
    }

    @Test
    fun nestedPreservesCapturedFailure() {
        val cause = IllegalArgumentException("cannot decode")
        val decoder = object : Decoder {
            override fun decode(value: String): String = throw cause
        }

        val result = assertIs<Attempt.Failure>(nested(decoder, "invalid"))

        assertSame(cause, result.cause)
    }

    @Test
    fun deferredDoesNotDecodeWhenConstructed() {
        val invocations = mutableListOf<String>()
        val decoder = object : Decoder {
            override fun decode(value: String): String {
                invocations.add(value)
                return "decoded:$value"
            }
        }

        deferred(decoder, "deferred")

        assertEquals(emptyList(), invocations)
    }
}
