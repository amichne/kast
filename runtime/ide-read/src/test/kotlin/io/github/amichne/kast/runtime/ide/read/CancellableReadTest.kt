package io.github.amichne.kast.runtime.ide.read

import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadInvalidation
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadPort
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadOperation
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadResult
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CancellableReadTest {
    @Test
    fun `completed read releases exact permit once`() {
        val freshness = FreshnessFixture("/tmp/kast-cancellable-complete")
        val controller = controller(freshness.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port)
        val permit = active(controller.admit(freshness.capability()))

        assertEquals(
            CancellableProjectReadResult.Completed(
                "detached-result",
                ProjectReadContinuation.Idle,
            ),
            executor.execute(permit) { "detached-result" },
        )
        assertEquals(1, port.calls)
        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Released),
            controller.release(permit),
        )
    }

    @Test
    fun `release promotes one queued request and both execute once`() {
        val freshness = FreshnessFixture("/tmp/kast-cancellable-promotion")
        val controller = controller(freshness.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port)
        val firstPermit = active(controller.admit(freshness.capability()))
        val queuedRequest = queued(controller.admit(freshness.capability()))

        val first = executor.execute(firstPermit) { "first" }
            as CancellableProjectReadResult.Completed
        val promotion = first.continuation as ProjectReadContinuation.Promoted
        assertSame(queuedRequest, promotion.request)
        assertEquals(
            CancellableProjectReadResult.Completed("second", ProjectReadContinuation.Idle),
            executor.execute(promotion.permit) { "second" },
        )
        assertEquals(2, port.calls)
    }

    @Test
    fun `computed value is discarded after concurrent terminalization`() {
        val freshness = FreshnessFixture("/tmp/kast-cancellable-discard")
        val controller = controller(freshness.capability())
        val port = CancellingReadPort(controller)
        val executor = cancellableExecutor(controller, port)
        val permit = active(controller.admit(freshness.capability()))
        port.bind(permit)

        assertEquals(
            CancellableProjectReadResult.PermitInvalidated(
                CancellableProjectReadInvalidation.Terminalized(
                    ProjectReadPermitTerminal.Cancelled(
                        ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                    ),
                    ProjectReadContinuation.Idle,
                ),
            ),
            executor.execute(permit) { "must-not-complete" },
        )
    }
}

private class CancellingReadPort(
    private val controller: ProjectReadSingleFlight,
) : CancellableProjectReadPort {
    private sealed interface BoundPermit {
        data object Missing : BoundPermit
        data class Present(val permit: ProjectReadPermit) : BoundPermit
    }

    private var boundPermit: BoundPermit = BoundPermit.Missing

    fun bind(permit: ProjectReadPermit) {
        check(boundPermit === BoundPermit.Missing)
        boundPermit = BoundPermit.Present(permit)
    }

    override fun <Value : Any> execute(
        operation: CancellableProjectReadOperation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> {
        val permit = when (val bound = boundPermit) {
            BoundPermit.Missing -> error("fixture permit was not bound")
            is BoundPermit.Present -> bound.permit
        }
        controller.cancel(permit, ProjectReadCancellationCause.CLIENT_DISCONNECTED)
        return AdmittedProjectReadExecutionResult.Completed(operation.execute(testProject))
    }
}
