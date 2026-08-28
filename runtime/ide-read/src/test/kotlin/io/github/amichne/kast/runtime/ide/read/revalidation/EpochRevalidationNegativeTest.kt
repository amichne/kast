package io.github.amichne.kast.runtime.ide.read

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadHostRejection
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadInvalidation
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadResult
import io.github.amichne.kast.runtime.ide.read.revalidation.EpochRevalidationPhase
import io.github.amichne.kast.runtime.ide.read.revalidation.RevalidatedIdeReadResult
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class EpochRevalidationNegativeTest {
    @Test
    fun `movement during semantic query cannot admit a completed value`() {
        val fixture = EpochRevalidationFixture("/tmp/kast-epoch-query-moved")
        val controller = controller(fixture.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port, epochObserver = fixture)
        val permit = active(controller.admit(fixture.capability()))

        assertEquals(
            CancellableProjectReadResult.Completed(
                RevalidatedIdeReadResult.Rejected.WorkspaceMoved,
                ProjectReadContinuation.Idle,
            ),
            executor.executeRevalidated(permit) {
                fixture.advance()
                detached("discarded")
            },
        )
        assertEquals(2, fixture.observations)
        assertEquals(1, port.calls)
    }

    @Test
    fun `movement during projection cannot admit a completed value`() {
        val fixture = EpochRevalidationFixture("/tmp/kast-epoch-projection-moved")
        fixture.plan(
            EpochRevalidationFixture.Step.Current,
            EpochRevalidationFixture.Step.Moved,
        )
        val controller = controller(fixture.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port, epochObserver = fixture)
        val permit = active(controller.admit(fixture.capability()))

        assertEquals(
            CancellableProjectReadResult.Completed(
                RevalidatedIdeReadResult.Rejected.WorkspaceMoved,
                ProjectReadContinuation.Idle,
            ),
            executor.executeRevalidated(permit) { detached("discarded") },
        )
        assertEquals(2, fixture.observations)
        assertEquals(1, port.calls)
    }

    @Test
    fun `incomparable after epoch is a closed rejection`() {
        val fixture = EpochRevalidationFixture("/tmp/kast-epoch-incomparable")
        fixture.plan(
            EpochRevalidationFixture.Step.Current,
            EpochRevalidationFixture.Step.Incomparable,
        )
        val controller = controller(fixture.capability())
        val executor = cancellableExecutor(
            controller,
            InvokingReadPort(),
            epochObserver = fixture,
        )
        val permit = active(controller.admit(fixture.capability()))

        assertEquals(
            CancellableProjectReadResult.Completed(
                RevalidatedIdeReadResult.Rejected.IncomparableEpoch,
                ProjectReadContinuation.Idle,
            ),
            executor.executeRevalidated(permit) { detached("discarded") },
        )
    }

    @Test
    fun `observation rejection retains its exact phase and work count`() {
        val beforeFixture = EpochRevalidationFixture("/tmp/kast-epoch-before-rejected")
        beforeFixture.plan(
            EpochRevalidationFixture.Step.Rejected(
                ProjectReadEpochObservationFailure.ProjectNotOpen,
            ),
        )
        val beforePort = InvokingReadPort()
        assertEquals(
            CancellableProjectReadResult.Completed(
                RevalidatedIdeReadResult.Rejected.EpochObservationRejected(
                    EpochRevalidationPhase.BEFORE,
                    ProjectReadEpochObservationFailure.ProjectNotOpen,
                ),
                ProjectReadContinuation.Idle,
            ),
            executeOne(beforeFixture, beforePort),
        )
        assertEquals(1, beforeFixture.observations)
        assertEquals(0, beforePort.calls)

        val afterFixture = EpochRevalidationFixture("/tmp/kast-epoch-after-rejected")
        afterFixture.plan(
            EpochRevalidationFixture.Step.Current,
            EpochRevalidationFixture.Step.Rejected(
                ProjectReadEpochObservationFailure.DumbMode,
            ),
        )
        val afterPort = InvokingReadPort()
        assertEquals(
            CancellableProjectReadResult.Completed(
                RevalidatedIdeReadResult.Rejected.EpochObservationRejected(
                    EpochRevalidationPhase.AFTER,
                    ProjectReadEpochObservationFailure.DumbMode,
                ),
                ProjectReadContinuation.Idle,
            ),
            executeOne(afterFixture, afterPort),
        )
        assertEquals(2, afterFixture.observations)
        assertEquals(1, afterPort.calls)
    }

    @Test
    fun `platform cancellation propagates without retry and leaves promotion retrievable`() {
        CancellationPhase.entries.forEach(::assertCancellation)
    }

    @Test
    fun `permit and host rejection preserve the KVP 021 outer result`() {
        val fixture = EpochRevalidationFixture("/tmp/kast-epoch-outer-results")
        val controller = controller(fixture.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port, epochObserver = fixture)
        val foreignFixture = EpochRevalidationFixture("/tmp/kast-epoch-foreign")
        val foreignController = controller(foreignFixture.capability())
        val foreignPermit = active(foreignController.admit(foreignFixture.capability()))

        assertEquals(
            CancellableProjectReadResult.PermitRejected(
                ProjectReadExecutionAdmissionFailure.NotOwned,
            ),
            executor.executeRevalidated(foreignPermit) { detached("not-called") },
        )
        assertEquals(0, fixture.observations)
        assertEquals(0, port.calls)

        val hostFixture = EpochRevalidationFixture("/tmp/kast-epoch-host-rejected")
        val hostController = controller(hostFixture.capability())
        val hostExecutor = cancellableExecutor(
            hostController,
            RejectingReadPort(AdmittedProjectReadExecutionFailure.WRONG_THREAD),
            epochObserver = hostFixture,
        )
        val hostPermit = active(hostController.admit(hostFixture.capability()))
        assertEquals(
            CancellableProjectReadResult.HostRejected(
                CancellableProjectReadHostRejection.WRONG_THREAD,
                ProjectReadContinuation.Idle,
            ),
            hostExecutor.executeRevalidated(hostPermit) { detached("not-called") },
        )
        assertEquals(1, hostFixture.observations)

        val disposedFixture = EpochRevalidationFixture("/tmp/kast-epoch-disposed")
        val disposedController = controller(disposedFixture.capability())
        val disposedExecutor = cancellableExecutor(
            disposedController,
            RejectingReadPort(AdmittedProjectReadExecutionFailure.PROJECT_DISPOSED),
            epochObserver = disposedFixture,
        )
        val disposedPermit = active(disposedController.admit(disposedFixture.capability()))
        val disposed = disposedExecutor.executeRevalidated(disposedPermit) {
            detached("not-called")
        } as CancellableProjectReadResult.ProjectDisposed
        assertSame(
            disposedPermit,
            (disposed.retiredAuthority as RetiredProjectReadAuthority.Active).permit,
        )

        val invalidatedFixture = EpochRevalidationFixture("/tmp/kast-epoch-invalidated")
        val invalidatedController = controller(invalidatedFixture.capability())
        val invalidatedExecutor = cancellableExecutor(
            invalidatedController,
            InvokingReadPort(),
            epochObserver = invalidatedFixture,
        )
        val invalidatedPermit = active(
            invalidatedController.admit(invalidatedFixture.capability()),
        )
        assertEquals(
            CancellableProjectReadResult.PermitInvalidated(
                CancellableProjectReadInvalidation.Terminalized(
                    ProjectReadPermitTerminal.Cancelled(
                        ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                    ),
                    ProjectReadContinuation.Idle,
                ),
            ),
            invalidatedExecutor.executeRevalidated(invalidatedPermit) {
                invalidatedController.cancel(
                    invalidatedPermit,
                    ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                )
                detached("discarded")
            },
        )
    }

    private fun executeOne(
        fixture: EpochRevalidationFixture,
        port: InvokingReadPort,
    ): CancellableProjectReadResult<RevalidatedIdeReadResult<String>> {
        val controller = controller(fixture.capability())
        val executor = cancellableExecutor(controller, port, epochObserver = fixture)
        val permit = active(controller.admit(fixture.capability()))
        return executor.executeRevalidated(permit) { detached("detached") }
    }

    private fun assertCancellation(phase: CancellationPhase) {
        val cancellation = ProcessCanceledException()
        val fixture = EpochRevalidationFixture(
            "/tmp/kast-epoch-cancel-${phase.name.lowercase()}",
        )
        when (phase) {
            CancellationPhase.BEFORE -> fixture.plan(
                EpochRevalidationFixture.Step.Cancelled(cancellation),
            )
            CancellationPhase.AFTER -> fixture.plan(
                EpochRevalidationFixture.Step.Current,
                EpochRevalidationFixture.Step.Cancelled(cancellation),
            )
        }
        val controller = controller(fixture.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port, epochObserver = fixture)
        val permit = active(controller.admit(fixture.capability()))
        val request = queued(controller.admit(fixture.capability()))

        val observed = try {
            executor.executeRevalidated(permit) { detached("must-not-complete") }
            throw AssertionError("platform cancellation was swallowed")
        } catch (caught: ProcessCanceledException) {
            caught
        }
        assertSame(cancellation, observed)
        assertEquals(phase.semanticCallsBeforeCancellation, port.calls)
        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(
                ProjectReadPermitTerminal.ExecutionCancelled(
                    ProjectReadExecutionCancellationCause.PLATFORM_CANCELLED,
                ),
            ),
            controller.release(permit),
        )

        val terminal = executor.observeQueued(request) as QueuedProjectReadObservation.Terminal
        val promotion = terminal.value as QueuedProjectReadTerminal.Promoted
        val completed = executor.executeRevalidated(promotion.permit) {
            detached("after-cancellation")
        }
            as CancellableProjectReadResult.Completed
        val revalidated = completed.value as RevalidatedIdeReadResult.Complete
        assertEquals("after-cancellation", revalidated.projection.value)
        assertEquals(ProjectReadContinuation.Idle, completed.continuation)
        assertEquals(phase.semanticCallsBeforeCancellation + 1, port.calls)
    }
}

private enum class CancellationPhase(val semanticCallsBeforeCancellation: Int) {
    BEFORE(0),
    AFTER(1),
}
