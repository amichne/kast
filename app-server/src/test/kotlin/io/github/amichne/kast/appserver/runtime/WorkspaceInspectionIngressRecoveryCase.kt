@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.core.BrokerCallId
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.BrokerThreadId
import io.github.amichne.kast.appserver.core.BrokerTurnId
import io.github.amichne.kast.appserver.protocol.codex.InvocationCertainty
import io.github.amichne.kast.appserver.protocol.codex.ProtocolRouting
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.OperationEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertInstanceOf

/** Production classification and projection surround an explicit recording transport effect port. */
internal class WorkspaceInspectionIngressRecoveryCase(private val scope: TestScope, effect: BrokerOperationEffect) {
    private val ingress = WorkspaceInspectionIngressFixture()
    private val workspace = (BrokerWorkspaceId.parse("a".repeat(64)) as Refinement.Refined).value
    private val connection = checkNotNull(ClientConnectionId.admit("00000000-0000-0000-0000-000000000003"))
    private val thread = checkNotNull(BrokerThreadId.admit("thread"))
    private val policy = (WorkspaceExecutionPolicy.admit(2, 5_000, 10_000) as Refinement.Refined).value
    private val lane = WorkspaceExecution(scope, policy, scope.testTimeSource)
    private val firstIdentity = identity("first")
    private val retire = CompletableDeferred<Unit>()
    private val retiring = CompletableDeferred<Unit>()
    private val entered = CompletableDeferred<Unit>()
    private val transport = RecordingInspectionTransport(ingress.params)
    private var semanticCalls = 0
    private val first =
        lane.submit(firstIdentity, access = WorkspaceExecutionAccess.Operation(effect)) {
            semanticCalls++
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    retiring.complete(Unit)
                    retire.await()
                }
            }
        }

    suspend fun inspect(
        call: String,
        expectedState: WorkspaceExecutionLaneState,
        expectedAction: WorkspaceExecutionNextAction,
    ) {
        assertTrue(entered.isCompleted)
        val access = workspaceInvocationAccess(ingress.params, ingress.broker, ingress.definitions)
        val request =
            lane.submit(identity(call), access = access) {
                val nativeReply = transport.dispatch(ingress.params, call)
                projectWorkspaceInspection(
                    nativeReply,
                    access,
                    { lane.observation(workspace) },
                    maximumBytes = 10_000,
                    maximumResultBytes = ingress.broker.limits.maximumToolResultBytes,
                ) { failure ->
                    error("qualified inspection projection rejected: $failure")
                }
            }
        scope.runCurrent()
        assertTrue(request.isCompleted, "qualified inspection must complete before unresolved provider retirement")
        val completed = assertInstanceOf<WorkspaceExecutionResult.Completed>(request.await())
        val projected = assertInstanceOf<ProtocolRouting.ReplyUpstream>(completed.routing)
        transport.send(projected)
        assertProjection(projected, call, expectedState, expectedAction)
        assertEquals(1, semanticCalls, "inspection cannot release or replay semantic execution")
    }

    private fun assertProjection(
        reply: ProtocolRouting.ReplyUpstream,
        call: String,
        expectedState: WorkspaceExecutionLaneState,
        expectedAction: WorkspaceExecutionNextAction,
    ) {
        assertEquals(InvocationCertainty.KNOWN, reply.certainty)
        val document = Json.parseToJsonElement(reply.message).jsonObject
        assertEquals(call, document.getValue("id").jsonPrimitive.content)
        val content = document.getValue("result").jsonObject.getValue("contentItems").jsonArray
        assertEquals(2, content.size)
        assertEquals(transport.nativeContent, content.first().jsonObject.getValue("text").jsonPrimitive.content)
        val appended =
            Json.parseToJsonElement(content.last().jsonObject.getValue("text").jsonPrimitive.content).jsonObject
        assertEquals("not_observed_by_broker", appended.getValue("nativeAuthority").jsonPrimitive.content)
        val snapshot = appended.getValue("workspaceExecution").jsonObject
        assertEquals(workspace.value, snapshot.getValue("workspaceId").jsonPrimitive.content)
        assertEquals(expectedState.name.lowercase(), snapshot.getValue("state").jsonPrimitive.content)
        assertEquals(expectedAction.name.lowercase(), snapshot.getValue("nextAction").jsonPrimitive.content)
        if (expectedState != WorkspaceExecutionLaneState.IDLE) {
            val blocked = snapshot.getValue("blockedBy").jsonObject
            assertEquals(firstIdentity.call.value, blocked.getValue("callId").jsonPrimitive.content)
            assertEquals(
                firstIdentity.connection.value,
                blocked.getValue("connectionId").jsonPrimitive.content,
            )
        }
    }

    fun cancel() {
        lane.cancel(workspace, firstIdentity.thread, firstIdentity.turn)
        scope.runCurrent()
        assertTrue(retiring.isCompleted)
        assertFalse(first.isCompleted)
        assertFalse(retire.isCompleted)
    }

    suspend fun settle(mutationFence: Boolean) {
        retire.complete(Unit)
        scope.runCurrent()
        assertEquals(
            WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_OUTCOME_UNCERTAIN),
            first.await(),
        )
        val read = WorkspaceExecutionAccess.Operation(BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ))
        val fresh =
            lane.submit(identity("fresh"), access = read) {
                error("this case must not send another semantic operation")
            }
        if (mutationFence)
            assertEquals(
                WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.WORKSPACE_RECOVERY_REQUIRED),
                fresh.await(),
            )
        else {
            // Retirement restores capacity; cancel the fresh request before its body is allowed to run.
            lane.cancel(workspace, thread, identity("fresh").turn)
            scope.runCurrent()
            assertEquals(
                WorkspaceExecutionResult.Rejected(WorkspaceExecutionFailure.CANCELLED_BEFORE_EXECUTION),
                fresh.await(),
            )
        }
    }

    fun assertDelivered() {
        assertEquals(3, transport.dispatched)
        assertEquals(3, transport.delivered.size)
        assertEquals(1, semanticCalls)
    }

    suspend fun close() {
        lane.cancel(workspace, firstIdentity.thread, firstIdentity.turn)
        retire.complete(Unit)
        scope.runCurrent()
        first.await()
    }

    private fun identity(call: String) =
        WorkspaceExecutionIdentity(
            workspace,
            connection,
            thread,
            checkNotNull(BrokerTurnId.admit("turn-$call")),
            checkNotNull(BrokerCallId.admit(call)),
        )

    private class RecordingInspectionTransport(private val qualifiedParams: JsonObject) {
        private val json = Json { encodeDefaults = true }
        val nativeContent = json.encodeToString(WorkspaceRecoveryNativeFixture().inspected())
        var dispatched = 0
            private set

        val delivered = mutableListOf<ProtocolRouting.ReplyUpstream>()

        fun dispatch(params: JsonObject, call: String): ProtocolRouting.ReplyUpstream {
            assertEquals(qualifiedParams, params)
            dispatched++
            return ProtocolRouting.ReplyUpstream(
                json.encodeToString(NativeReply(call, NativeResult(contentItems = listOf(NativeText(nativeContent))))),
                InvocationCertainty.KNOWN,
            )
        }

        fun send(reply: ProtocolRouting.ReplyUpstream) {
            delivered += reply
        }
    }

    @Serializable private data class NativeReply(val id: String, val result: NativeResult)

    @Serializable private data class NativeResult(val contentItems: List<NativeText>, val success: Boolean = true)

    @Serializable private data class NativeText(val text: String, val type: String = "inputText")
}
