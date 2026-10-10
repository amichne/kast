@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/** The production lane consumes only detached identity, effect metadata, and provider termination here. */
class WorkspaceExecutionSettlementPropertyTest {
    @Test
    fun `detached workspace identity admits only canonical digest syntax`() {
        val raw = "0123456789abcdef".repeat(4)
        assertEquals(raw, assertInstanceOf<Refinement.Refined<BrokerWorkspaceId>>(BrokerWorkspaceId.parse(raw)).value.value)
        for (invalid in listOf("", raw.dropLast(1), raw + "0", raw.uppercase(), "g".repeat(64), " " + raw)) {
            assertEquals(Refinement.Rejected(BrokerWorkspaceId.Failure.INVALID), BrokerWorkspaceId.parse(invalid))
        }
    }

    @Test
    fun `cancelled and timed out reads retain capacity until proven provider retirement`() = runTest {
        for (stop in listOf(Event.CANCEL, Event.DEADLINE)) {
            val fixture = Fixture(this, readAccess)
            try {
                runCurrent()
                val queued = fixture.fresh("queued")
                fixture.apply(stop)
                runCurrent()
                assertTrue(fixture.retiring.isCompleted)
                assertFalse(fixture.first.isCompleted)
                assertFalse(queued.isCompleted)
                fixture.assertPrefix()
                fixture.inspect()
                fixture.retire.complete(Unit)
                runCurrent()
                assertEquals(WorkspaceExecutionResult.Rejected(
                    if (stop == Event.CANCEL) WorkspaceExecutionFailure.WORKSPACE_OUTCOME_UNCERTAIN
                    else WorkspaceExecutionFailure.WORKSPACE_INTERACTION_TIMED_OUT,
                ), fixture.first.await())
                assertEquals(completed, queued.await())
                assertEquals(completed, fixture.fresh("after-retirement").await())
                fixture.assertPrefix()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `all uncertain writing effects and unknown effects remain fenced while inspection stays reachable`() = runTest {
        val effects = listOf(
            OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
            OperationEffect.INTELLIJ_WRITE,
            OperationEffect.FILESYSTEM_WRITE,
            OperationEffect.PERSISTENCE_WRITE,
            OperationEffect.WORKSPACE_MODEL_WRITE,
            OperationEffect.PROCESS_CONTROL,
        ).map { BrokerOperationEffect.Canonical(it) } + BrokerOperationEffect.Unknown
        for (effect in effects) {
            val fixture = Fixture(this, WorkspaceExecutionAccess.Operation(effect))
            try {
                runCurrent()
                val queued = fixture.fresh("queued")
                fixture.apply(Event.CANCEL)
                runCurrent()
                fixture.inspect()
                assertFalse(queued.isCompleted)
                fixture.retire.complete(Unit)
                runCurrent()
                assertEquals(recoveryRequired, queued.await(), effect.toString())
                assertEquals(recoveryRequired, fixture.fresh("later").await(), effect.toString())
                val fenced = fixture.inspect()
                assertEquals(WorkspaceExecutionLaneState.RECOVERY_REQUIRED, fenced.state)
                assertEquals(fixture.firstIdentity.document(), fenced.blockedBy)
                assertEquals(WorkspaceExecutionNextAction.RECONCILE_UNCERTAIN_MUTATION, fenced.nextAction)
                assertEquals(listOf("first"), fixture.calls)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `uncertain terminal replies on reads require new native admission without permanent fencing`() = runTest {
        val fixture = Fixture(this, readAccess, start = false)
        val uncertain = ProtocolRouting.ReplyUpstream("detached-terminal", InvocationCertainty.UNCERTAIN)
        val result = fixture.lane.submit(fixture.identity("reply-failed"), access = readAccess) { uncertain }
        assertEquals(WorkspaceExecutionResult.Completed(uncertain), result.await())
        assertEquals(WorkspaceExecutionNextAction.REOBSERVE_NATIVE_AUTHORITY, fixture.inspect().nextAction)
        assertEquals(completed, fixture.fresh("fresh").await())
    }

    @Test
    fun `cancellation before execution cannot create an uncertain mutation fence`() = runTest {
        val fixture = Fixture(this, WorkspaceExecutionAccess.Operation(BrokerOperationEffect.Unknown))
        fixture.cancel()
        runCurrent()
        assertEquals(WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.CANCELLED_BEFORE_EXECUTION), fixture.first.await())
        assertEquals(emptyList<String>(), fixture.calls)
        assertEquals(completed, fixture.fresh("fresh").await())
        assertEquals(WorkspaceExecutionLaneState.IDLE, fixture.inspect().state)
    }

    @Test
    fun `every bounded event prefix preserves settlement capacity and observation reachability`() = runTest {
        // Exhaust every length-three word: repeated demands, repeated cancellation, deadlines, and early retirement gates.
        for (events in sequences(Event.entries, 3)) {
            val fixture = Fixture(this, readAccess)
            try {
                runCurrent()
                fixture.assertPrefix()
                events.forEach { event ->
                    fixture.apply(event)
                    runCurrent()
                    fixture.assertPrefix()
                    fixture.inspect()
                }
                fixture.cancel()
                fixture.retire.complete(Unit)
                runCurrent()
                fixture.assertPrefix()
                assertEquals(completed, fixture.fresh("eventual-fresh").await(), events.toString())
            } finally {
                fixture.close()
            }
        }
    }

    private enum class Event { CANCEL, DEADLINE, QUEUE, INSPECT, RETIRE }

    private fun sequences(events: List<Event>, length: Int): List<List<Event>> =
        if (length == 0) listOf(emptyList())
        else sequences(events, length - 1).flatMap { prefix -> events.map { prefix + it } }

    private class Fixture(
        private val scope: TestScope,
        private val access: WorkspaceExecutionAccess,
        start: Boolean = true,
    ) {
        private val workspace = assertInstanceOf<Refinement.Refined<BrokerWorkspaceId>>(BrokerWorkspaceId.parse("a".repeat(64))).value
        private val connection = requireNotNull(ClientConnectionId.admit("00000000-0000-0000-0000-000000000001"))
        private val thread = requireNotNull(BrokerThreadId.admit("thread"))
        private val policy = assertInstanceOf<Refinement.Refined<WorkspaceExecutionPolicy>>(
            WorkspaceExecutionPolicy.admit(4, 5_000, 10_000),
        ).value
        val lane = WorkspaceExecution(scope, policy, scope.testTimeSource)
        val firstIdentity = identity("first")
        val retiring = CompletableDeferred<Unit>()
        val retire = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        private var providerRetired = false
        private var active = 0
        private val pending = mutableListOf<Deferred<WorkspaceExecutionResult>>()
        val first = if (start) lane.submit(firstIdentity, limit, access) {
            calls += "first"
            active++
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    retiring.complete(Unit)
                    retire.await()
                    active--
                    providerRetired = true
                }
            }
        } else CompletableDeferred(completed)

        fun identity(call: String) = WorkspaceExecutionIdentity(workspace, connection, thread,
            requireNotNull(BrokerTurnId.admit("turn-$call")), requireNotNull(BrokerCallId.admit(call)))

        fun fresh(call: String): Deferred<WorkspaceExecutionResult> = lane.submit(identity(call), access = readAccess) {
            assertEquals(0, active, "native provider has not terminated")
            calls += call
            reply
        }.also(pending::add)

        fun cancel() = lane.cancel(workspace, firstIdentity.thread, firstIdentity.turn)

        suspend fun apply(event: Event) {
            when (event) {
                Event.CANCEL -> cancel()
                Event.DEADLINE -> scope.advanceTimeBy(100)
                Event.QUEUE -> fresh("queued-${pending.size}")
                Event.INSPECT -> inspect()
                Event.RETIRE -> retire.complete(Unit)
            }
        }

        suspend fun inspect(): WorkspaceExecutionLaneSnapshot {
            var observed: WorkspaceExecutionLaneSnapshot? = null
            val outcome = lane.submit(identity("inspect"), access = WorkspaceExecutionAccess.Observation) {
                observed = lane.observation(workspace)
                reply
            }
            assertEquals(completed, outcome.await(), "inspection must not enter semantic admission")
            return requireNotNull(observed)
        }

        fun assertPrefix() {
            val observation = lane.observation(workspace)
            assertEquals(workspace.value, observation.workspaceId)
            if (!providerRetired) {
                assertEquals(1, active)
                assertEquals(listOf("first"), calls)
                assertFalse(first.isCompleted)
                assertTrue(pending.none { it.isCompleted })
                assertEquals(WorkspaceExecutionLaneState.BUSY, observation.state)
                assertEquals(firstIdentity.document(), observation.blockedBy)
                assertEquals(WorkspaceExecutionNextAction.OBSERVE_PROVIDER_SETTLEMENT, observation.nextAction)
            } else {
                assertEquals(0, active)
                assertTrue(first.isCompleted)
                assertTrue(pending.all { it.isCompleted })
                assertEquals(WorkspaceExecutionLaneState.IDLE, observation.state)
                assertEquals(null, observation.blockedBy)
                assertEquals(WorkspaceExecutionNextAction.REOBSERVE_NATIVE_AUTHORITY, observation.nextAction)
            }
        }

        fun close() {
            cancel()
            retire.complete(Unit)
            scope.runCurrent()
        }
    }

    private companion object {
        val readAccess = WorkspaceExecutionAccess.Operation(BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ))
        val limit = assertInstanceOf<Refinement.Refined<ElapsedTimeLimitMillis>>(ElapsedTimeLimitMillis.parse(100)).value
        val reply = ProtocolRouting.ReplyUpstream("detached-result")
        val completed = WorkspaceExecutionResult.Completed(reply)
        val recoveryRequired = WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_RECOVERY_REQUIRED)
    }
}
