package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeHostMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplicationProjectObservationTest {
    @Test
    fun `desktop inspection discovers borrowed projects`() {
        var observations = 0
        observeApplicationProjects(IdeHostMode.DESKTOP) { observations += 1 }
        assertEquals(1, observations)
    }

    @Test
    fun `managed inspection leaves registration with the native opener`() {
        var observations = 0
        observeApplicationProjects(IdeHostMode.MANAGED_HEADLESS) { observations += 1 }
        assertEquals(0, observations)
    }
}
