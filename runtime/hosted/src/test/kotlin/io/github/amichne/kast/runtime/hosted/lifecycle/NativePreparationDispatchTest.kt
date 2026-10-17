@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativePreparationDispatchTest {
    @Test
    fun `expired dispatch allowance rejects before a native effect and includes queued time`() = runTest {
        val admittedAt = currentTime * 1_000_000
        val observations = mutableListOf<NativePreparationObservation>()
        advanceTimeBy(1200)
        var effects = 0
        val result =
            prepareNativeWorkspace("request", 1000, admittedAt, observations::add, { currentTime * 1_000_000 }) {
                effects++
                error("expired dispatch must not start an effect")
            }
        assertEquals(0, effects)
        assertEquals(IdeLifecycleFailure.DEADLINE_EXCEEDED, (result as IdeLifecycleResult.Blocked).reason)
        val terminal = observations.last()
        assertEquals(1200, terminal.preparation.elapsedMillis)
        assertEquals(1000, terminal.preparation.limitMillis)
        assertEquals(0, terminal.preparation.remainingMillis)
        assertEquals(WorkspacePreparationDeadlineOwner.NATIVE_PREPARATION, terminal.preparation.limitingOwner)
    }
}
