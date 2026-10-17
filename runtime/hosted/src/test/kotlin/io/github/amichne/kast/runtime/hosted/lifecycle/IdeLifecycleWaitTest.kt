@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.runtime.hosted.lifecycle

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeLifecycleWaitTest {
    @Test
    fun `mandatory preparation can settle after two minutes within its readiness allowance`() = runTest {
        assertTrue(awaitLifecycleCondition { currentTime >= 173_200L })
        assertEquals(173_200L, currentTime)
    }
}
