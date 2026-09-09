package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.core.*
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WorkspaceExecutionBudgetTest {
    @Test fun `queue policy admits published maximum and rejects overflow and inverted wait`() {
        val maximum = BrokerOperationalLimits.maximumWorkspaceQueued
        val admitted = WorkspaceExecutionPolicy.admit(maximum, 1, 1)
        assertTrue(admitted is Refinement.Refined)
        assertEquals(Refinement.Rejected(WorkspaceExecutionPolicyFailure.QUEUED_LIMIT_REJECTED), WorkspaceExecutionPolicy.admit(maximum + 1, 1, 1))
        assertEquals(Refinement.Rejected(WorkspaceExecutionPolicyFailure.WAIT_LIMIT_REJECTED), WorkspaceExecutionPolicy.admit(maximum, 2, 1))
        assertEquals(BrokerOperationalLimits.defaultWorkspaceQueued, WorkspaceExecutionPolicy.Default.maximumQueued)
        assertEquals(BrokerOperationalLimits.workspaceQueueWait, WorkspaceExecutionPolicy.Default.queueWait)
        assertEquals(BrokerOperationalLimits.workspaceInteraction, WorkspaceExecutionPolicy.Default.interaction)
    }

    @Test fun `selected operation allowance includes time already spent queued`(@TempDir directory: Path): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val policy = (WorkspaceExecutionPolicy.admit(2, 1_000, 1_000) as Refinement.Refined).value
        val executions = WorkspaceExecution(scope, policy)
        val workspace = BrokerWorkspaceId.derive(requireNotNull(CanonicalBrokerDirectory.admit(directory.toRealPath())))
        fun identity(call: String) = WorkspaceExecutionIdentity(workspace, ClientConnectionId.fresh(), requireNotNull(BrokerThreadId.admit("thread")), requireNotNull(BrokerTurnId.admit("turn")), requireNotNull(BrokerCallId.admit(call)))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = executions.submit(identity("first")) { entered.complete(Unit); release.await(); ProtocolRouting.ReplyUpstream("{}") }
        entered.await()
        val second = executions.submit(identity("second"), (ElapsedTimeLimitMillis.parse(100) as Refinement.Refined).value) { awaitCancellation() }
        try {
            delay(40); release.complete(Unit); first.await()
            val outcome = withTimeoutOrNull(300) { second.await() }
            assertNotNull(outcome, "selected operation allowance was reset to global workspace limit")
            assertEquals(WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_INTERACTION_TIMED_OUT), outcome)
            val events = executions.snapshot().getValue("events").jsonArray.map { it.jsonObject }
            val terminal = events.last { it.getValue("callId").jsonPrimitive.content == "second" }
            assertEquals("timed_out", terminal.getValue("outcome").jsonPrimitive.content)
            assertEquals("uncertain", terminal.getValue("certainty").jsonPrimitive.content)
            assertEquals("recovery_required", executions.snapshot().getValue("lanes").jsonArray.single().jsonObject.getValue("state").jsonPrimitive.content)
            assertEquals(100L, terminal.getValue("interactionLimitMillis").jsonPrimitive.long)
            assertEquals(0L, terminal.getValue("remainingMillis").jsonPrimitive.long)
            assertTrue(events.any { it.getValue("callId").jsonPrimitive.content == "first" && it.getValue("outcome").jsonPrimitive.content == "completed" })
        } finally { release.complete(Unit); scope.cancel() }
    }
}
