package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class WorkspaceRefreshDeadlineTest {
    @Test
    fun `indefinite incremental lifecycle waiter survives the default and ends on disposal`() {
        val port = WorkspaceRefreshTestPort()
        var now = 0L
        val service = WorkspaceRefreshService(port, { now })
        val outcomes = mutableListOf<WorkspaceRefreshStatus>()
        service.refreshForRead(io.github.amichne.kast.kernel.ElapsedTimeAllowance.Unlimited, outcomes::add)
        now = 18 * 60_000_000_000L
        service.inspection()
        assertEquals(emptyList<WorkspaceRefreshStatus>(), outcomes)
        assertEquals(1, port.incrementalEffects)
        service.dispose()
        assertEquals(listOf(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DISPOSED)), outcomes)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun `incremental lifecycle waiter uses its finite allowance instead of the read default`() {
        val port = WorkspaceRefreshTestPort()
        var now = 0L
        val service = WorkspaceRefreshService(port, { now }, pendingTimeoutNanos = 10_000_000_000)
        val outcomes = mutableListOf<WorkspaceRefreshStatus>()
        val allowance =
            (io.github.amichne.kast.kernel.ElapsedTimeAllowance.admitMillis(30_000) as Refinement.Refined).value
        service.refreshForRead(allowance, outcomes::add)
        now = 20_000_000_000
        service.inspection()
        assertEquals(emptyList<WorkspaceRefreshStatus>(), outcomes)
        now = 30_000_000_000
        service.inspection()
        assertEquals(listOf(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED)), outcomes)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun `indefinite refresh waiter retains the live attempt beyond the default deadline`() {
        val port = WorkspaceRefreshTestPort()
        var now = 0L
        val service = WorkspaceRefreshService(port, { now })
        service.submit(
            id(1),
            WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD,
            io.github.amichne.kast.kernel.ElapsedTimeAllowance.Unlimited,
        )
        now = 18 * 60_000_000_000L
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT), service.status(id(1)))
        assertEquals(1, port.effects.size)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        port.ready = true
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(1)))
    }

    @Test
    fun `inherited shorter allowance expires its waiter without cancelling native work and attributes the owner`() {
        val port = WorkspaceRefreshTestPort()
        var now = 0L
        val observations = mutableListOf<WorkspaceRefreshDeadlineObservation>()
        val service = WorkspaceRefreshService(port, { now }, observeDeadline = observations::add)
        val allowance = (io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(300) as Refinement.Refined).value
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD, allowance)
        now = 300_000_000
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED), service.status(id(1)))
        val observation = observations.single()
        assertEquals(id(1).value, observation.requestId)
        assertEquals(300, observation.preparation.elapsedMillis)
        assertEquals(300, observation.preparation.limitMillis)
        assertEquals(0, observation.preparation.remainingMillis)
        assertEquals(
            io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner.REFRESH_WAITER,
            observation.preparation.limitingOwner,
        )
        assertInstanceOf(WorkspaceRefreshInspection.Running::class.java, service.inspection())
        assertEquals(1, port.effects.size)
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Failed(WorkspaceRefreshFailure.DEADLINE_EXCEEDED), service.status(id(1)))
        assertEquals(1, observations.size)
    }

    @Test
    fun `mandatory import remains pending past two minutes until native settlement and fresh readiness`() {
        val port = WorkspaceRefreshTestPort()
        var now = 0L
        val service = WorkspaceRefreshService(port, { now })
        service.submit(id(1), WorkspaceRefreshEffect.GRADLE_MODEL_RELOAD)
        now = 173_200_000_000L
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.EFFECT), service.status(id(1)))
        port.finish(WorkspaceRefreshEffectResult.SUCCEEDED)
        assertEquals(WorkspaceRefreshStatus.Pending(WorkspaceRefreshStage.ADMISSION), service.status(id(1)))
        port.ready = true
        assertEquals(WorkspaceRefreshStatus.Complete, service.status(id(1)))
        assertEquals(1, port.effects.size)
    }

    private fun id(number: Int): WorkspaceRefreshRequestId =
        (WorkspaceRefreshRequestId.parse("deadline-$number") as Refinement.Refined).value
}
