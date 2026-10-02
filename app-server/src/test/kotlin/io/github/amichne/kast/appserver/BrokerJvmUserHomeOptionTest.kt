package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class BrokerJvmUserHomeOptionTest {
    @Test
    fun `admitted home keeps spaces quotes and backslashes in one JVM option`() {
        val option =
            when (val parsed = BrokerJvmUserHomeOption.from(Path.of("/private/home with \"quote\" and \\slash"))) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> fail("valid home rejected: ${parsed.failure}")
            }
        assertEquals("-Duser.home=\"/private/home with \\\"quote\\\" and \\\\slash\"", option.value)
    }

    @Test
    fun `unproven home cannot enter the child JVM option`() {
        for (home in listOf("/private/home\nextra", "/private/home\rextra", "relative/home", "/private/../home")) {
            assertEquals(
                Refinement.Rejected(BrokerJvmUserHomeOptionFailure.INVALID_VALUE),
                BrokerJvmUserHomeOption.from(Path.of(home)),
            )
        }
    }
}
