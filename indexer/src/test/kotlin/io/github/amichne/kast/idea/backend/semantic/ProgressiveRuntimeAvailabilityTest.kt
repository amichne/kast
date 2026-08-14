package io.github.amichne.kast.idea.backend.semantic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProgressiveRuntimeAvailabilityTest {
    @Test
    fun `compiler and workspace files complete while persisted lanes remain held`() = runBlocking {
        val persistedLanesRelease = CompletableDeferred<Unit>()
        val persistedLanes = async(Dispatchers.Default) { persistedLanesRelease.await() }
        val availability = ProgressiveRuntimeAvailability()

        val epoch = availability.publishCurrent()
        val compiler = availability.execute(CurrentRuntimeLane.COMPILER) { "compiler@${epoch.revision.value}" }
        val workspaceFiles = availability.execute(CurrentRuntimeLane.WORKSPACE_FILES) {
            "workspace-files@${epoch.revision.value}"
        }

        assertEquals(CurrentRuntimeExecution.Completed(epoch, "compiler@1"), compiler)
        assertEquals(CurrentRuntimeExecution.Completed(epoch, "workspace-files@1"), workspaceFiles)
        assertFalse(persistedLanes.isCompleted)
        persistedLanesRelease.complete(Unit)
    }

    @Test
    fun `compiler result crossing invalidation is rejected`() = runBlocking {
        val availability = ProgressiveRuntimeAvailability()
        val firstEpoch = availability.publishCurrent()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val execution = async(Dispatchers.Default) {
            availability.execute(CurrentRuntimeLane.COMPILER) {
                entered.complete(Unit)
                release.await()
                "stale"
            }
        }

        entered.await()
        availability.invalidate()
        release.complete(Unit)

        val rejected = assertInstanceOf(CurrentRuntimeExecution.Rejected::class.java, execution.await())
        assertEquals(
            CurrentRuntimeExecutionFailure.Invalidated(
                lane = CurrentRuntimeLane.COMPILER,
                admitted = firstEpoch,
                observed = CurrentRuntimeLaneState.Building,
            ),
            rejected.failure,
        )
    }

    @Test
    fun `republishing creates a distinct current epoch`() = runBlocking {
        val availability = ProgressiveRuntimeAvailability()
        val first = availability.publishCurrent()
        availability.invalidate()
        val second = availability.publishCurrent()

        assertEquals(1, first.revision.value)
        assertEquals(2, second.revision.value)
        assertEquals(
            CurrentRuntimeExecution.Completed(second, "fresh"),
            availability.execute(CurrentRuntimeLane.COMPILER) { "fresh" },
        )
    }
}
