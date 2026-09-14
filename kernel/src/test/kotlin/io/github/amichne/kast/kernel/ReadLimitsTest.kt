package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReadLimitsTest {
    @Test
    fun `defaults are complete and overrides preserve parameter identity and provenance`() {
        val limits =
            ReadLimits.resolve(
                mapOf(ReadLimitParameter.MODEL_MODULES.environmentKey to "768"),
                mapOf(ReadLimitParameter.MODEL_MODULES.propertyKey to "1024"),
            ) as Refinement.Refined
        assertEquals(ReadLimitParameter.entries.size, limits.value.values.size)
        val modules = limits.value[ReadLimitParameter.MODEL_MODULES]
        assertEquals(ReadLimitParameter.MODEL_MODULES, modules.parameter)
        assertEquals(1024, modules.value)
        assertEquals(ReadLimitSource.JVM_PROPERTY, modules.source)
        assertEquals(100_000, limits.value[ReadLimitParameter.SEMANTIC_WORK].value)
    }

    @Test
    fun `unknown malformed out of range and inconsistent bounds never fall back`() {
        for (value in listOf("", "0", "-1", "+2", "1.5", "999999999999999999999999999999999")) {
            assertTrue(
                ReadLimits.resolve(mapOf(ReadLimitParameter.MODEL_MODULES.environmentKey to value))
                    is Refinement.Rejected
            )
        }
        assertTrue(ReadLimits.resolve(mapOf("KAST_READ_UNKNOWN" to "secret")) is Refinement.Rejected)
        assertTrue(
            ReadLimits.resolve(mapOf(ReadLimitParameter.HOST_QUERY_MILLIS.environmentKey to "6000"))
                is Refinement.Rejected
        )
        assertTrue(
            ReadLimits.resolve(
                mapOf(ReadLimitParameter.MODEL_MODULES.environmentKey to "secret"),
                mapOf(ReadLimitParameter.MODEL_MODULES.propertyKey to "1024"),
            ) is Refinement.Rejected
        )
    }

    @Test
    fun `a query checkpoint cannot exceed its continuation store byte authority`() {
        val rejected =
            ReadLimits.resolve(mapOf(ReadLimitParameter.QUERY_CHECKPOINT_BYTES.environmentKey to "33554433"))
                as Refinement.Rejected
        assertEquals(
            ReadLimitFailure.InconsistentBounds(
                ReadLimitParameter.QUERY_CHECKPOINT_BYTES,
                ReadLimitParameter.QUERY_CONTINUATION_BYTES,
            ),
            rejected.failure,
        )
        val admitted =
            ReadLimits.resolve(mapOf(ReadLimitParameter.QUERY_CONTINUATION_ENTRIES.environmentKey to "2"))
                as Refinement.Refined
        assertEquals(2, admitted.value[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value)
    }

    @Test
    fun `rejections reveal the parameter and finite condition without supplied data`() {
        val result =
            ReadLimits.resolve(mapOf(ReadLimitParameter.MODEL_MODULES.environmentKey to "private-value"))
                as Refinement.Rejected
        val failure = result.failure as ReadLimitFailure.InvalidValue
        assertEquals(ReadLimitParameter.MODEL_MODULES, failure.parameter)
        assertEquals(ReadLimitValueFailure.INVALID_NUMBER, failure.kind)
        assertFalse(result.failure.toString().contains("private-value"))
    }
}
