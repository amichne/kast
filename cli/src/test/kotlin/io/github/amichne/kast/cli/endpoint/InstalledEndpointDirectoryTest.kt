package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class InstalledEndpointDirectoryTest {
    @Test
    fun `installed CLI consumes the hosted plugin endpoint directory`() {
        when (val refined = installedIdeEndpointSocketDirectory()) {
            is Refinement.Refined -> assertEquals("/tmp", refined.value.value)
            is Refinement.Rejected -> fail("stable endpoint directory rejected: ${refined.failure}")
        }
    }
}
