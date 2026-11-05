@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import io.github.amichne.kast.protocol.contract.WorkspacePreparationTiming
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceSharedDeadlineTest {
    @Test
    fun `continuing observation cannot accept a replacement project incarnation`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val script =
            WorkspaceRecoveryNativeFixture.Script(
                indefiniteRenewal(fixture).dropLast(1) +
                    WorkspaceRecoveryNativeFixture.Step(fixture.status(2)) {
                        IdeLifecycleResult.Opened(fixture.target.copy(project = fixture.id(4).value.toString()))
                    }
            )
        var nextId = 0
        val owner =
            WorkspacePreparations(
                this,
                script::exchange,
                budget = Duration.INFINITE,
                newId = { fixture.id(++nextId) },
                nanoTime = { currentTime * 1_000_000 },
            )
        try {
            val entry = owner.prepare(fixture.root).recoveryEntry()
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.STALE_PROJECT), entry.state.value)
            script.assertDrained()
        } finally {
            owner.close()
        }
    }

    @Test
    fun `bounded waiting charges shared expiry and inspection against the original allowance`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val script = WorkspaceRecoveryNativeFixture.Script(boundedRenewal(fixture))
        val requests = mutableListOf<WorkspaceLifecycleRequest>()
        val events = mutableListOf<WorkspacePreparationActivity>()
        var nextId = 0
        val owner =
            WorkspacePreparations(
                this,
                {
                    requests += it
                    script.exchange(it)
                },
                WorkspacePreparationObserver(events::add),
                budget = 1.seconds,
                newId = { fixture.id(++nextId) },
                nanoTime = { currentTime * 1_000_000 },
            )
        try {
            val entry = owner.prepare(fixture.root).recoveryEntry()
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(
                WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED),
                entry.state.value,
            )
            assertEquals(
                listOf(1000L, 700L),
                requests.filterIsInstance<WorkspaceLifecycleRequest.Open>().map { it.preparationMillis },
            )
            assertEquals(1000, events.last().preparation?.elapsedMillis)
            assertEquals(1000, events.last().preparation?.limitMillis)
            script.assertDrained()
        } finally {
            owner.close()
        }
    }

    @Test
    fun `real failures and unattributed expiry never start another native observation`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        for (failure in
            listOf(
                sharedDeadline().copy(reason = IdeLifecycleFailure.IMPORT_FAILED),
                sharedDeadline().copy(reason = IdeLifecycleFailure.CANCELLED),
                sharedDeadline().copy(preparation = null),
            )) {
            val script = WorkspaceRecoveryNativeFixture.Script(terminalSteps(fixture, failure))
            val owner =
                WorkspacePreparations(
                    this,
                    script::exchange,
                    budget = Duration.INFINITE,
                    newId = { fixture.id(1) },
                    nanoTime = { currentTime * 1_000_000 },
                )
            try {
                val entry = owner.prepare(fixture.root).recoveryEntry()
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(
                    WorkspacePreparationOutcome.Blocked(failure.reason, failure.preparation),
                    entry.state.value,
                )
                script.assertDrained()
            } finally {
                owner.close()
            }
        }
    }

    @Test
    fun `shared expiry stops when the editor closes the host changes or the project disappears`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        for ((inspection, expected) in unavailableContinuations(fixture)) {
            val script =
                WorkspaceRecoveryNativeFixture.Script(
                    terminalSteps(fixture, sharedDeadline()) +
                        WorkspaceRecoveryNativeFixture.Step(WorkspaceLifecycleRequest.Inspect) { inspection }
                )
            val owner =
                WorkspacePreparations(
                    this,
                    script::exchange,
                    budget = Duration.INFINITE,
                    newId = { fixture.id(1) },
                    nanoTime = { currentTime * 1_000_000 },
                )
            try {
                val entry = owner.prepare(fixture.root).recoveryEntry()
                runCurrent()
                advanceTimeBy(100)
                runCurrent()
                assertEquals(expected, entry.state.value)
                script.assertDrained()
            } finally {
                owner.close()
            }
        }
    }

    @Test
    fun `continuing observation cannot reuse a terminal native request identity`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val script = WorkspaceRecoveryNativeFixture.Script(indefiniteRenewal(fixture).take(3))
        val owner =
            WorkspacePreparations(
                this,
                script::exchange,
                budget = Duration.INFINITE,
                newId = { fixture.id(1) },
                nanoTime = { currentTime * 1_000_000 },
            )
        try {
            val entry = owner.prepare(fixture.root).recoveryEntry()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(
                WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.IDENTITY_REJECTED),
                entry.state.value,
            )
            script.assertDrained()
        } finally {
            owner.close()
        }
    }

    @Test
    fun `indefinite waiting continues after an earlier shared native deadline on the same live workspace`() = runTest {
        val fixture = WorkspaceRecoveryNativeFixture()
        val script = WorkspaceRecoveryNativeFixture.Script(indefiniteRenewal(fixture))
        val events = mutableListOf<WorkspacePreparationActivity>()
        var nextId = 0
        val owner =
            WorkspacePreparations(
                this,
                script::exchange,
                WorkspacePreparationObserver(events::add),
                budget = Duration.INFINITE,
                newId = { fixture.id(++nextId) },
                nanoTime = { currentTime * 1_000_000 },
            )
        try {
            val entry = owner.prepare(fixture.root).recoveryEntry()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertTrue(entry.state.value is WorkspacePreparationOutcome.Pending, entry.state.value.toString())
            advanceTimeBy(100)
            runCurrent()
            assertTrue(entry.state.value is WorkspacePreparationOutcome.Complete)
            assertEquals(2, nextId)
            val renewal = events.single { it.outcome is WorkspacePreparationActivityOutcome.WaitingAfterSharedDeadline }
            val evidence = renewal.outcome as WorkspacePreparationActivityOutcome.WaitingAfterSharedDeadline
            assertEquals(fixture.id(1).value.toString(), evidence.previousRequestId)
            assertEquals(fixture.id(2).value.toString(), evidence.nextRequestId)
            assertEquals(100, evidence.expired.limitMillis)
            assertEquals(
                io.github.amichne.kast.protocol.contract.WorkspacePreparationWaitMode.INDEFINITE,
                renewal.preparation?.waitMode,
            )
            script.assertDrained()
        } finally {
            owner.close()
        }
    }
}

private fun terminalSteps(fixture: WorkspaceRecoveryNativeFixture, result: IdeLifecycleResult) =
    listOf(
        WorkspaceRecoveryNativeFixture.Step(fixture.open(1).copy(preparationMillis = null)) { fixture.pending(1) },
        WorkspaceRecoveryNativeFixture.Step(fixture.status(1)) { result },
    )

private fun boundedRenewal(fixture: WorkspaceRecoveryNativeFixture) =
    listOf(
        WorkspaceRecoveryNativeFixture.Step(fixture.open(1)) { fixture.pending(1) },
        WorkspaceRecoveryNativeFixture.Step(fixture.status(1)) { sharedDeadline() },
        WorkspaceRecoveryNativeFixture.Step(WorkspaceLifecycleRequest.Inspect) {
            delay(200)
            fixture.inspected()
        },
        WorkspaceRecoveryNativeFixture.Step(fixture.open(2)) { fixture.pending(2) },
    ) + List(6) { WorkspaceRecoveryNativeFixture.Step(fixture.status(2)) { fixture.pending(2) } }

private fun unavailableContinuations(
    fixture: WorkspaceRecoveryNativeFixture
): List<Pair<IdeLifecycleResult, WorkspacePreparationOutcome>> =
    listOf(
        IdeLifecycleResult.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE) to
            WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.HOST_UNAVAILABLE),
        fixture.inspected().copy(host = fixture.id(3).value.toString()) to
            WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.WRONG_HOST),
        fixture.inspected().copy(projects = emptyList()) to
            WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.ROOT_REJECTED),
    )

private fun indefiniteRenewal(fixture: WorkspaceRecoveryNativeFixture): List<WorkspaceRecoveryNativeFixture.Step> =
    listOf(
        WorkspaceRecoveryNativeFixture.Step(fixture.open(1).copy(preparationMillis = null)) { fixture.pending(1) },
        WorkspaceRecoveryNativeFixture.Step(fixture.status(1)) { sharedDeadline() },
        WorkspaceRecoveryNativeFixture.Step(WorkspaceLifecycleRequest.Inspect) { fixture.inspected() },
        WorkspaceRecoveryNativeFixture.Step(fixture.open(2).copy(preparationMillis = null)) { fixture.pending(2) },
        WorkspaceRecoveryNativeFixture.Step(fixture.status(2)) { IdeLifecycleResult.Opened(fixture.target) },
    )

private fun sharedDeadline() =
    IdeLifecycleResult.Blocked(
        IdeLifecycleFailure.DEADLINE_EXCEEDED,
        WorkspacePreparationTiming(
            IdeLifecycleStage.IMPORTING,
            100,
            100,
            0,
            WorkspacePreparationDeadlineOwner.NATIVE_PREPARATION,
        ),
    )
