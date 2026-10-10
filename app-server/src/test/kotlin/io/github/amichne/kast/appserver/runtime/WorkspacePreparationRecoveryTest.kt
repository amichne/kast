@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Script
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Step
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Terminal preparation history never substitutes for current native admission. */
class WorkspacePreparationRecoveryTest {
    private val fixture = WorkspaceRecoveryNativeFixture()

    @Test
    fun `every blocked terminal can be freshly admitted on later authorized demand without rewriting history`() =
        runTest {
            for (reason in IdeLifecycleFailure.entries) {
                val script =
                    Script(
                        listOf(
                            Step(fixture.open(1)) { IdeLifecycleResult.Blocked(reason) },
                            Step(WorkspaceLifecycleRequest.Inspect) { fixture.inspected() },
                            Step(fixture.open(2)) { IdeLifecycleResult.Opened(fixture.target) },
                        )
                    )
                var issued = 0
                val preparations =
                    WorkspacePreparations(this, script::exchange, capacity = 1, newId = { fixture.id(++issued) })
                try {
                    val original = preparations.prepare(fixture.root).recoveryEntry()
                    runCurrent()
                    val held = original.state.value
                    val retried = preparations.prepareForDemand(fixture.root).recoveryEntry()
                    assertEquals(fixture.id(2), retried.id, reason.toString())
                    assertSame(retried, preparations.prepareForDemand(fixture.root).recoveryEntry())
                    runCurrent()
                    assertEquals(
                        fixture.target,
                        (retried.state.value as WorkspacePreparationOutcome.Complete).workspace.target,
                    )
                    assertEquals(WorkspacePreparationOutcome.Blocked(reason), held)
                    assertSame(held, original.state.value)
                    script.assertDrained()
                } finally {
                    preparations.close()
                }
            }
        }

    @Test
    fun `rejected response and request identity records do not become permanent preparation authority`() = runTest {
        val rejections =
            listOf(
                IdeLifecycleResult.Opened(fixture.target.copy(root = "/foreign")) to
                    WorkspacePreparationFailure.RESPONSE_REJECTED,
                IdeLifecycleResult.Pending("foreign-request", IdeLifecycleStage.IMPORTING, fixture.target.host) to
                    WorkspacePreparationFailure.REQUEST_ID_MISMATCH,
            )
        for ((response, failure) in rejections) {
            val script =
                Script(
                    listOf(
                        Step(fixture.open(1)) { response },
                        Step(WorkspaceLifecycleRequest.Inspect) { fixture.inspected() },
                        Step(fixture.open(2)) { IdeLifecycleResult.Opened(fixture.target) },
                    )
                )
            var issued = 0
            val preparations =
                WorkspacePreparations(this, script::exchange, capacity = 1, newId = { fixture.id(++issued) })
            try {
                val original = preparations.prepare(fixture.root).recoveryEntry()
                runCurrent()
                assertEquals(WorkspacePreparationOutcome.Rejected(failure), original.state.value)
                val retried = preparations.prepareForDemand(fixture.root).recoveryEntry()
                runCurrent()
                assertEquals(
                    fixture.target,
                    (retried.state.value as WorkspacePreparationOutcome.Complete).workspace.target,
                )
                assertEquals(WorkspacePreparationOutcome.Rejected(failure), original.state.value)
                script.assertDrained()
            } finally {
                preparations.close()
            }
        }
    }
}
