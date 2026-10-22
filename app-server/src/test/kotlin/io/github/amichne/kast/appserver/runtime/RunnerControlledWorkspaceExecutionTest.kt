@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.core.HostedToolDefinition
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolEmit
import io.github.amichne.kast.appserver.query.PublicToolQuerySymbols
import io.github.amichne.kast.appserver.query.PublicToolReferenceSource
import io.github.amichne.kast.appserver.query.PublicToolRunAction
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.registry.AgentToolName
import io.github.amichne.kast.protocol.registry.HostedApprovalPolicy
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RunnerControlledWorkspaceExecutionTest {
    @Test
    fun `schema admitted runner query outlives interaction limit while bounded query stops`() = runTest {
        val policy = (WorkspaceExecutionPolicy.admit(1, 100, 100) as Refinement.Refined).value
        val lane = WorkspaceExecution(this, policy, testTimeSource)
        val definition = queryDefinition()
        val effect = BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ)
        val input = queryInput(QueryExecutionModeDocument.RUNNER_CONTROLLED)
        val access = workspaceInvocationAccess(effect, definition, input)
        val runner =
            lane.submit(identity("runner"), access = access) {
                delay(200)
                ProtocolRouting.ReplyUpstream(Json.encodeToString(RunnerLaneReply()))
            }
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertFalse(runner.isCompleted, "The runner owns the semantic lifetime")
        advanceTimeBy(50)
        runCurrent()
        assertInstanceOf(WorkspaceExecutionResult.Completed::class.java, runner.await())
        val bounded =
            lane.submit(
                identity("bounded"),
                access =
                    workspaceInvocationAccess(
                        effect,
                        definition,
                        queryInput(QueryExecutionModeDocument.BOUNDED),
                    ),
            ) {
                delay(200)
                ProtocolRouting.ReplyUpstream(Json.encodeToString(RunnerLaneReply()))
            }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(
            WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_INTERACTION_TIMED_OUT),
            bounded.await(),
        )
    }

    @Test
    fun `caller cancellation stops a runner query without a completed result`() = runTest {
        val policy = (WorkspaceExecutionPolicy.admit(1, 100, 100) as Refinement.Refined).value
        val lane = WorkspaceExecution(this, policy, testTimeSource)
        val selected = identity("cancelled-runner")
        val access =
            workspaceInvocationAccess(
                BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ),
                queryDefinition(),
                queryInput(QueryExecutionModeDocument.RUNNER_CONTROLLED),
            )
        val runner = lane.submit(selected, access = access) { awaitCancellation() }
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertFalse(runner.isCompleted)
        lane.cancel(selected.workspace, selected.thread, selected.turn)
        runCurrent()
        assertEquals(
            WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_OUTCOME_UNCERTAIN),
            runner.await(),
        )
        assertEquals(WorkspaceExecutionLaneState.IDLE, lane.observation(selected.workspace).state)
    }

    @Test
    fun `a query cannot grant runner access to a write effect`() {
        val effect = BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_WRITE)
        val access =
            workspaceInvocationAccess(
                effect,
                queryDefinition(),
                queryInput(QueryExecutionModeDocument.RUNNER_CONTROLLED),
            )
        assertEquals(WorkspaceExecutionAccess.Operation(effect), access)
    }
}

private fun queryDefinition(): HostedToolDefinition {
    val schema = PublicToolContract.schema(PublicToolIdentity.QUERY_SYMBOLS)
    return HostedToolDefinition(
        CanonicalOperation.QUERY_RUN,
        (AgentToolName.parse("query_symbols") as Refinement.Refined).value,
        (ProtocolText.parse("Query compiler symbols") as Refinement.Refined).value,
        schema,
        schema,
        OperationEffect.INTELLIJ_READ,
        HostedApprovalPolicy.NONE,
        OperationExecutionBudget.SEMANTIC_READ,
        HostedToolLoading.EAGER,
    )
}

private fun queryInput(mode: QueryExecutionModeDocument) =
    Json.encodeToJsonElement(
        PublicToolQuerySymbols(
            PublicToolRunAction(
                source =
                    PublicToolReferenceSource(
                        (BoundedProtocolList.create(
                                listOf((ProtocolText.parse("exact:v2:opaque") as Refinement.Refined).value)
                            ) as Refinement.Refined)
                            .value
                    ),
                terminalStep = PublicToolEmit(),
                executionMode = mode,
            )
        )
    )

private fun identity(call: String): WorkspaceExecutionIdentity =
    WorkspaceExecutionIdentity(
        (BrokerWorkspaceId.parse("a".repeat(64)) as Refinement.Refined).value,
        checkNotNull(ClientConnectionId.admit("00000000-0000-0000-0000-000000000003")),
        checkNotNull(BrokerThreadId.admit("thread")),
        checkNotNull(BrokerTurnId.admit("turn")),
        checkNotNull(BrokerCallId.admit(call)),
    )

@Serializable private data class RunnerLaneReply(val status: String = "complete")
