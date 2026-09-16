package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceRefreshServiceTest {
    @Test
    fun `explicit new requests conservatively retain unobserved external changes`() {
        val port = Port().apply { ready = WorkspaceRefreshReadiness.READY }
        val service = WorkspaceRefreshService(port)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH)
        service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH)
        assertEquals(1, port.effects.size)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(2, port.effects.size)
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION), service.status(id(1)))
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(1)))
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(2)))
    }

    @Test
    fun `effect completion still requires fresh readiness and duplicate requests are idempotent`() {
        val port = Port()
        val service = WorkspaceRefreshService(port)
        val initial = service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT), initial)
        assertEquals(initial, service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1)))
        assertEquals(1, port.effects.size)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION), service.status(id(1)))
        port.ready = WorkspaceRefreshReadiness.READY
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(1)))
        assertEquals(
            WorkspaceRefreshStatus.Complete,
            service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1)),
        )
        assertEquals(1, port.effects.size)
    }

    @Test
    fun `equivalent work coalesces but a newer change waits for its own completed effect`() {
        val port = Port().apply { ready = WorkspaceRefreshReadiness.READY }
        val service = WorkspaceRefreshService(port)
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, stamp(1))
        service.submit(id(2), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, stamp(1))
        service.submit(id(3), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, stamp(2))
        assertEquals(1, port.effects.size)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(2, port.effects.size)
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION), service.status(id(1)))
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT), service.status(id(3)))
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        for (number in 1..3) assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(number)))
    }

    @Test
    fun `failed and cancelled imports remain terminal and do not stop independent queued work`() {
        for ((result, failure) in
            listOf(
                WorkspaceRefreshEffectResult.UNSAVED_DOCUMENTS to WorkspaceRefreshFailure.UNSAVED_DOCUMENTS,
                WorkspaceRefreshEffectResult.UNLINKED_BUILD to WorkspaceRefreshFailure.UNLINKED_BUILD,
                WorkspaceRefreshEffectResult.FAILED to WorkspaceRefreshFailure.EFFECT_FAILED,
                WorkspaceRefreshEffectResult.CANCELLED to WorkspaceRefreshFailure.CANCELLED,
            )) {
            val port = Port().apply { ready = WorkspaceRefreshReadiness.READY }
            val service = WorkspaceRefreshService(port)
            service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, stamp(1))
            service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH, stamp(2))
            port.finish(result)
            assertEquals(WorkspaceRefreshStatus.Failed(failure), service.status(id(1)))
            port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
            assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(2)))
        }
    }

    @Test
    fun `pending deadline capacity conflicting reuse and unknown ids fail closed`() {
        val port = Port()
        var now = 0L
        val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10, capacity = 1)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        assertEquals(
            WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.REQUEST_CONFLICT),
            service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, stamp(1)),
        )
        assertEquals(
            WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.CAPACITY),
            service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH, stamp(2)),
        )
        assertEquals(WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.UNKNOWN_REQUEST), service.status(id(3)))
        now = 10
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED), service.status(id(1)))
        port.ready = WorkspaceRefreshReadiness.READY
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED), service.status(id(1)))
    }

    @Test
    fun `host disposal rejects work and late callbacks cannot restore completion or affect another workspace`() {
        val first = Port()
        val second = Port().apply { ready = WorkspaceRefreshReadiness.READY }
        val service = WorkspaceRefreshService(first)
        val other = WorkspaceRefreshService(second)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        other.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        service.dispose()
        first.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED), service.status(id(1)))
        assertEquals(
            WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.DISPOSED),
            service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH, stamp(2)),
        )
        second.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Complete, other.status(id(1)))
    }

    private class Port : WorkspaceRefreshPort {
        val effects = mutableListOf<WorkspaceRefreshEffect>()
        val callbacks = ArrayDeque<(WorkspaceRefreshEffectResult) -> Unit>()
        var ready = WorkspaceRefreshReadiness.NOT_READY

        override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
            effects += effect
            callbacks += complete
        }

        override fun readiness() = ready

        fun finish(result: WorkspaceRefreshEffectResult) = callbacks.removeFirst()(result)
    }

    private fun id(value: Int) = (WorkspaceRefreshRequestId.parse("request-$value") as Refinement.Refined).value

    private fun stamp(value: Long) = (WorkspaceRefreshStamp.parse(value) as Refinement.Refined).value
}
