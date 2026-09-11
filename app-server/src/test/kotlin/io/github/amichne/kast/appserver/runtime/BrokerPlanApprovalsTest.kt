package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerInvocationContext
import io.github.amichne.kast.appserver.core.ObserverFileChange
import io.github.amichne.kast.appserver.core.ObserverFileChangeKind
import io.github.amichne.kast.appserver.protocol.codex.CodexOwnedSchema
import io.github.amichne.kast.appserver.protocol.codex.CodexProtocolContracts
import io.github.amichne.kast.appserver.protocol.codex.PlanApprovalItemCompletion
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerPlanApprovalsTest {
    @Test
    fun `unconfigured gateway fails without presenting an approval or issuing a grant`(@TempDir root: Path) = runTest {
        val fixture = Fixture(root, unavailable = true)
        assertEquals(
            Refinement.Rejected(HostedPlanApprovalFailure.UNAVAILABLE),
            fixture.manager.approve(fixture.controller, fixture.request),
        )
        assertTrue(fixture.messages.tryReceive().isFailure)
        assertEquals(0, fixture.signatures)
    }

    @Test
    fun `only correlated controller accept signs the immutable hosted challenge`(@TempDir root: Path) = runTest {
        val fixture = Fixture(root)
        val work = async { fixture.manager.approve(fixture.controller, fixture.request) }
        val started = fixture.messages.receive().second
        val prompt = fixture.messages.receive().second
        assertEquals("item/started", started["method"]!!.jsonPrimitive.content)
        assertEquals("fileChange", started["params"]!!.jsonObject["item"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("item/fileChange/requestApproval", prompt["method"]!!.jsonPrimitive.content)
        assertFalse(prompt["params"]!!.jsonObject.containsKey("grantRoot"))
        assertFalse(work.isCompleted)
        assertEquals(0, fixture.signatures)
        val answer = buildJsonObject {
            put("id", prompt.getValue("id"))
            put("result", buildJsonObject { put("decision", "accept") })
        }
        assertEquals(
            BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.NOT_RESPONSIBLE_CONTROLLER),
            fixture.manager.respond(ClientConnectionId.fresh(), answer),
        )
        assertEquals(BrokerPlanApprovalReply.Handled, fixture.manager.respond(fixture.controller, answer))
        assertEquals("serverRequest/resolved", fixture.messages.receive().second["method"]!!.jsonPrimitive.content)
        val granted = (work.await() as Refinement.Refined).value
        assertEquals(1, fixture.signatures)
        assertSame(fixture.subject, granted.grant.approval.subject)
        assertEquals(
            BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.ALREADY_RESOLVED),
            fixture.manager.respond(fixture.controller, answer),
        )
        fixture.manager.complete(granted, PlanApprovalItemCompletion.COMPLETED)
        val completed = fixture.messages.receive().second
        assertEquals(
            "completed",
            completed["params"]!!.jsonObject["item"]!!.jsonObject["status"]!!.jsonPrimitive.content,
        )
        assertEquals(
            BrokerPlanApprovalReply.Rejected(PlanApprovalFailure.UNKNOWN_REQUEST),
            fixture.manager.respond(fixture.controller, answer),
        )
        assertEquals(0, fixture.tasks.views().single().pendingRequests)
        val evidence = fixture.activity.joinToString { it.document().toString() }
        assertFalse(evidence.contains("private declaration"))
        assertFalse(evidence.contains(fixture.subject.hostedChallenge))
        assertTrue(
            fixture.activity.any { it.stage == SessionStage.APPROVAL_REDEEM && it.outcome == SessionOutcome.COMPLETED }
        )
    }

    @Test
    fun `decline cancel session approval and malformed responses never reach signer`(@TempDir root: Path) = runTest {
        for (decision in listOf("decline", "cancel", "acceptForSession", "invented")) {
            val fixture = Fixture(root)
            val work = async { fixture.manager.approve(fixture.controller, fixture.request) }
            fixture.messages.receive()
            val prompt = fixture.messages.receive().second
            fixture.manager.respond(
                fixture.controller,
                buildJsonObject {
                    put("id", prompt.getValue("id"))
                    put("result", buildJsonObject { put("decision", decision) })
                },
            )
            assertInstanceOf(Refinement.Rejected::class.java, work.await())
            assertEquals(0, fixture.signatures)
            assertEquals(0, fixture.tasks.views().single().pendingRequests)
        }
    }

    @Test
    fun `disconnect and retirement resolve prompts without signing`(@TempDir root: Path) = runTest {
        for (retire in listOf(false, true)) {
            val fixture = Fixture(root)
            val work = async { fixture.manager.approve(fixture.controller, fixture.request) }
            fixture.messages.receive()
            fixture.messages.receive()
            if (retire) fixture.manager.retire(fixture.controller) else fixture.manager.disconnect(fixture.controller)
            assertEquals(
                Refinement.Rejected(
                    if (retire) HostedPlanApprovalFailure.OWNER_RETIRED else HostedPlanApprovalFailure.DISCONNECTED
                ),
                work.await(),
            )
            assertEquals(0, fixture.signatures)
            assertEquals(0, fixture.tasks.views().single().pendingRequests)
        }
    }

    @Test
    fun `approval timeout and coroutine cancellation retire task pending state`(@TempDir root: Path) = runTest {
        val timeout = Fixture(root)
        val expired = async { timeout.manager.approve(timeout.controller, timeout.request) }
        timeout.messages.receive()
        timeout.messages.receive()
        assertEquals(Refinement.Rejected(HostedPlanApprovalFailure.TIMED_OUT), expired.await())
        assertEquals(0, timeout.tasks.views().single().pendingRequests)
        val cancelled = Fixture(root)
        val interrupted = launch { cancelled.manager.approve(cancelled.controller, cancelled.request) }
        cancelled.messages.receive()
        cancelled.messages.receive()
        interrupted.cancelAndJoin()
        assertEquals(0, cancelled.signatures)
        assertEquals(0, cancelled.tasks.views().single().pendingRequests)
    }

    private class Fixture(root: Path, unavailable: Boolean = false) {
        val controller = ClientConnectionId.fresh()
        val tasks = SharedTaskSessions()
        val invocation =
            BrokerInvocationContext.admit(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    callId = "call-1",
                    workingDirectory = root.toRealPath(),
                )
                .refined()
        val subject =
            ExactPlanApprovalSubject.admit(
                    planIdentity = "a".repeat(64),
                    hostedChallenge = "b".repeat(64),
                    root = invocation.workingDirectory,
                    host = HostedApprovalOwnerId.admit("11111111-1111-1111-1111-111111111111").refined(),
                )
                .refined()
        val request =
            HostedPlanApprovalRequest.admit(
                    HostedChangeApprovalOperation.APPLY,
                    invocation,
                    buildJsonObject { put("planIdentity", "plan:${subject.planIdentity}") },
                )
                .refined()
        val messages = Channel<Pair<ClientConnectionId, JsonObject>>(Channel.UNLIMITED)
        val activity = mutableListOf<SessionActivity>()
        var signatures = 0

        init {
            tasks.connect(controller)
            tasks.attach(invocation.threadId, controller)
        }

        private val gateway =
            object : HostedPlanApprovalGateway {
                override suspend fun prepare(request: HostedPlanApprovalRequest) =
                    HostedPlanApprovalChallenge.fromStoredPlan(
                        request,
                        subject,
                        ObserverFileChange.admit(
                                "src/Thing.kt",
                                ObserverFileChangeKind.UPDATE,
                                "@@\n+private declaration",
                            )
                            .refined(),
                    )

                override suspend fun redeem(
                    approval: ControllerApprovedPlan
                ): Refinement<HostedPlanApprovalGrant, HostedPlanApprovalFailure> {
                    signatures++
                    return HostedPlanApprovalGrant.fromSignedControllerApproval(approval, "e30." + "a".repeat(86))
                }
            }
        val manager =
            BrokerPlanApprovals(
                tasks = tasks,
                contracts =
                    CodexProtocolContracts.define(
                            CodexOwnedSchema.entries.associateWith { buildJsonObject { put("type", "object") } }
                        )
                        .validated(),
                gateway = if (unavailable) HostedPlanApprovalGateway.Unavailable else gateway,
                delivery =
                    BrokerPlanApprovalDelivery(
                        send = { client, message ->
                            messages.trySend(client to Json.parseToJsonElement(message).jsonObject)
                            PlanApprovalSend.SENT
                        },
                        clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                    ),
                publish = { activity.add(it) },
                waitMillis = 1_000,
            )
    }

    companion object {
        private fun <T, E> Refinement<T, E>.refined(): T = (this as Refinement.Refined).value

        private fun <T, E> Validation<T, E>.validated(): T = (this as Validation.Validated).value
    }
}
