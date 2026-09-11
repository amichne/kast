package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.WorkspaceEnrollment
import io.github.amichne.kast.appserver.core.ObserverFileChange
import io.github.amichne.kast.appserver.core.ObserverFileChangeKind
import io.github.amichne.kast.appserver.protocol.codex.ProtocolCloseFailure
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerSessionHubTest {
    @Test
    fun `native exact approval waits outside workspace lane and carries proof only after controller accepts`(
        @TempDir root: Path
    ) = runBlocking {
        val gateway =
            object : HostedPlanApprovalGateway {
                override suspend fun prepare(
                    request: HostedPlanApprovalRequest
                ): Refinement<HostedPlanApprovalChallenge, HostedPlanApprovalFailure> {
                    val subject =
                        ExactPlanApprovalSubject.admit(
                                planIdentity = request.planIdentity,
                                hostedChallenge = "b".repeat(64),
                                operation = request.operation,
                                root = request.invocation.workingDirectory,
                                host = HostedApprovalOwnerId.admit("11111111-1111-1111-1111-111111111111").refined(),
                            )
                            .refined()
                    return HostedPlanApprovalChallenge.fromStoredPlan(
                        request,
                        subject,
                        ObserverFileChange.admit(
                                "src/Main.kt",
                                ObserverFileChangeKind.UPDATE,
                                "@@ -1 +1 @@\n-old\n+new\n",
                            )
                            .refined(),
                    )
                }

                override suspend fun redeem(approval: ControllerApprovedPlan) =
                    HostedPlanApprovalGrant.fromSignedControllerApproval(approval, "e30.${"a".repeat(86)}")
            }
        val fixture = HubTestFixture(root, planGateway = gateway)
        try {
            val peer = fixture.connect()
            fixture.bind(peer, "thread/start")
            peer.upstream.received.send(
                BrokerUpstreamFrame.Text(
                    """
                    |{"id":70,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-apply",
                    |"callId":"apply","namespace":"kast","tool":"change_apply",
                    |"arguments":{"planIdentity":"plan:${"a".repeat(64)}"}}}
                    """
                        .trimMargin()
                )
            )
            val started = Json.parseToJsonElement(withTimeout(1000) { peer.session.output.receive() }).jsonObject
            assertEquals("item/started", started["method"]?.jsonPrimitive?.content)
            val approval = Json.parseToJsonElement(withTimeout(1000) { peer.session.output.receive() }).jsonObject
            assertEquals("item/fileChange/requestApproval", approval["method"]?.jsonPrimitive?.content)
            assertTrue(fixture.approvedInvocations.isEmpty())
            peer.upstream.received.send(
                BrokerUpstreamFrame.Text(toolCall(thread = "thread-1", call = "read", request = 71, independent = true))
            )
            val readReply = Json.parseToJsonElement(withTimeout(1000) { peer.upstream.sent.receive() }).jsonObject
            assertEquals(JsonPrimitive(71), readReply["id"])
            assertTrue(fixture.approvedInvocations.isEmpty())
            peer.session.accept(
                buildJsonObject {
                    put("id", approval.getValue("id"))
                    put("result", buildJsonObject { put("decision", "accept") })
                }
                    .toString()
            )
            val reply = Json.parseToJsonElement(withTimeout(1000) { peer.upstream.sent.receive() }).jsonObject
            assertEquals(JsonPrimitive(70), reply["id"])
            assertInstanceOf(BrokerInvocationApproval.Granted::class.java, fixture.approvedInvocations.single())
            val resolved = Json.parseToJsonElement(withTimeout(1000) { peer.session.output.receive() }).jsonObject
            val completed = Json.parseToJsonElement(withTimeout(1000) { peer.session.output.receive() }).jsonObject
            assertEquals("serverRequest/resolved", resolved["method"]?.jsonPrimitive?.content)
            assertEquals("item/completed", completed["method"]?.jsonPrimitive?.content)
            assertTrue(peer.upstream.sent.tryReceive().isFailure)
        } finally {
            fixture.hub.close()
        }
    }

    @Test
    fun `tool display records bounded success and rejection evidence without payloads`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            try {
                val peer = fixture.connect()
                val completed =
                    """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","completedAtMs":20,"item":{"type":"dynamicToolCall","id":"call-1","namespace":"kast","tool":"query","arguments":{"private":"do-not-log"},"status":"completed","success":true,"contentItems":[{"type":"inputText","text":"private-result"}]}}}"""
                peer.upstream.received.send(BrokerUpstreamFrame.Text(completed))
                val displayed = Json.parseToJsonElement(withTimeout(1_000) { peer.session.output.receive() }).jsonObject
                assertEquals(
                    "mcpToolCall",
                    displayed
                        .getValue("params")
                        .jsonObject
                        .getValue("item")
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive
                        .content,
                )
                assertEquals(
                    listOf(SessionOutcome.COMPLETED),
                    fixture.activities.filter { it.stage.name == "TOOL_DISPLAY" }.map { it.outcome },
                )

                peer.upstream.received.send(
                    BrokerUpstreamFrame.Text(completed.replace("\"success\":true", "\"success\":false"))
                )
                assertTrue(withTimeout(1_000) { peer.session.output.receiveCatching() }.isClosed)
                val evidence = fixture.activities.filter { it.stage.name == "TOOL_DISPLAY" }
                assertEquals(listOf(SessionOutcome.COMPLETED, SessionOutcome.REJECTED), evidence.map { it.outcome })
                assertInstanceOf(
                    ProtocolCloseFailure.ToolCallProjectionRejected::class.java,
                    evidence.last().protocolFailure,
                )
                val log = evidence.joinToString { it.document().toString() }
                assertFalse(log.contains("do-not-log"))
                assertFalse(log.contains("private-result"))
                assertTrue(log.contains("COMPLETION_SUCCESS_CONFLICT"))
            } finally {
                fixture.hub.close()
            }
        }

    @Test
    fun `hub close waits for active execution cancellation to retire`(@TempDir root: Path) = runBlocking {
        val cancellationRetirement = CompletableDeferred<Unit>()
        val fixture = HubTestFixture(root, cancellationRetirement = cancellationRetirement)
        try {
            val active = fixture.connect()
            fixture.bind(active, "thread/start")
            active.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "active", 7)))
            fixture.entered.await()

            val closure = async { fixture.hub.close() }
            fixture.cancelled.await()
            assertNull(
                withTimeoutOrNull(100) { closure.join() },
                "hub close returned while owned execution cleanup was still active",
            )
            cancellationRetirement.complete(Unit)
            withTimeout(1_000) { closure.await() }
        } finally {
            cancellationRetirement.complete(Unit)
            fixture.allowExecution.complete(Unit)
            fixture.hub.close()
        }
    }

    @Test
    fun `blocked semantic work in one workspace leaves another workspace serviceable`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            val other = java.nio.file.Files.createDirectory(root.resolve("other")).toRealPath()
            try {
                val a = fixture.connect()
                val b = fixture.connect()
                fixture.bind(a, "thread/start")
                fixture.bind(b, "thread/start", "thread-2", other)
                a.upstream.received.send(
                    BrokerUpstreamFrame.Text(
                        """{"id":7,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"kast","tool":"query","arguments":{}}}"""
                    )
                )
                fixture.entered.await()
                b.upstream.received.send(
                    BrokerUpstreamFrame.Text(
                        """{"id":7,"method":"item/tool/call","params":{"threadId":"thread-2","turnId":"turn-1","callId":"call-1","namespace":"kast","tool":"query","arguments":{"independent":true}}}"""
                    )
                )
                val response = withTimeoutOrNull(1_000) { b.upstream.sent.receive() }
                assertNotNull(response, "ready workspace B was blocked by workspace A's semantic execution")
                assertTrue(response!!.contains("independent"))
                assertFalse(fixture.allowExecution.isCompleted)
            } finally {
                fixture.allowExecution.complete(Unit)
                fixture.hub.close()
            }
        }

    @Test
    fun `queued cancellation returns before active work and never journals execution`(@TempDir root: Path) =
        runBlocking {
            val journal = root.toRealPath().resolve("invocations.json")
            val fixture = HubTestFixture(root, invocationJournal = journal)
            try {
                val active = fixture.connect()
                val queued = fixture.connect()
                fixture.bind(active, "thread/start")
                fixture.bind(queued, "thread/start", "thread-2")
                active.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "active", 7)))
                fixture.entered.await()
                queued.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-2", "queued", 8)))
                queued.upstream.received.send(BrokerUpstreamFrame.Text("""{"method":"fixture/barrier"}"""))
                queued.session.output.receive()
                queued.session.accept(
                    """{"id":20,"method":"turn/interrupt","params":{"threadId":"thread-2","turnId":"turn-queued"}}"""
                )
                val responses = mutableListOf<String>()
                withTimeoutOrNull(1_000) {
                    while (
                        responses.none { Json.parseToJsonElement(it).jsonObject["id"] == JsonPrimitive(8) }
                    ) responses += queued.upstream.sent.receive()
                }
                val result = responses.firstOrNull { Json.parseToJsonElement(it).jsonObject["id"] == JsonPrimitive(8) }
                assertNotNull(result, "queued invocation did not retire before active work completed")
                assertTrue(result!!.contains("CANCELLED_BEFORE_EXECUTION"))
                assertEquals(1, fixture.invocations.get())
                val records =
                    Json.parseToJsonElement(java.nio.file.Files.readString(journal))
                        .jsonObject
                        .getValue("records")
                        .jsonObject
                assertEquals(1, records.size, "queued cancellation was incorrectly journaled as started")
                assertFalse(fixture.allowExecution.isCompleted)
            } finally {
                fixture.allowExecution.complete(Unit)
                fixture.hub.close()
            }
        }

    @Test
    fun `workspace queue rejects demand beyond its declared bound`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val peer = fixture.connect()
            fixture.bind(peer, "thread/start")
            peer.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "active", 7)))
            fixture.entered.await()
            repeat(33) { index ->
                peer.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "queued-$index", index + 8)))
            }
            val rejection = withTimeoutOrNull(1_000) { peer.upstream.sent.receive() }
            assertNotNull(rejection, "workspace accepted unbounded pending work")
            assertTrue(rejection!!.contains("WORKSPACE_QUEUE_CAPACITY_EXCEEDED"))
            assertEquals(1, fixture.invocations.get())
        } finally {
            fixture.allowExecution.complete(Unit)
            fixture.hub.close()
        }
    }

    @Test
    fun `controller handoff cancels original execution and uncertainty stays workspace local`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            val other = java.nio.file.Files.createDirectory(root.resolve("other")).toRealPath()
            try {
                val source = fixture.connect()
                val controller = fixture.connect()
                val independent = fixture.connect()
                fixture.bind(source, "thread/start")
                fixture.bind(controller, "thread/resume")
                fixture.bind(independent, "thread/start", "thread-2", other)
                assertEquals(ControlResult.Accepted, fixture.hub.tasks.release(fixture.thread, source.session.id))
                assertEquals(ControlResult.Accepted, fixture.hub.tasks.claim(fixture.thread, controller.session.id))
                source.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "active", 7)))
                fixture.entered.await()
                controller.session.accept(
                    """{"id":20,"method":"turn/interrupt","params":{"threadId":"thread-1","turnId":"turn-active"}}"""
                )
                val interrupted = withTimeoutOrNull(1_000) { source.upstream.sent.receive() }
                assertNotNull(interrupted, "new controller could not interrupt original session's execution")
                assertTrue(interrupted!!.contains("uncertain", ignoreCase = true))
                assertNotNull(
                    withTimeoutOrNull(1_000) { fixture.cancelled.await() },
                    "cancelled wrapper left the provider operation running",
                )
                source.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "next", 8)))
                assertTrue(
                    withTimeout(1_000) { source.upstream.sent.receive() }.contains("WORKSPACE_RECOVERY_REQUIRED")
                )
                independent.upstream.received.send(
                    BrokerUpstreamFrame.Text(toolCall("thread-2", "independent", 9, true))
                )
                assertTrue(withTimeout(1_000) { independent.upstream.sent.receive() }.contains("independent"))
                assertFalse(fixture.allowExecution.isCompleted)
            } finally {
                fixture.allowExecution.complete(Unit)
                fixture.hub.close()
            }
        }

    @Test
    fun `one workspace serializes semantic work until its active request completes`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val active = fixture.connect()
            val pending = fixture.connect()
            fixture.bind(active, "thread/start")
            fixture.bind(pending, "thread/start", "thread-2")
            active.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "active", 7)))
            fixture.entered.await()
            pending.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-2", "pending", 8, true)))
            assertNull(withTimeoutOrNull(150) { pending.upstream.sent.receive() })
            assertEquals(1, fixture.invocations.get())
            fixture.allowExecution.complete(Unit)
            assertTrue(withTimeout(1_000) { active.upstream.sent.receive() }.contains("success"))
            assertTrue(withTimeout(1_000) { pending.upstream.sent.receive() }.contains("independent"))
            assertEquals(2, fixture.invocations.get())
        } finally {
            fixture.allowExecution.complete(Unit)
            fixture.hub.close()
        }
    }

    @Test
    fun `queue deadline reports elapsed wait and leaves the workspace reusable`(@TempDir root: Path) = runBlocking {
        val policy = WorkspaceExecutionPolicy.admit(1, 100, 5_000).refined()
        val fixture = HubTestFixture(root, executionPolicy = policy)
        try {
            val active = fixture.connect()
            val pending = fixture.connect()
            fixture.bind(active, "thread/start")
            fixture.bind(pending, "thread/start", "thread-2")
            active.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "active", 7)))
            fixture.entered.await()
            pending.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-2", "pending", 8, true)))
            assertTrue(withTimeout(1_000) { pending.upstream.sent.receive() }.contains("WORKSPACE_QUEUE_TIMED_OUT"))
            assertEquals(1, fixture.invocations.get())
            pending.session.accept("""{"id":21,"method":"kast/appServer/status"}""")
            val status =
                Json.parseToJsonElement(pending.session.output.receive())
                    .jsonObject
                    .getValue("result")
                    .jsonObject
                    .getValue("workspaceExecution")
                    .jsonObject
            val timedOut =
                status
                    .getValue("events")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it["failure"] == JsonPrimitive("WORKSPACE_QUEUE_TIMED_OUT") }
            assertEquals(JsonPrimitive("queue"), timedOut["stage"])
            assertEquals(JsonPrimitive("timed_out"), timedOut["outcome"])
            assertTrue(
                timedOut.getValue("queueAgeMillis").jsonPrimitive.long > 0,
                "queue timeout evidence lost the time spent waiting",
            )
            assertTrue(
                timedOut.getValue("elapsedMillis").jsonPrimitive.long >=
                    timedOut.getValue("queueAgeMillis").jsonPrimitive.long
            )
            fixture.allowExecution.complete(Unit)
            active.upstream.sent.receive()
            pending.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-2", "next", 9, true)))
            assertTrue(withTimeout(1_000) { pending.upstream.sent.receive() }.contains("independent"))
        } finally {
            fixture.allowExecution.complete(Unit)
            fixture.hub.close()
        }
    }

    @Test
    fun `interaction deadline cancels execution and retains local recovery evidence`(@TempDir root: Path) =
        runBlocking {
            val policy = WorkspaceExecutionPolicy.admit(1, 50, 200).refined()
            val fixture = HubTestFixture(root, executionPolicy = policy)
            try {
                val peer = fixture.connect()
                fixture.bind(peer, "thread/start")
                peer.upstream.received.send(BrokerUpstreamFrame.Text(toolCall("thread-1", "deadline", 7)))
                fixture.entered.await()
                assertTrue(
                    withTimeout(1_000) { peer.upstream.sent.receive() }.contains("WORKSPACE_INTERACTION_TIMED_OUT")
                )
                withTimeout(1_000) { fixture.cancelled.await() }
                peer.session.accept("""{"id":21,"method":"kast/appServer/status"}""")
                val status =
                    Json.parseToJsonElement(peer.session.output.receive())
                        .jsonObject
                        .getValue("result")
                        .jsonObject
                        .getValue("workspaceExecution")
                        .jsonObject
                assertEquals(
                    JsonPrimitive("recovery_required"),
                    status.getValue("lanes").jsonArray.single().jsonObject["state"],
                )
                assertTrue(
                    status
                        .getValue("events")
                        .jsonArray
                        .map { it.jsonObject }
                        .any {
                            it["outcome"] == JsonPrimitive("timed_out") &&
                                it["certainty"] == JsonPrimitive("uncertain") &&
                                it["failure"] == JsonPrimitive("WORKSPACE_INTERACTION_TIMED_OUT")
                        }
                )
                assertFalse(fixture.allowExecution.isCompleted)
            } finally {
                fixture.allowExecution.complete(Unit)
                fixture.hub.close()
            }
        }

    @Test
    fun `workspace diagnostic history is bounded and excludes tool payloads`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val peer = fixture.connect()
            fixture.bind(peer, "thread/start")
            repeat(70) { index ->
                val document =
                    Json.parseToJsonElement(toolCall("thread-1", "diagnostic-$index", index + 7, true)).jsonObject
                val params = document.getValue("params").jsonObject
                val arguments =
                    JsonObject(
                        params.getValue("arguments").jsonObject + ("privatePayload" to JsonPrimitive("must-not-appear"))
                    )
                val call =
                    JsonObject(document + ("params" to JsonObject(params + ("arguments" to arguments)))).toString()
                peer.upstream.received.send(BrokerUpstreamFrame.Text(call))
                withTimeout(1_000) { peer.upstream.sent.receive() }
            }
            peer.session.accept("""{"id":100,"method":"kast/appServer/status"}""")
            val status =
                Json.parseToJsonElement(peer.session.output.receive())
                    .jsonObject
                    .getValue("result")
                    .jsonObject
                    .getValue("workspaceExecution")
                    .jsonObject
            assertEquals(128, status.getValue("events").jsonArray.size)
            assertTrue(
                status
                    .getValue("events")
                    .jsonArray
                    .map { it.jsonObject }
                    .any { it["outcome"] == JsonPrimitive("completed") }
            )
            assertFalse(status.toString().contains("privatePayload"))
            assertFalse(status.toString().contains("must-not-appear"))
        } finally {
            fixture.allowExecution.complete(Unit)
            fixture.hub.close()
        }
    }

    @Test
    fun `queue policy rejects invalid bounds`() {
        assertEquals(
            WorkspaceExecutionPolicyFailure.QUEUED_LIMIT_REJECTED,
            (WorkspaceExecutionPolicy.admit(-1, 10, 20) as Refinement.Rejected).failure,
        )
        assertEquals(
            WorkspaceExecutionPolicyFailure.WAIT_LIMIT_REJECTED,
            (WorkspaceExecutionPolicy.admit(1, 30, 20) as Refinement.Rejected).failure,
        )
        assertEquals(
            WorkspaceExecutionPolicyFailure.INTERACTION_LIMIT_REJECTED,
            (WorkspaceExecutionPolicy.admit(1, 10, 0) as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `resolution on source retires detached approval recipient`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val source = fixture.connect()
            val recipient = fixture.connect()
            fixture.bind(source, "thread/start")
            fixture.bind(recipient, "thread/resume")
            fixture.hub.tasks.release(fixture.thread, source.session.id)
            fixture.hub.tasks.claim(fixture.thread, recipient.session.id)
            source.upstream.received.send(
                BrokerUpstreamFrame.Text(
                    """{"id":42,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1"}}"""
                )
            )
            recipient.session.output.receive()
            recipient.session.detach()
            source.upstream.sent.receive()
            assertFalse(recipient.upstream.closed)

            source.upstream.received.send(
                BrokerUpstreamFrame.Text(
                    """{"method":"serverRequest/resolved","params":{"threadId":"thread-1","requestId":42}}"""
                )
            )

            withTimeout(1_000) { while (!recipient.upstream.closed) yield() }
            assertFalse(source.upstream.closed)
            assertEquals(ControlResult.Accepted, fixture.hub.tasks.claim(fixture.thread, source.session.id))
        } finally {
            fixture.hub.close()
        }
    }

    @Test
    fun `identical request ids remain connection local and blocked writer does not block peer`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            try {
                val a = fixture.connect()
                val b = fixture.connect()
                a.upstream.block = CompletableDeferred()
                a.session.accept("""{"id":8,"method":"model/list"}""")
                b.session.accept("""{"id":8,"method":"model/list"}""")
                assertEquals(
                    8,
                    Json.parseToJsonElement(b.upstream.sent.receive()).jsonObject.getValue("id").jsonPrimitive.int,
                )
                b.upstream.received.send(BrokerUpstreamFrame.Text("""{"id":8,"result":{"owner":"b"}}"""))
                assertTrue(withTimeout(1_000) { b.session.output.receive() }.contains("\"b\""))
                a.upstream.block!!.complete(Unit)
                a.upstream.sent.receive()
                a.upstream.received.send(BrokerUpstreamFrame.Text("""{"id":8,"result":{"owner":"a"}}"""))
                assertTrue(withTimeout(1_000) { a.session.output.receive() }.contains("\"a\""))
                a.session.detach()
                b.session.accept("""{"id":9,"method":"kast/appServer/status"}""")
                assertEquals(
                    9,
                    Json.parseToJsonElement(b.session.output.receive()).jsonObject.getValue("id").jsonPrimitive.int,
                )
            } finally {
                fixture.hub.close()
            }
        }

    @Test
    fun `observers receive ordered duplicate deltas while controller owns input`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val a = fixture.connect()
            val b = fixture.connect()
            fixture.bind(a, "thread/start")
            fixture.bind(b, "thread/resume")
            b.session.accept("""{"id":6,"method":"turn/start","params":{"threadId":"thread-1","input":[]}}""")
            assertTrue(b.session.output.receive().contains("NOT_CONTROLLER"))
            val delta =
                """{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"answer","delta":"same"}}"""
            repeat(2) { a.upstream.received.send(BrokerUpstreamFrame.Text(delta)) }
            repeat(2) {
                assertEquals(delta, withTimeout(1_000) { a.session.output.receive() })
                assertEquals(delta, withTimeout(1_000) { b.session.output.receive() })
            }
            // A stale observer copy is not a second authoritative event stream.
            b.upstream.received.send(BrokerUpstreamFrame.Text(delta))
            b.session.accept("""{"id":10,"method":"kast/appServer/status"}""")
            assertEquals(
                10,
                Json.parseToJsonElement(b.session.output.receive()).jsonObject.getValue("id").jsonPrimitive.int,
            )
        } finally {
            fixture.hub.close()
        }
    }

    @Test
    fun `service owns one invocation after controller detaches and observer sees completion`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            try {
                val a = fixture.connect()
                val b = fixture.connect()
                fixture.bind(a, "thread/start")
                fixture.bind(b, "thread/resume")
                a.upstream.received.send(
                    BrokerUpstreamFrame.Text(
                        """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1"}}}"""
                    )
                )
                a.session.output.receive()
                b.session.output.receive()
                val request =
                    """{"id":7,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"kast","tool":"query","arguments":{}}}"""
                a.upstream.received.send(BrokerUpstreamFrame.Text(request))
                fixture.entered.await()
                b.upstream.received.send(BrokerUpstreamFrame.Text(request))
                a.session.detach()
                assertFalse(a.upstream.closed)
                assertEquals(
                    ControlResult.Rejected(ControlFailure.TASK_BUSY),
                    fixture.hub.tasks.claim(fixture.thread, b.session.id),
                )
                fixture.allowExecution.complete(Unit)
                val replyA = withTimeout(1_000) { a.upstream.sent.receive() }
                val replyB = withTimeout(1_000) { b.upstream.sent.receive() }
                assertEquals(replyA, replyB)
                assertEquals(1, fixture.invocations.get())
                val completed =
                    """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}"""
                a.upstream.received.send(BrokerUpstreamFrame.Text(completed))
                assertEquals(completed, withTimeout(1_000) { b.session.output.receive() })
                assertEquals(ControlResult.Accepted, fixture.hub.tasks.claim(fixture.thread, b.session.id))
            } finally {
                fixture.hub.close()
            }
        }

    @Test
    fun `locally rejected turn request does not reserve controller forever`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val a = fixture.connect()
            fixture.bind(a, "thread/start")
            a.session.accept("""{"id":5,"method":"turn/interrupt","params":{"threadId":"thread-1"}}""")
            assertTrue(a.session.output.receive().contains("TURN_INTERRUPT_SCHEMA_REJECTED"))
            assertEquals(ControlResult.Accepted, fixture.hub.tasks.release(fixture.thread, a.session.id))
        } finally {
            fixture.hub.close()
        }
    }

    @Test
    fun `unenrolled start and resume reject missing workspace ownership`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root, WorkspaceEnrollment.Unenrolled)
        try {
            val a = fixture.connect()
            for (request in
                listOf(
                    " { \"id\":1, \"method\":\"thread/start\", \"params\":{\"cwd\":\"$root\"} } ",
                    " { \"id\":2, \"method\":\"thread/resume\", \"params\":{\"threadId\":\"external\",\"path\":\"external.jsonl\"} } ",
                )) {
                a.session.accept(request)
                val response = Json.parseToJsonElement(withTimeout(1_000) { a.session.output.receive() }).jsonObject
                assertTrue(response.containsKey("error"))
                assertEquals(Json.parseToJsonElement(request).jsonObject["id"], response["id"])
                assertTrue(a.upstream.sent.tryReceive().isFailure)
            }
        } finally {
            fixture.hub.close()
        }
    }

    @Test
    fun `approval answer and resolution retain recipient correlation through a handoff`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            try {
                val a = fixture.connect()
                val b = fixture.connect()
                fixture.bind(a, "thread/start")
                fixture.bind(b, "thread/resume")
                fixture.hub.tasks.release(fixture.thread, a.session.id)
                fixture.hub.tasks.claim(fixture.thread, b.session.id)
                a.upstream.received.send(
                    BrokerUpstreamFrame.Text(
                        """{"id":42,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-1"}}"""
                    )
                )
                val request = Json.parseToJsonElement(withTimeout(1_000) { b.session.output.receive() }).jsonObject
                val routedId = request.getValue("id")
                val answer = """{"id":$routedId,"result":{"decision":"accept"}}"""
                a.session.accept(answer)
                assertTrue(a.session.output.receive().contains("NOT_RESPONSIBLE_CLIENT"))
                a.session.detach()
                assertFalse(a.upstream.closed)
                b.session.accept(answer)
                assertEquals(
                    JsonPrimitive(42),
                    Json.parseToJsonElement(a.upstream.sent.receive()).jsonObject.getValue("id"),
                )
                assertEquals(
                    ControlResult.Rejected(ControlFailure.TASK_BUSY),
                    fixture.hub.tasks.release(fixture.thread, b.session.id),
                )
                a.upstream.received.send(
                    BrokerUpstreamFrame.Text(
                        """{"method":"serverRequest/resolved","params":{"threadId":"thread-1","requestId":42}}"""
                    )
                )
                val resolved = Json.parseToJsonElement(withTimeout(1_000) { b.session.output.receive() }).jsonObject
                assertEquals(routedId, resolved.getValue("params").jsonObject.getValue("requestId"))
                assertEquals(ControlResult.Accepted, fixture.hub.tasks.release(fixture.thread, b.session.id))
            } finally {
                fixture.hub.close()
            }
        }

    @Test
    fun `losing previous request source marks handed off task uncertain and retires its prompt`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            try {
                val a = fixture.connect()
                val b = fixture.connect()
                fixture.bind(a, "thread/start")
                fixture.bind(b, "thread/resume")
                fixture.hub.tasks.release(fixture.thread, a.session.id)
                fixture.hub.tasks.claim(fixture.thread, b.session.id)
                a.upstream.received.send(
                    BrokerUpstreamFrame.Text(
                        """{"id":42,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1"}}"""
                    )
                )
                val request = Json.parseToJsonElement(b.session.output.receive()).jsonObject
                a.upstream.received.send(BrokerUpstreamFrame.Closed)
                val resolved = Json.parseToJsonElement(withTimeout(1_000) { b.session.output.receive() }).jsonObject
                assertEquals(request.getValue("id"), resolved.getValue("params").jsonObject.getValue("requestId"))
                assertEquals(
                    ControlResult.Rejected(ControlFailure.RECONCILIATION_REQUIRED),
                    fixture.hub.tasks.authorize(fixture.thread, b.session.id),
                )
            } finally {
                fixture.hub.close()
            }
        }

    companion object {
        private fun toolCall(thread: String, call: String, request: Int, independent: Boolean = false): String =
            """{"id":$request,"method":"item/tool/call","params":{"threadId":"$thread","turnId":"turn-$call","callId":"$call","namespace":"kast","tool":"query","arguments":{"independent":$independent}}}"""

        private fun <T, E> Refinement<T, E>.refined(): T = (this as Refinement.Refined).value
    }
}
