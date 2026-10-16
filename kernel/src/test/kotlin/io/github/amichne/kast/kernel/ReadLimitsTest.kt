package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReadLimitsTest {
    @Test
    fun `execution defaults provide headroom without larger responses or retention stores`() {
        val limits = (ReadLimits.resolve() as Refinement.Refined).value
        val expected =
            mapOf(
                ReadLimitParameter.SEMANTIC_MILLIS to 15_000,
                ReadLimitParameter.SEMANTIC_WORK to 2_000_000,
                ReadLimitParameter.SEMANTIC_RESULTS to 128,
                ReadLimitParameter.SEMANTIC_RETURNED_BYTES to 49_152,
                ReadLimitParameter.SOURCE_ENTITY_WORK to 10_000,
                ReadLimitParameter.SOURCE_ENTITIES to 128,
                ReadLimitParameter.SOURCE_RETURNED_BYTES to 49_152,
                ReadLimitParameter.QUERY_CHECKPOINT_BYTES to 33_554_432,
                ReadLimitParameter.QUERY_CONTINUATION_BYTES to 134_217_728,
            )
        for ((parameter, value) in expected) {
            assertEquals(value, limits[parameter].value, parameter.name)
            assertEquals(ReadLimitSource.DEFAULT, limits[parameter].source, parameter.name)
        }
    }

    @Test
    fun `shorter host deadlines need an explicit compatible semantic default`() {
        assertEquals(
            Refinement.Rejected(
                ReadLimitFailure.InconsistentBounds(
                    ReadLimitParameter.SEMANTIC_MILLIS,
                    ReadLimitParameter.HOST_QUERY_MILLIS,
                )
            ),
            ReadLimits.resolve(mapOf("KAST_READ_HOST_QUERY_MILLIS" to "4000")),
        )
        val limits =
            (ReadLimits.resolve(mapOf("KAST_READ_HOST_QUERY_MILLIS" to "4000", "KAST_READ_SEMANTIC_MILLIS" to "4000"))
                    as Refinement.Refined)
                .value
        assertEquals(4000, limits[ReadLimitParameter.SEMANTIC_MILLIS].value)
        assertEquals(ReadLimitSource.ENVIRONMENT, limits[ReadLimitParameter.SEMANTIC_MILLIS].source)
    }

    @Test
    fun `larger semantic byte defaults need no transport overrides`() {
        val bytes = 128 * 1_024 * 1_024
        val limits =
            ReadLimits.resolve(
                mapOf(
                    ReadLimitParameter.SEMANTIC_RETURNED_BYTES.environmentKey to bytes.toString(),
                    ReadLimitParameter.SOURCE_RETURNED_BYTES.environmentKey to bytes.toString(),
                )
            )
        assertTrue(limits is Refinement.Refined)
    }

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
        assertEquals(2_000_000, limits.value[ReadLimitParameter.SEMANTIC_WORK].value)
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
            ReadLimits.resolve(mapOf(ReadLimitParameter.HOST_QUERY_MILLIS.environmentKey to "32000"))
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
            ReadLimits.resolve(
                mapOf(
                    ReadLimitParameter.QUERY_CHECKPOINT_BYTES.environmentKey to "8193",
                    ReadLimitParameter.QUERY_CONTINUATION_BYTES.environmentKey to "8192",
                )
            ) as Refinement.Rejected
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
