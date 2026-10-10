@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Script
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Step
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class WorkspaceInspectionDeadlineRecoveryTest {
    @Test
    fun `inspection deadline retains history and later authorized demand never repeats uncertain semantic execution`() =
        runTest {
            val case = InspectionDeadlineCase(this)
            try {
                val original = case.preparations.prepare(case.fixture.root).recoveryEntry()
                runCurrent()
                val first = async { case.query() }
                runCurrent()
                advanceTimeBy(1000)
                runCurrent()
                val failure =
                    (first.await() as WorkspaceDemandResult.Rejected).failure as WorkspaceDemandFailure.Operation
                assertEquals(case.fixture.id(2), failure.id)
                assertEquals(
                    WorkspaceDemandCause.Preparation(WorkspacePreparationFailure.DEADLINE_EXCEEDED),
                    failure.cause,
                )
                val timedOut = case.preparations.observe(case.fixture.id(2)).recoveryEntry()
                val held = timedOut.state.value
                assertEquals(WorkspacePreparationOutcome.Rejected(WorkspacePreparationFailure.DEADLINE_EXCEEDED), held)
                case.assertNoAutomaticRetry()
                advanceTimeBy(1000)
                runCurrent()
                case.assertNoAutomaticRetry()
                val later = async { case.query() }
                runCurrent()
                assertEquals(WorkspaceDemandResult.Native(case.uncertainNative), later.await())
                case.assertFreshDemandOnce()
                assertSame(held, timedOut.state.value)
                assertEquals(
                    WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.PLUGIN_UNAVAILABLE),
                    original.state.value,
                )
            } finally {
                case.preparations.close()
            }
        }

    private class InspectionDeadlineCase(scope: TestScope) {
        val fixture = WorkspaceRecoveryNativeFixture()
        private val ids = ArrayDeque(listOf(fixture.id(1), fixture.id(2), fixture.id(3)))
        private val requests = mutableListOf<WorkspaceLifecycleRequest>()
        private val script =
            Script(
                listOf(
                    Step(fixture.open(1)) { IdeLifecycleResult.Blocked(IdeLifecycleFailure.PLUGIN_UNAVAILABLE) },
                    Step(WorkspaceLifecycleRequest.Inspect) { awaitCancellation() },
                    Step(WorkspaceLifecycleRequest.Inspect) { fixture.inspected() },
                    Step(fixture.open(3)) { IdeLifecycleResult.Opened(fixture.target) },
                )
            )
        val preparations =
            WorkspacePreparations(
                scope,
                { request ->
                    requests += request
                    script.exchange(request)
                },
                budget = 1.seconds,
                newId = { ids.removeFirst() },
            )
        private var inspections = 0
        private var semantics = 0
        val uncertainNative = ExistingIdeExchange.Rejected(ExistingIdeFailure.DEADLINE_EXCEEDED)
        private val demand =
            PreparedWorkspaceDemand(
                preparations,
                {
                    inspections++
                    fixture.inspected()
                },
            ) { workspace, operation ->
                semantics++
                assertEquals(fixture.target, workspace.target)
                assertEquals(ExistingIdeOperation.Status, operation)
                uncertainNative
            }

        suspend fun query() = demand.query(fixture.root, ExistingIdeOperation.Status)

        fun assertNoAutomaticRetry() {
            assertEquals(2, requests.size, "no automatic retry after the inspection deadline")
            assertEquals(0, semantics)
        }

        fun assertFreshDemandOnce() {
            assertEquals(1, semantics, "uncertain semantic execution cannot be replayed automatically")
            assertEquals(1, inspections)
            assertEquals(4, requests.size)
            assertEquals(
                listOf(
                    fixture.open(1),
                    WorkspaceLifecycleRequest.Inspect,
                    WorkspaceLifecycleRequest.Inspect,
                    fixture.open(3),
                ),
                requests,
            )
            assertEquals(0, ids.size)
            script.assertDrained()
        }
    }
}
