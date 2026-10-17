@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdeLifecycleWaitTest {
    @Test
    fun `terminal propagation preserves the narrower refresh waiter attribution`() = runTest {
        val observations = mutableListOf<NativePreparationObservation>()
        val operation =
            (NativeWorkspacePreparation.admit("request", 1000, { currentTime * 1_000_000 }, observations::add)
                    as Refinement.Refined)
                .value
        val refresh =
            io.github.amichne.kast.protocol.contract.WorkspacePreparationTiming(
                IdeLifecycleStage.IMPORTING,
                300,
                300,
                0,
                WorkspacePreparationDeadlineOwner.REFRESH_WAITER,
            )
        val rejected = IdeLifecycleResult.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED, refresh)
        assertEquals(rejected, operation.finish(rejected))
        assertEquals(refresh, observations.last().preparation)
    }

    @Test
    fun `successful preparation reports admission time without source or workspace data`() = runTest {
        val observations = mutableListOf<NativePreparationObservation>()
        val operation =
            (NativeWorkspacePreparation.admit("request", 1000, { currentTime * 1_000_000 }, observations::add)
                    as Refinement.Refined)
                .value
        advanceTimeBy(700)
        operation.enter(IdeLifecycleStage.ADMISSION)
        val opened =
            IdeLifecycleResult.Opened(
                io.github.amichne.kast.protocol.contract.IdeProjectTarget("host", "project", "/fixture")
            )
        assertEquals(opened, operation.finish(opened))
        val terminal = observations.last()
        assertEquals(NativePreparationOutcome.COMPLETE, terminal.outcome)
        assertEquals(IdeLifecycleStage.ADMISSION, terminal.preparation.stage)
        assertEquals(700, terminal.preparation.elapsedMillis)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded = json.encodeToString(NativePreparationObservation.serializer(), terminal)
        assertFalse(encoded.contains("/fixture"))
        assertTrue(encoded.contains("\"elapsedMillis\":700"))
        assertTrue(encoded.contains("\"limitMillis\":1000"))
    }

    @Test
    fun `mandatory preparation can settle after two minutes within its readiness allowance`() = runTest {
        assertTrue(awaitLifecycleCondition { currentTime >= 173_200L })
        assertEquals(173_200L, currentTime)
    }

    @Test
    fun `opening and import share the remaining parent deadline and report its exact owner`() = runTest {
        val observations = mutableListOf<NativePreparationObservation>()
        val operation =
            (NativeWorkspacePreparation.admit("request", 1000, { currentTime * 1_000_000 }, observations::add)
                    as Refinement.Refined)
                .value
        assertTrue(awaitLifecycleCondition(operation.deadline, operation.nanoTime) { currentTime >= 700 })
        operation.enter(IdeLifecycleStage.IMPORTING)
        assertFalse(awaitLifecycleCondition(operation.deadline, operation.nanoTime) { currentTime >= 1200 })
        operation.finish(IdeLifecycleResult.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED))
        assertEquals(1000, currentTime)
        val terminal = observations.last()
        assertEquals("request", terminal.requestId)
        assertEquals(NativePreparationOutcome.BLOCKED, terminal.outcome)
        assertEquals(IdeLifecycleStage.IMPORTING, terminal.preparation.stage)
        assertEquals(1000, terminal.preparation.elapsedMillis)
        assertEquals(1000, terminal.preparation.limitMillis)
        assertEquals(0, terminal.preparation.remainingMillis)
        assertEquals(WorkspacePreparationDeadlineOwner.NATIVE_PREPARATION, terminal.preparation.limitingOwner)
    }

    @Test
    fun `expired parent allowance does not observe a child condition or grant fresh time`() = runTest {
        val operation =
            (NativeWorkspacePreparation.admit("request", 100, { currentTime * 1_000_000 }) as Refinement.Refined).value
        advanceTimeBy(100)
        var observations = 0
        assertFalse(
            awaitLifecycleCondition(operation.deadline, operation.nanoTime) {
                observations++
                true
            }
        )
        assertEquals(0, observations)
        assertEquals(100, currentTime)
    }

    @Test
    fun `waiter cancellation propagates instead of manufacturing deadline completion`() = runTest {
        val operation =
            (NativeWorkspacePreparation.admit("request", 1000, { currentTime * 1_000_000 }) as Refinement.Refined).value
        val waiter = async { awaitLifecycleCondition(operation.deadline, operation.nanoTime) { false } }
        runCurrent()
        advanceTimeBy(100)
        waiter.cancel()
        runCurrent()
        assertTrue(waiter.isCancelled)
        assertEquals(100, currentTime)
    }
}
