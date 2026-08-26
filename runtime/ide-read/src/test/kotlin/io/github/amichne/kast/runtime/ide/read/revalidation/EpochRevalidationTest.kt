package io.github.amichne.kast.runtime.ide.read

import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadResult
import io.github.amichne.kast.runtime.ide.read.revalidation.RevalidatedIdeReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class EpochRevalidationTest {
    @Test
    fun `stable epoch completes with one detached projection and exact observations`() {
        val fixture = EpochRevalidationFixture("/tmp/kast-epoch-stable")
        val controller = controller(fixture.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port, epochObserver = fixture)
        val permit = active(controller.admit(fixture.capability()))

        val completed = executor.executeRevalidated(permit) { "detached-result" }
            as CancellableProjectReadResult.Completed
        val revalidated = completed.value as RevalidatedIdeReadResult.Complete
        assertEquals("detached-result", revalidated.projection.value)
        assertEquals(ProjectReadContinuation.Idle, completed.continuation)
        assertEquals(2, fixture.observations)
        assertEquals(1, port.calls)
    }

    @Test
    fun `stable completion preserves the exact queued continuation`() {
        val fixture = EpochRevalidationFixture("/tmp/kast-epoch-promotion")
        val controller = controller(fixture.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port, epochObserver = fixture)
        val firstPermit = active(controller.admit(fixture.capability()))
        val queuedRequest = queued(controller.admit(fixture.capability()))

        val first = executor.executeRevalidated(firstPermit) { "first" }
            as CancellableProjectReadResult.Completed
        val promotion = first.continuation as ProjectReadContinuation.Promoted
        assertSame(queuedRequest, promotion.request)
        val second = executor.executeRevalidated(promotion.permit) { "second" }
            as CancellableProjectReadResult.Completed
        val revalidated = second.value as RevalidatedIdeReadResult.Complete
        assertEquals("second", revalidated.projection.value)
        assertEquals(ProjectReadContinuation.Idle, second.continuation)
        assertEquals(4, fixture.observations)
        assertEquals(2, port.calls)
    }
}
