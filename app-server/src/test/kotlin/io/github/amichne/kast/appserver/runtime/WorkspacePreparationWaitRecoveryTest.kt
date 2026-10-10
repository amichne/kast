@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Script
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Step
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class WorkspacePreparationWaitRecoveryTest {
    @Test
    fun `expired wait retries through owner and joins unresolved import until fresh native completion`() = runTest {
        val case = WaitRecoveryCase(this)
        try {
            val original = case.preparations.prepare(case.fixture.root).recoveryEntry()
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            case.assertTimedOut(original)
            val cancelledWaiter = async { case.query() }
            val survivingWaiter = async { case.query() }
            runCurrent()
            val retry = case.preparations.prepareForDemand(case.fixture.root).recoveryEntry()
            assertEquals(case.fixture.id(2), retry.id)
            assertEquals(WorkspacePreparationOutcome.Pending(IdeLifecycleStage.IMPORTING), retry.state.value)
            cancelledWaiter.cancel()
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            assertSame(retry, case.preparations.prepareForDemand(case.fixture.root).recoveryEntry())
            assertFalse(survivingWaiter.isCompleted)
            assertEquals(0, case.queries, "unresolved native work cannot establish admission")
            advanceTimeBy(50)
            runCurrent()
            assertEquals(WorkspaceDemandResult.Native(case.fixture.native), survivingWaiter.await())
            assertEquals(1, case.queries)
            assertEquals(2, case.issued, "waiter cancellation must not create another native demand")
            case.assertTimedOut(original)
            case.script.assertDrained()
        } finally {
            case.preparations.close()
        }
    }

    private class WaitRecoveryCase(scope: TestScope) {
        val fixture = WorkspaceRecoveryNativeFixture()
        val script =
            Script(
                listOf(
                    Step(fixture.open(1)) { awaitCancellation() },
                    Step(WorkspaceLifecycleRequest.Inspect) { fixture.inspected() },
                    Step(fixture.open(2)) { fixture.pending(2) },
                    Step(fixture.status(2)) { fixture.pending(2) },
                    Step(fixture.status(2)) { IdeLifecycleResult.Opened(fixture.target) },
                )
            )
        var issued = 0
            private set

        var queries = 0
            private set

        val preparations =
            WorkspacePreparations(
                scope,
                script::exchange,
                capacity = 1,
                budget = 200.milliseconds,
                interval = 50.milliseconds,
                newId = { fixture.id(++issued) },
            )
        private val demand =
            PreparedWorkspaceDemand(preparations, { fixture.inspected() }) { workspace, operation ->
                queries++
                assertEquals(fixture.target, workspace.target)
                assertEquals(fixture.readOperation, operation)
                fixture.native
            }

        suspend fun query() = demand.query(fixture.root, fixture.readOperation)

        fun assertTimedOut(entry: WorkspacePreparation) =
            assertEquals(
                WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED),
                entry.state.value,
            )
    }
}
