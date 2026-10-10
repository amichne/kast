@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.protocol.codex.codexToolCancellationPresentation
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Script
import io.github.amichne.kast.appserver.runtime.WorkspaceRecoveryNativeFixture.Step
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.registry.OperationEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

/** Case-owned observations and retirement gate; production owns all admission and transition decisions. */
internal class WorkspaceDemandSettlementRecoveryCase(private val scope: TestScope, effect: OperationEffect) {
    private val fixture = WorkspaceRecoveryNativeFixture()
    private val script =
        Script(
            listOf(
                Step(fixture.open(1)) { IdeLifecycleResult.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED) },
                Step(WorkspaceLifecycleRequest.Inspect) { fixture.inspected() },
                Step(fixture.open(2)) { IdeLifecycleResult.Opened(fixture.target) },
            )
        )
    private var issued = 0
    private val preparations =
        WorkspacePreparations(
            scope,
            script::exchange,
            capacity = 1,
            newId = { fixture.id(++issued) },
        )
    private val entered = CompletableDeferred<Unit>()
    private val retiring = CompletableDeferred<Unit>()
    private val retire = CompletableDeferred<Unit>()
    private var queries = 0
    private var inspections = 0
    private var active = 0
    private val demand =
        PreparedWorkspaceDemand(
            preparations,
            {
                inspections++
                fixture.inspected()
            },
            ::invoke,
        )
    private val workspace = (BrokerWorkspaceId.parse("a".repeat(64)) as Refinement.Refined).value
    private val connection = checkNotNull(ClientConnectionId.admit("00000000-0000-0000-0000-000000000003"))
    private val thread = checkNotNull(BrokerThreadId.admit("thread"))
    private val policy = (WorkspaceExecutionPolicy.admit(2, 5_000, 10_000) as Refinement.Refined).value
    private val lane = WorkspaceExecution(scope, policy, scope.testTimeSource)
    private val access = WorkspaceExecutionAccess.Operation(BrokerOperationEffect.Canonical(effect))
    private val readAccess =
        WorkspaceExecutionAccess.Operation(BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ))
    private val firstIdentity = identity("first")
    private val delivery = CancellationReplyPort()

    data class Executing(
        val first: Deferred<WorkspaceExecutionResult>,
        val fresh: Deferred<WorkspaceExecutionResult>,
        val ready: WorkspacePreparation,
        val held: WorkspacePreparationOutcome.Complete,
    )

    suspend fun begin(): Executing {
        val failed = preparations.prepare(fixture.root).recoveryEntry()
        scope.runCurrent()
        assertEquals(WorkspacePreparationOutcome.Blocked(IdeLifecycleFailure.DEADLINE_EXCEEDED), failed.state.value)
        val first =
            lane.submit(firstIdentity, access = access) {
                assertEquals(WorkspaceDemandResult.Native(fixture.native), query())
                fixture.reply
            }
        scope.runCurrent()
        assertTrue(
            entered.isCompleted,
            "later demand must replace the cached terminal failure before semantic execution",
        )
        val ready = preparations.prepareForDemand(fixture.root).recoveryEntry()
        val held = ready.state.value as WorkspacePreparationOutcome.Complete
        val fresh =
            lane.submit(identity("fresh"), access = readAccess) {
                assertEquals(WorkspaceDemandResult.Native(fixture.native), query())
                fixture.reply
            }
        return Executing(first, fresh, ready, held)
    }

    suspend fun cancelAndInspect(executing: Executing) {
        lane.cancel(workspace, firstIdentity.thread, firstIdentity.turn)
        scope.runCurrent()
        assertTrue(retiring.isCompleted)
        assertFalse(executing.first.isCompleted)
        assertFalse(executing.fresh.isCompleted)
        assertEquals(1, queries)
        assertEquals(CancellationReplyPort.Delivery.IO_UNAVAILABLE, delivery.sendCancellation())
        var observed: WorkspaceExecutionLaneSnapshot? = null
        val inspection =
            lane.submit(identity("inspect"), access = WorkspaceExecutionAccess.Observation) {
                observed = lane.observation(workspace)
                fixture.reply
            }
        assertEquals(WorkspaceExecutionResult.Completed(fixture.reply), inspection.await())
        assertEquals(firstIdentity.document(), observed?.blockedBy)
        assertEquals(WorkspaceExecutionNextAction.OBSERVE_PROVIDER_SETTLEMENT, observed?.nextAction)
        assertSame(
            executing.held,
            executing.ready.state.value,
            "cancellation and delivery loss cannot discard current model evidence",
        )
    }

    suspend fun settle(executing: Executing, mutationFence: Boolean) {
        retire.complete(Unit)
        scope.runCurrent()
        assertEquals(
            WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_OUTCOME_UNCERTAIN),
            executing.first.await(),
        )
        if (mutationFence) assertWriteFence(executing) else assertReadRecovery(executing)
        assertEquals(2, issued, "native readiness after retirement must reuse the owner-admitted incarnation")
        assertSame(executing.held, executing.ready.state.value)
        assertEquals(0, active)
        assertEquals(1, delivery.calls)
        script.assertDrained()
    }

    private suspend fun assertWriteFence(executing: Executing) {
        assertEquals(
            WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_RECOVERY_REQUIRED),
            executing.fresh.await(),
        )
        assertEquals(1, queries)
        assertEquals(WorkspaceExecutionNextAction.RECONCILE_UNCERTAIN_MUTATION, lane.observation(workspace).nextAction)
        val inspection =
            lane.submit(identity("inspect-fenced"), access = WorkspaceExecutionAccess.Observation) {
                fixture.reply
            }
        assertEquals(WorkspaceExecutionResult.Completed(fixture.reply), inspection.await())
    }

    private suspend fun assertReadRecovery(executing: Executing) {
        assertEquals(WorkspaceExecutionResult.Completed(fixture.reply), executing.fresh.await())
        assertEquals(2, queries)
        assertEquals(2, inspections, "each semantic demand obtains a current identity observation")
        assertEquals(WorkspaceExecutionLaneState.IDLE, lane.observation(workspace).state)
    }

    private suspend fun invoke(prepared: PreparedWorkspace, operation: ExistingIdeOperation) =
        if (++queries == 1) {
            assertInvocation(prepared, operation)
            active++
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    retiring.complete(Unit)
                    retire.await()
                    active--
                }
            }
        } else {
            assertInvocation(prepared, operation)
            fixture.native
        }

    private fun assertInvocation(prepared: PreparedWorkspace, operation: ExistingIdeOperation) {
        assertEquals(fixture.target, prepared.target)
        assertEquals(fixture.readOperation, operation)
        assertEquals(0, active, "provider has not retired")
    }

    private suspend fun query() = demand.query(fixture.root, fixture.readOperation)

    private fun identity(call: String) =
        WorkspaceExecutionIdentity(
            workspace,
            connection,
            thread,
            checkNotNull(BrokerTurnId.admit("turn-$call")),
            checkNotNull(BrokerCallId.admit(call)),
        )

    suspend fun close() {
        lane.cancel(workspace, firstIdentity.thread, firstIdentity.turn)
        retire.complete(Unit)
        scope.runCurrent()
        preparations.close()
    }

    private class CancellationReplyPort {
        enum class Delivery {
            IO_UNAVAILABLE
        }

        var calls = 0
            private set

        fun sendCancellation(): Delivery {
            calls++
            assertEquals(1, calls)
            assertFalse(codexToolCancellationPresentation().success)
            return Delivery.IO_UNAVAILABLE
        }
    }
}
