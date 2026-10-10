package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceCapabilityReadiness
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessFixture
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessReason
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessNextAction

class WorkspaceRefreshServiceTest {
    @Test
    fun `explicit new requests conservatively retain unobserved external changes`() {
        val port = Port().apply { ready = true }
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
        port.ready = true
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(1)))
        assertEquals(
            WorkspaceRefreshStatus.Complete,
            service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1)),
        )
        assertEquals(1, port.effects.size)
    }

    @Test
    fun `equivalent work coalesces but a newer change waits for its own completed effect`() {
        val port = Port().apply { ready = true }
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
            val port = Port().apply { ready = true }
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
        port.ready = true
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED), service.status(id(1)))
    }

    @Test
    fun `host disposal rejects work and late callbacks cannot restore completion or affect another workspace`() {
        val first = Port()
        val second = Port().apply { ready = true }
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

    @Test
    fun `terminal records do not permanently exhaust refresh capacity`() {
        val port = Port().apply { ready = true }
        val service = WorkspaceRefreshService(port, capacity = 1)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        port.finish(WorkspaceRefreshEffectResult.FAILED)
        assertEquals(
            WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT),
            service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1)),
        )
    }

    @Test
    fun `new demand reobserves pending admission before capacity accounting`() {
        val port = Port()
        val service = WorkspaceRefreshService(port, capacity = 1)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        port.ready = true
        assertEquals(
            WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT),
            service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH, stamp(2)),
        )
        assertEquals(2, port.effects.size)
    }

    @Test
    fun `late duplicate completion cannot settle retry of identical demand`() {
        val port = Port().apply { ready = true }
        val service = WorkspaceRefreshService(port)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        val stale = port.callbacks.first()
        port.finish(WorkspaceRefreshEffectResult.FAILED)
        service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        stale(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT), service.status(id(2)))
    }

    @Test
    fun `read waiters share incremental effect and cancellation does not cancel survivors`() {
        val port = Port()
        val service = WorkspaceRefreshService(port)
        val first = mutableListOf<WorkspaceRefreshStatus>()
        val second = mutableListOf<WorkspaceRefreshStatus>()
        val cancelFirst = service.refreshForRead(first::add)
        val cancelSecond = service.refreshForRead(second::add)
        cancelFirst()
        assertEquals(listOf(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.CANCELLED)), first)
        assertEquals(emptyList<WorkspaceRefreshStatus>(), second)
        assertEquals(1, port.effects.size)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(listOf(WorkspaceRefreshStatus.Complete), second)
        cancelSecond()
        assertEquals(listOf(WorkspaceRefreshStatus.Complete), second)
        assertEquals(1, port.effects.size)
        assertEquals(1, port.incrementalEffects)
        assertEquals(listOf(WorkspaceRefreshEffect.FILE_REFRESH), port.effects)
    }

    @Test
    fun `cancelled queued read is pruned while active shared native work remains fenced`() {
        val port = Port()
        val service = WorkspaceRefreshService(port)
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, stamp(1))
        val outcomes = mutableListOf<WorkspaceRefreshStatus>()
        val cancel = service.refreshForRead(outcomes::add)
        cancel()
        port.finish(WorkspaceRefreshEffectResult.FAILED)
        assertEquals(1, port.effects.size)
        assertEquals(listOf(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.CANCELLED)), outcomes)
    }

    @Test
    fun `missing native callback remains observable and timeout cannot start competing work`() {
        val port = Port()
        var now = 0L
        val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10, capacity = 2)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        now = 10
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED), service.status(id(1)))
        service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.QUEUED), service.status(id(2)))
        assertEquals(true, service.hasWork())
        assertEquals(1, port.effects.size)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(2, port.effects.size)
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT), service.status(id(2)))
    }

    @Test
    fun `inspection preserves unresolved attempt identity when expired waiter records are evicted`() {
        val port = Port()
        var now = 0L
        val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10, capacity = 1)
        service.submit(id(1), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        val before = assertInstanceOf(WorkspaceRefreshInspection.Running::class.java, service.inspection())
        now = 10
        service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
        val after = assertInstanceOf(WorkspaceRefreshInspection.Running::class.java, service.inspection())
        assertEquals(before.active.id, after.active.id)
        assertEquals(WorkspaceRefreshWaiterIdentity.Request(id(1)), after.active.initiator)
        assertEquals(emptyList<WorkspaceRefreshWaiterInspection>(), after.active.waiters)
        assertEquals(1, after.queued.size)
        assertEquals(false, after.active.id == after.queued.single().id)
        assertEquals(WorkspaceRefreshWaiterIdentity.Request(id(2)), after.queued.single().initiator)
        assertEquals(1, port.effects.size)
    }

    @Test
    fun `retired inspection retains native identity until actual late settlement without revival`() {
        val port = Port()
        val service = WorkspaceRefreshService(port)
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, stamp(1))
        val callback = port.callbacks.first()
        callback(WorkspaceRefreshEffectResult.RETIRED)
        val retired = assertInstanceOf(WorkspaceRefreshInspection.Retired::class.java, service.inspection())
        assertEquals(1, retired.unsettled.size)
        assertEquals(WorkspaceRefreshWaiterIdentity.Request(id(1)), retired.unsettled.single().initiator)
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED), service.status(id(1)))
        callback(WorkspaceRefreshEffectResult.DISPOSED)
        val settled = assertInstanceOf(WorkspaceRefreshInspection.Retired::class.java, service.inspection())
        assertEquals(emptyList<WorkspaceRefreshAttemptInspection>(), settled.unsettled)
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED), service.status(id(1)))
        assertEquals(WorkspaceRefreshStatus.Rejected(WorkspaceRefreshRejection.DISPOSED), service.submit(id(2), WorkspaceRefreshEffect.FILE_REFRESH))
    }

    @Test
    fun `generated bounded prefixes preserve one native attempt and reject stale completions`() {
        val alphabet = listOf("demand", "read", "cancel", "timeout", "settle", "stale", "dispose")
        fun check(events: List<String>) {
            val port = Port().apply { ready = true }
            var now = 0L
            val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10, capacity = 2)
            var request = 0
            var settled = 0
            var retired = false
            var startsAtRetirement = 0
            val cancellations = mutableListOf<() -> Unit>()
            val completed = mutableListOf<(WorkspaceRefreshEffectResult) -> Unit>()
            for (event in events) {
                when (event) {
                    "demand" -> service.submit(id(++request), WorkspaceRefreshEffect.FILE_REFRESH, stamp(1))
                    "read" -> cancellations += service.refreshForRead {}
                    "cancel" -> cancellations.lastOrNull()?.invoke()
                    "timeout" -> { now += 10; if (request > 0) service.status(id(request)) }
                    "settle" -> if (port.callbacks.isNotEmpty()) {
                        val callback = port.callbacks.removeFirst()
                        completed += callback
                        settled++
                        callback(WorkspaceRefreshEffectResult.FAILED)
                    }
                    "stale" -> completed.lastOrNull()?.invoke(WorkspaceRefreshEffectResult.SUCCEEDED)
                    "dispose" -> { service.dispose(); retired = true; startsAtRetirement = port.effects.size }
                }
                assertEquals(true, port.effects.size <= settled + 1, events.toString())
                if (port.callbacks.isNotEmpty() && !retired) assertEquals(true, service.hasWork(), events.toString())
                if (retired) assertEquals(startsAtRetirement, port.effects.size, events.toString())
            }
        }
        fun generate(prefix: List<String>, remaining: Int) {
            check(prefix)
            if (remaining > 0) alphabet.forEach { generate(prefix + it, remaining - 1) }
        }
        generate(emptyList(), 4)
    }

    private class Port : WorkspaceRefreshPort {
        val effects = mutableListOf<WorkspaceRefreshEffect>()
        val callbacks = ArrayDeque<(WorkspaceRefreshEffectResult) -> Unit>()
        var incrementalEffects = 0
        var ready = false

        override fun start(effect: WorkspaceRefreshEffect, complete: (WorkspaceRefreshEffectResult) -> Unit) {
            effects += effect
            callbacks += complete
        }

        private val facts = WorkspaceReadinessFixture(
            (CanonicalWorkspaceRoot.fromCanonicalPath(java.nio.file.Path.of("/workspace")) as Refinement.Refined).value
        )

        override fun startIncremental(complete: (WorkspaceRefreshEffectResult) -> Unit) {
            incrementalEffects++
            start(WorkspaceRefreshEffect.FILE_REFRESH, complete)
        }

        override fun readiness(): WorkspaceCapabilityReadiness =
            if (ready) facts.ready()
            else WorkspaceCapabilityReadiness.Pending(
                facts.identity,
                WorkspaceReadinessReason.INDEXING,
                WorkspaceReadinessNextAction.OBSERVE_AGAIN,
            )

        fun finish(result: WorkspaceRefreshEffectResult) = callbacks.removeFirst()(result)
    }

    private fun id(value: Int) = (WorkspaceRefreshRequestId.parse("request-$value") as Refinement.Refined).value

    private fun stamp(value: Long) = (WorkspaceRefreshStamp.parse(value) as Refinement.Refined).value
}
