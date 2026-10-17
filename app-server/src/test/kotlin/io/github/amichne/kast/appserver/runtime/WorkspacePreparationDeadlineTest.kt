@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspacePreparationDeadlineTest {
    @Test
    fun `dispatch delay is charged before native open and expiry preserves last preparation stage`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val calls = mutableListOf<WorkspaceLifecycleRequest>()
        val events = mutableListOf<WorkspacePreparationActivity>()
        var dispatchDelay = 0L
        val preparations =
            WorkspacePreparations(
                this,
                { request ->
                    calls += request
                    fixture.pending(1)
                },
                observer = WorkspacePreparationObserver(events::add),
                budget = 1.seconds,
                newId = { fixture.id(1) },
                nanoTime = { (currentTime + dispatchDelay) * 1_000_000 },
            )
        val entry = (preparations.prepare(fixture.root) as Refinement.Refined).value
        dispatchDelay = 200
        runCurrent()
        assertEquals(800, (calls.single() as WorkspaceLifecycleRequest.Open).preparationMillis)
        advanceTimeBy(800)
        runCurrent()
        assertEquals(
            WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED),
            entry.state.value,
        )
        val timing = checkNotNull(events.last().preparation)
        assertEquals(IdeLifecycleStage.IMPORTING, timing.stage)
        assertEquals(1000, timing.elapsedMillis)
        assertEquals(1000, timing.limitMillis)
        assertEquals(0, timing.remainingMillis)
        assertEquals(WorkspacePreparationDeadlineOwner.PREPARATION_OWNER, timing.limitingOwner)
        preparations.close()
    }

    @Test
    fun `expired owner budget cannot start native preparation`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        var now = 0L
        var exchanges = 0
        val preparations =
            WorkspacePreparations(
                this,
                {
                    exchanges++
                    error("expired preparation must not exchange")
                },
                budget = 1.seconds,
                nanoTime = { now },
            )
        val entry = (preparations.prepare(fixture.root) as Refinement.Refined).value
        now = 1_000_000_000
        runCurrent()
        assertEquals(0, exchanges)
        assertEquals(
            WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED),
            entry.state.value,
        )
        preparations.close()
    }

    @Test
    fun `recovery host inspection consumes the same allowance before the new native open`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        var issued = 0
        val calls = mutableListOf<WorkspaceLifecycleRequest>()
        val preparations =
            WorkspacePreparations(
                this,
                { request ->
                    calls += request
                    when (request) {
                        WorkspaceLifecycleRequest.Inspect -> {
                            delay(200)
                            fixture.inspected()
                        }
                        is WorkspaceLifecycleRequest.Open ->
                            if (issued == 1) IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE)
                            else IdeLifecycleResult.Opened(fixture.target)
                        else -> error("unexpected request")
                    }
                },
                budget = 1.seconds,
                newId = { fixture.id(++issued) },
                nanoTime = { currentTime * 1_000_000 },
            )
        preparations.prepare(fixture.root)
        runCurrent()
        val retry = (preparations.prepareForDemand(fixture.root) as Refinement.Refined).value
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(800, (calls.last() as WorkspaceLifecycleRequest.Open).preparationMillis)
        assertEquals(fixture.target, (retry.state.value as WorkspacePreparationOutcome.Complete).workspace.target)
        preparations.close()
    }
}
