package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ReadDeadlineOrderingTest {
    @Test
    fun `outer transport deadlines require positive slack beyond the inner operation`() {
        for ((inner, outer) in
            listOf(
                ReadLimitParameter.HOST_CONNECTION_MILLIS to ReadLimitParameter.CLIENT_EXCHANGE_MILLIS,
                ReadLimitParameter.CLIENT_EXCHANGE_MILLIS to ReadLimitParameter.PROVIDER_INVOCATION_MILLIS,
                ReadLimitParameter.CLIENT_EXCHANGE_MILLIS to ReadLimitParameter.PROVIDER_GRAPH_INVOCATION_MILLIS,
            )) {
            val innerMillis = ReadLimits.Default[inner].value
            val equal = ReadLimits.resolve(mapOf(outer.environmentKey to innerMillis.toString()))
            val rejected = assertInstanceOf(Refinement.Rejected::class.java, equal)
            assertEquals(ReadLimitFailure.InconsistentBounds(inner, outer), rejected.failure)
            assertInstanceOf(
                Refinement.Refined::class.java,
                ReadLimits.resolve(mapOf(outer.environmentKey to (innerMillis + 1).toString())),
            )
        }
    }

    @Test
    fun `semantic configuration equality remains admissible because hosted admission reserves completion time`() {
        assertInstanceOf(
            Refinement.Refined::class.java,
            ReadLimits.resolve(
                mapOf(
                    ReadLimitParameter.SEMANTIC_MILLIS.environmentKey to
                        ReadLimits.Default[ReadLimitParameter.HOST_QUERY_MILLIS].value.toString()
                )
            ),
        )
    }
}
