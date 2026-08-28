package io.github.amichne.kast.runtime.ide.read

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadExecutor
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadHostRejection
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadInvalidation
import io.github.amichne.kast.runtime.ide.read.execution.CancellableProjectReadResult
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecution
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionFailure
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CancellableReadNegativeTest {
    @Test
    fun `foreign executing and terminal permits reject before another read`() {
        val freshness = FreshnessFixture("/tmp/kast-cancellable-negative")
        val controller = controller(freshness.capability())
        val foreignFreshness = FreshnessFixture("/tmp/kast-cancellable-foreign")
        val foreignController = controller(foreignFreshness.capability())
        val port = InvokingReadPort()
        val executor = cancellableExecutor(controller, port)
        val foreign = active(foreignController.admit(foreignFreshness.capability()))
        assertEquals(
            CancellableProjectReadResult.PermitRejected(
                ProjectReadExecutionAdmissionFailure.NotOwned,
            ),
            executor.execute(foreign) { "foreign" },
        )

        val permit = active(controller.admit(freshness.capability()))
        val entered = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val blockingPort = BlockingReadPort(entered, continueRead)
        val blockingExecutor = cancellableExecutor(controller, blockingPort)
        val thread = Executors.newSingleThreadExecutor()
        try {
            val first = thread.submit<CancellableProjectReadResult<String>> {
                blockingExecutor.execute(permit) { "first" }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(
                CancellableProjectReadResult.PermitRejected(
                    ProjectReadExecutionAdmissionFailure.AlreadyExecuting,
                ),
                blockingExecutor.execute(permit) { "second" },
            )
            continueRead.countDown()
            assertTrue(first.get(10, TimeUnit.SECONDS) is CancellableProjectReadResult.Completed<*>)
        } finally {
            continueRead.countDown()
            thread.shutdownNow()
        }
        assertEquals(
            CancellableProjectReadResult.PermitRejected(
                ProjectReadExecutionAdmissionFailure.Terminal(
                    ProjectReadPermitTerminal.Released,
                ),
            ),
            blockingExecutor.execute(permit) { "terminal" },
        )
        assertEquals(0, port.calls)
        assertEquals(1, blockingPort.calls)
    }

    @Test
    fun `every fail fast host state is typed and releases authority`() {
        for (failure in AdmittedProjectReadExecutionFailure.entries) {
            val freshness = FreshnessFixture("/tmp/kast-cancellable-${failure.name.lowercase()}")
            val controller = controller(freshness.capability())
            val port = RejectingReadPort(failure)
            val executor = cancellableExecutor(controller, port)
            val permit = active(controller.admit(freshness.capability()))
            val result = executor.execute(permit) { "not-called" }
            assertEquals(1, port.calls)
            if (failure == AdmittedProjectReadExecutionFailure.PROJECT_DISPOSED) {
                val disposed = result as CancellableProjectReadResult.ProjectDisposed
                assertSame(
                    permit,
                    (disposed.retiredAuthority as RetiredProjectReadAuthority.Active).permit,
                )
                assertEquals(
                    ProjectReadPermitEnd.AlreadyEnded(
                        ProjectReadPermitTerminal.Retired(
                            ProjectReadRetirementCause.PROJECT_DISPOSED,
                        ),
                    ),
                    controller.release(permit),
                )
            } else {
                assertEquals(
                    CancellableProjectReadResult.HostRejected(
                        hostRejection(failure),
                        ProjectReadContinuation.Idle,
                    ),
                    result,
                )
                assertEquals(
                    ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Released),
                    controller.release(permit),
                )
            }
        }
    }

    @Test
    fun `platform cancellation propagates after exact cancellation terminalization`() {
        val cancellations = listOf(
            ProcessCanceledException() to ProjectReadExecutionCancellationCause.PLATFORM_CANCELLED,
            ReadAction.CannotReadException() to
                ProjectReadExecutionCancellationCause.WRITE_PREEMPTED,
        )
        for ((cancellation, cause) in cancellations) {
            val freshness = FreshnessFixture(
                "/tmp/kast-cancellable-cancel-${cancellation::class.java.simpleName}",
            )
            val controller = controller(freshness.capability())
            val executor = cancellableExecutor(controller, CancelOnceReadPort(cancellation))
            val permit = active(controller.admit(freshness.capability()))
            val request = queued(controller.admit(freshness.capability()))
            val observed = try {
                executor.execute(permit) { "not-called" }
                throw AssertionError("platform cancellation was swallowed")
            } catch (caught: ProcessCanceledException) {
                caught
            }
            assertSame(cancellation, observed)
            assertEquals(
                ProjectReadPermitEnd.AlreadyEnded(
                    ProjectReadPermitTerminal.ExecutionCancelled(cause),
                ),
                controller.release(permit),
            )
            val terminal = executor.observeQueued(request) as QueuedProjectReadObservation.Terminal
            val promotion = terminal.value as QueuedProjectReadTerminal.Promoted
            assertEquals(
                CancellableProjectReadResult.Completed(
                    "after-cancellation",
                    ProjectReadContinuation.Idle,
                ),
                executor.execute(promotion.permit) { "after-cancellation" },
            )
        }
    }

    @Test
    fun `concurrent cancellation or retirement discards a later value`() {
        assertConcurrentInvalidation(retire = false)
        assertConcurrentInvalidation(retire = true)
    }

    @Test
    fun `client cancellation signals exact executing process before unwind`() {
        val freshness = FreshnessFixture("/tmp/kast-cancellable-signal")
        val controller = controller(freshness.capability())
        val entered = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val processFactory = SignalingReadProcessFactory()
        val executor = cancellableExecutor(
            controller,
            BlockingReadPort(entered, continueRead),
            processFactory,
        )
        val permit = active(controller.admit(freshness.capability()))
        val thread = Executors.newSingleThreadExecutor()
        try {
            val pending = thread.submit<CancellableProjectReadResult<String>> {
                executor.execute(permit) { "discarded" }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(
                ProjectReadExecutionCancellation.Deferred(
                    ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                ),
                executor.cancel(permit, ProjectReadCancellationCause.CLIENT_DISCONNECTED),
            )
            assertTrue(processFactory.cancelled.await(10, TimeUnit.SECONDS))
            continueRead.countDown()
            val observed = try {
                pending.get(10, TimeUnit.SECONDS)
                throw AssertionError("cancelled execution completed")
            } catch (failure: java.util.concurrent.ExecutionException) {
                failure.cause
            }
            assertSame(processFactory.cancellation, observed)
            assertEquals(
                ProjectReadPermitEnd.AlreadyEnded(
                    ProjectReadPermitTerminal.Cancelled(
                        ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                    ),
                ),
                controller.release(permit),
            )
        } finally {
            continueRead.countDown()
            thread.shutdownNow()
        }
    }

    @Test
    fun `unexpected defect propagates after releasing permit`() {
        val freshness = FreshnessFixture("/tmp/kast-cancellable-defect")
        val controller = controller(freshness.capability())
        val defect = IllegalStateException("fixture defect")
        val executor = cancellableExecutor(controller, ThrowingReadPort(defect))
        val permit = active(controller.admit(freshness.capability()))
        val observed = try {
            executor.execute(permit) { "not-called" }
            throw AssertionError("defect was swallowed")
        } catch (caught: IllegalStateException) {
            caught
        }
        assertSame(defect, observed)
        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Released),
            controller.release(permit),
        )
    }

    @Test
    fun `process preparation defect leaves permit active and owned`() {
        val freshness = FreshnessFixture("/tmp/kast-cancellable-process-defect")
        val controller = controller(freshness.capability())
        val defect = IllegalStateException("process preparation defect")
        val port = InvokingReadPort()
        val executor = cancellableExecutor(
            controller,
            port,
            processFactory = { throw defect },
        )
        val permit = active(controller.admit(freshness.capability()))
        val observed = try {
            executor.execute(permit) { "not-called" }
            throw AssertionError("process preparation defect was swallowed")
        } catch (caught: IllegalStateException) {
            caught
        }
        assertSame(defect, observed)
        assertEquals(0, port.calls)
        assertEquals(
            ProjectReadPermitEnd.Ended(
                ProjectReadPermitTerminal.Released,
                ProjectReadContinuation.Idle,
            ),
            controller.release(permit),
        )
    }

    @Test
    fun `live adapter bytecode and public surface enforce the KVP 021 boundary`() {
        val resource = AdmittedProjectReadExecution::class.java.name.replace('.', '/') + ".class"
        val bytes = checkNotNull(
            AdmittedProjectReadExecution::class.java.classLoader.getResourceAsStream(resource),
        ).use { stream -> stream.readBytes() }.toString(Charsets.ISO_8859_1)
        listOf(
            "computeCancellable",
            "checkCanceled",
            "isDispatchThread",
            "isReadAccessAllowed",
            "isDisposed",
            "isOpen",
            "isDumb",
        ).forEach { required -> assertTrue(bytes.contains(required), required) }
        listOf(
            "computeBlocking",
            "waitForSmartMode",
            "runReadActionInSmartMode",
            "executeSynchronously",
            "java/lang/Thread",
            "sleep",
            "invokeAndWait",
        ).forEach { forbidden -> assertFalse(bytes.contains(forbidden), forbidden) }

        assertTrue(AdmittedProjectReadExecution::class.java.declaredConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers) || constructor.isSynthetic
        })
        assertNoPublicProjectSurface(AdmittedIdeProject::class.java)
        assertNoPublicProjectSurface(CancellableProjectReadExecutor::class.java)
        assertFalse(CancellableProjectReadExecutor::class.java.declaredFields.any { field ->
            field.type == Project::class.java || field.type.name.startsWith("kotlin.jvm.functions")
        })
    }

    private fun assertConcurrentInvalidation(retire: Boolean) {
        val freshness = FreshnessFixture("/tmp/kast-cancellable-race-$retire")
        val controller = controller(freshness.capability())
        val entered = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val executor = cancellableExecutor(controller, BlockingReadPort(entered, continueRead))
        val permit = active(controller.admit(freshness.capability()))
        val queuedRequest = queued(controller.admit(freshness.capability()))
        val thread = Executors.newSingleThreadExecutor()
        try {
            val pending = thread.submit<CancellableProjectReadResult<String>> {
                executor.execute(permit) { "discarded" }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            if (retire) {
                controller.retire(ProjectReadRetirementCause.PROJECT_DISPOSED)
            } else {
                assertEquals(
                    ProjectReadExecutionCancellation.Deferred(
                        ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                    ),
                    executor.cancel(
                        permit,
                        ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                    ),
                )
            }
            continueRead.countDown()
            val result = pending.get(10, TimeUnit.SECONDS)
                as CancellableProjectReadResult.PermitInvalidated
            if (retire) {
                assertEquals(
                    CancellableProjectReadInvalidation.AlreadyEnded(
                        ProjectReadPermitTerminal.Retired(
                            ProjectReadRetirementCause.PROJECT_DISPOSED,
                        ),
                    ),
                    result.invalidation,
                )
                assertEquals(
                    QueuedProjectReadCancellation.AlreadyTerminal(
                        QueuedProjectReadTerminal.Retired(
                            ProjectReadRetirementCause.PROJECT_DISPOSED,
                        ),
                    ),
                    controller.cancelQueued(
                        queuedRequest,
                        ProjectReadCancellationCause.REQUEST_CANCELLED,
                    ),
                )
            } else {
                val invalidation = result.invalidation
                    as CancellableProjectReadInvalidation.Terminalized
                assertEquals(
                    ProjectReadPermitTerminal.Cancelled(
                        ProjectReadCancellationCause.CLIENT_DISCONNECTED,
                    ),
                    invalidation.terminal,
                )
                val promotion = invalidation.continuation as ProjectReadContinuation.Promoted
                assertSame(queuedRequest, promotion.request)
                assertTrue(controller.release(promotion.permit) is ProjectReadPermitEnd.Ended)
            }
        } finally {
            continueRead.countDown()
            thread.shutdownNow()
        }
    }

    private fun assertNoPublicProjectSurface(type: Class<*>) {
        val exposed = type.methods.filter { method ->
            Modifier.isPublic(method.modifiers) && (
                method.returnType == Project::class.java ||
                    method.parameterTypes.any { parameter -> parameter == Project::class.java }
                )
        }
        assertTrue(exposed.isEmpty(), "public Project surface: $exposed")
    }
}

private fun hostRejection(
    failure: AdmittedProjectReadExecutionFailure,
): CancellableProjectReadHostRejection = when (failure) {
    AdmittedProjectReadExecutionFailure.WRONG_THREAD ->
        CancellableProjectReadHostRejection.WRONG_THREAD
    AdmittedProjectReadExecutionFailure.EXISTING_READ_ACCESS ->
        CancellableProjectReadHostRejection.EXISTING_READ_ACCESS
    AdmittedProjectReadExecutionFailure.PROJECT_NOT_OPEN ->
        CancellableProjectReadHostRejection.PROJECT_NOT_OPEN
    AdmittedProjectReadExecutionFailure.DUMB_MODE ->
        CancellableProjectReadHostRejection.DUMB_MODE
    AdmittedProjectReadExecutionFailure.PROJECT_DISPOSED -> error("disposal is not rejection")
}
