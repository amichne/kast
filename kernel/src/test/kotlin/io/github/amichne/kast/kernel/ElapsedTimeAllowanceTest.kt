package io.github.amichne.kast.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElapsedTimeAllowanceTest {
    @Test
    fun `numeric settings cannot silently become indefinite`() {
        assertEquals(
            Refinement.Rejected(ElapsedTimeAllowanceFailure.INVALID_SETTING),
            ElapsedTimeAllowance.admitMillis(Long.MAX_VALUE),
        )
        val finite = ElapsedTimeAllowance.admitMillis(86_400_000) as Refinement.Refined
        assertTrue(finite.value.duration.isFinite())
        assertEquals(86_400_000L, finite.value.protocolMillis())
        assertEquals(
            Refinement.Refined(ElapsedTimeAllowance.Unlimited),
            ElapsedTimeAllowance.parseSetting("indefinite"),
        )
    }
}
