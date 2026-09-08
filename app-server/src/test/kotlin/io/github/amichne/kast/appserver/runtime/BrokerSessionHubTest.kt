package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.WorkspaceEnrollment
import io.github.amichne.kast.appserver.core.*
import io.github.amichne.kast.appserver.protocol.*
import io.github.amichne.kast.appserver.protocol.codex.*
import io.github.amichne.kast.appserver.schema.*
import io.github.amichne.kast.kernel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class BrokerSessionHubTest {
    @Test fun `resolution on source retires detached approval recipient`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        try {
            val source = fixture.connect()
            val recipient = fixture.connect()
            fixture.bind(source, "thread/start")
            fixture.bind(recipient, "thread/resume")
            fixture.hub.tasks.release(fixture.thread, source.session.id)
            fixture.hub.tasks.claim(fixture.thread, recipient.session.id)
            source.upstream.received.send(BrokerUpstreamFrame.Text("""{"id":42,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1"}}"""))
            recipient.session.output.receive()
            recipient.session.detach()
            source.upstream.sent.receive()
            assertFalse(recipient.upstream.closed)

            source.upstream.received.send(BrokerUpstreamFrame.Text("""{"method":"serverRequest/resolved","params":{"threadId":"thread-1","requestId":42}}"""))

            withTimeout(1_000) { while (!recipient.upstream.closed) yield() }
            assertFalse(source.upstream.closed)
            assertEquals(ControlResult.Accepted, fixture.hub.tasks.claim(fixture.thread, source.session.id))
        } finally { fixture.hub.close() }
    }
    @Test fun `identical request ids remain connection local and blocked writer does not block peer`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        try {
            val a = fixture.connect(); val b = fixture.connect()
            a.upstream.block = CompletableDeferred()
            a.session.accept("""{"id":8,"method":"model/list"}""")
            b.session.accept("""{"id":8,"method":"model/list"}""")
            assertEquals(8, Json.parseToJsonElement(b.upstream.sent.receive()).jsonObject.getValue("id").jsonPrimitive.int)
            b.upstream.received.send(BrokerUpstreamFrame.Text("""{"id":8,"result":{"owner":"b"}}"""))
            assertTrue(withTimeout(1_000) { b.session.output.receive() }.contains("\"b\""))
            a.upstream.block!!.complete(Unit)
            a.upstream.sent.receive()
            a.upstream.received.send(BrokerUpstreamFrame.Text("""{"id":8,"result":{"owner":"a"}}"""))
            assertTrue(withTimeout(1_000) { a.session.output.receive() }.contains("\"a\""))
            a.session.detach()
            b.session.accept("""{"id":9,"method":"kast/appServer/status"}""")
            assertEquals(9, Json.parseToJsonElement(b.session.output.receive()).jsonObject.getValue("id").jsonPrimitive.int)
        } finally { fixture.hub.close() }
    }

    @Test fun `observers receive ordered duplicate deltas while controller owns input`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        try {
            val a = fixture.connect(); val b = fixture.connect()
            fixture.bind(a,"thread/start"); fixture.bind(b,"thread/resume")
            b.session.accept("""{"id":6,"method":"turn/start","params":{"threadId":"thread-1","input":[]}}""")
            assertTrue(b.session.output.receive().contains("NOT_CONTROLLER"))
            val delta = """{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"answer","delta":"same"}}"""
            repeat(2) { a.upstream.received.send(BrokerUpstreamFrame.Text(delta)) }
            repeat(2) { assertEquals(delta,withTimeout(1_000) { a.session.output.receive() }); assertEquals(delta,withTimeout(1_000) { b.session.output.receive() }) }
            // A stale observer copy is not a second authoritative event stream.
            b.upstream.received.send(BrokerUpstreamFrame.Text(delta))
            b.session.accept("""{"id":10,"method":"kast/appServer/status"}""")
            assertEquals(10,Json.parseToJsonElement(b.session.output.receive()).jsonObject.getValue("id").jsonPrimitive.int)
        } finally { fixture.hub.close() }
    }

    @Test fun `service owns one invocation after controller detaches and observer sees completion`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        try {
            val a = fixture.connect(); val b = fixture.connect()
            fixture.bind(a,"thread/start"); fixture.bind(b,"thread/resume")
            a.upstream.received.send(BrokerUpstreamFrame.Text("""{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1"}}}"""))
            a.session.output.receive(); b.session.output.receive()
            val request = """{"id":7,"method":"item/tool/call","params":{"threadId":"thread-1","turnId":"turn-1","callId":"call-1","namespace":"kast","tool":"query","arguments":{}}}"""
            a.upstream.received.send(BrokerUpstreamFrame.Text(request))
            fixture.entered.await()
            b.upstream.received.send(BrokerUpstreamFrame.Text(request))
            a.session.detach()
            assertFalse(a.upstream.closed)
            assertEquals(ControlResult.Rejected(ControlFailure.TASK_BUSY),fixture.hub.tasks.claim(fixture.thread,b.session.id))
            fixture.allowExecution.complete(Unit)
            val replyA = withTimeout(1_000) { a.upstream.sent.receive() }
            val replyB = withTimeout(1_000) { b.upstream.sent.receive() }
            assertEquals(replyA,replyB); assertEquals(1,fixture.invocations.get())
            val completed = """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}"""
            a.upstream.received.send(BrokerUpstreamFrame.Text(completed))
            assertEquals(completed,withTimeout(1_000) { b.session.output.receive() })
            assertEquals(ControlResult.Accepted,fixture.hub.tasks.claim(fixture.thread,b.session.id))
        } finally { fixture.hub.close() }
    }

    @Test fun `locally rejected turn request does not reserve controller forever`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        try {
            val a = fixture.connect(); fixture.bind(a,"thread/start")
            a.session.accept("""{"id":5,"method":"turn/interrupt","params":{"threadId":"thread-1"}}""")
            assertTrue(a.session.output.receive().contains("TURN_INTERRUPT_SCHEMA_REJECTED"))
            assertEquals(ControlResult.Accepted,fixture.hub.tasks.release(fixture.thread,a.session.id))
        } finally { fixture.hub.close() }
    }

    @Test fun `unenrolled start and resume are transparent including whitespace and override fields`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root, WorkspaceEnrollment.Unenrolled)
        try {
            val a = fixture.connect()
            for (request in listOf(
                " { \"id\":1, \"method\":\"thread/start\", \"params\":{\"cwd\":\"$root\"} } ",
                " { \"id\":2, \"method\":\"thread/resume\", \"params\":{\"threadId\":\"external\",\"path\":\"external.jsonl\"} } ",
            )) { a.session.accept(request); assertEquals(request,a.upstream.sent.receive()) }
        } finally { fixture.hub.close() }
    }

    @Test fun `approval answer and resolution retain recipient correlation through a handoff`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        try {
            val a = fixture.connect(); val b = fixture.connect()
            fixture.bind(a,"thread/start"); fixture.bind(b,"thread/resume")
            fixture.hub.tasks.release(fixture.thread,a.session.id); fixture.hub.tasks.claim(fixture.thread,b.session.id)
            a.upstream.received.send(BrokerUpstreamFrame.Text("""{"id":42,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"command-1"}}"""))
            val request = Json.parseToJsonElement(withTimeout(1_000) { b.session.output.receive() }).jsonObject
            val routedId = request.getValue("id")
            val answer = """{"id":$routedId,"result":{"decision":"accept"}}"""
            a.session.accept(answer)
            assertTrue(a.session.output.receive().contains("NOT_RESPONSIBLE_CLIENT"))
            a.session.detach()
            assertFalse(a.upstream.closed)
            b.session.accept(answer)
            assertEquals(JsonPrimitive(42),Json.parseToJsonElement(a.upstream.sent.receive()).jsonObject.getValue("id"))
            assertEquals(ControlResult.Rejected(ControlFailure.TASK_BUSY),fixture.hub.tasks.release(fixture.thread,b.session.id))
            a.upstream.received.send(BrokerUpstreamFrame.Text("""{"method":"serverRequest/resolved","params":{"threadId":"thread-1","requestId":42}}"""))
            val resolved = Json.parseToJsonElement(withTimeout(1_000) { b.session.output.receive() }).jsonObject
            assertEquals(routedId,resolved.getValue("params").jsonObject.getValue("requestId"))
            assertEquals(ControlResult.Accepted,fixture.hub.tasks.release(fixture.thread,b.session.id))
        } finally { fixture.hub.close() }
    }

    @Test fun `losing previous request source marks handed off task uncertain and retires its prompt`(@TempDir root: Path) = runBlocking {
        val fixture = Fixture(root)
        try {
            val a = fixture.connect(); val b = fixture.connect()
            fixture.bind(a,"thread/start"); fixture.bind(b,"thread/resume")
            fixture.hub.tasks.release(fixture.thread,a.session.id); fixture.hub.tasks.claim(fixture.thread,b.session.id)
            a.upstream.received.send(BrokerUpstreamFrame.Text("""{"id":42,"method":"item/commandExecution/requestApproval","params":{"threadId":"thread-1"}}"""))
            val request = Json.parseToJsonElement(b.session.output.receive()).jsonObject
            a.upstream.received.send(BrokerUpstreamFrame.Closed)
            val resolved = Json.parseToJsonElement(withTimeout(1_000) { b.session.output.receive() }).jsonObject
            assertEquals(request.getValue("id"),resolved.getValue("params").jsonObject.getValue("requestId"))
            assertEquals(ControlResult.Rejected(ControlFailure.RECONCILIATION_REQUIRED),fixture.hub.tasks.authorize(fixture.thread,b.session.id))
        } finally { fixture.hub.close() }
    }

    private class Peer(val session: BrokerSessionHub.Session,val upstream: FakeUpstream)
    private class FakeUpstream : BrokerUpstreamConnection {
        val sent = Channel<String>(16)
        val received = Channel<BrokerUpstreamFrame>(16)
        var block: CompletableDeferred<Unit>? = null
        @Volatile var closed = false
        override suspend fun send(message: String): BrokerUpstreamSend { block?.await(); sent.send(message); return BrokerUpstreamSend.SENT }
        override suspend fun receive(): BrokerUpstreamFrame = received.receiveCatching().getOrNull() ?: BrokerUpstreamFrame.Closed
        override suspend fun close() { closed = true; received.close(); sent.close(); block?.cancel() }
    }
    private class Fixture(root: Path, enrollment: WorkspaceEnrollment = WorkspaceEnrollment.ProtocolFixture) {
        val root = root.toRealPath()
        val invocations = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val allowExecution = CompletableDeferred<Unit>()
        val thread = checkNotNull(BrokerThreadId.admit("thread-1"))
        private val connecting = Channel<FakeUpstream>(16)
        private val objectSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
        private val schema = NetworkntJsonSchemaCompiler.compile(objectSchema).refined()
        private val tool: BrokerTool<Unit,JsonElement,JsonElement,Nothing> = BrokerTool(
            ToolName.admit("query").refined(),ToolDescription.admit("Query test workspace").refined(),ToolLoading.EAGER,
            JsonDomainDefinition(schema,RefinementDefinition { input -> Validation.validated(input.element) }),schema,
            invoke = { _, input, _ -> invocations.incrementAndGet(); entered.complete(Unit); allowExecution.await(); ProviderCall.Completed(input) },
            encode = { it }, present = { ToolPresentation.text(it.toString(),true) },
        )
        private val broker = Broker.create(listOf(ProviderRegistration.define(
            ProviderNamespace.admit("kast").refined(),ProviderVersion.admit("1").refined(),listOf(tool),start = { ProviderStartup.Started(Unit) },
        ).validated()),BrokerLimits.defaults()).validated()
        val hub = BrokerSessionHub(KtorBrokerServerOptions(
            BrokerSocketPath.admit(Path.of("/tmp/hub-test.sock")).validated(),broker,
            CodexProtocolContracts.define(CodexOwnedSchema.entries.associateWith {
                if (it == CodexOwnedSchema.TURN_INTERRUPT_PARAMS) Json.parseToJsonElement("""{"type":"object","required":["threadId","turnId"]}""").jsonObject else objectSchema
            }).validated(),MemoryThreadCatalogStore(),BrokerUpstreamConnector { BrokerUpstreamConnectionAdmission.Connected(connecting.receive()) },
            4,4 * 1_024 * 1_024,enrollment = enrollment,
        ))
        suspend fun connect(): Peer {
            val upstream = FakeUpstream(); connecting.send(upstream)
            val session = checkNotNull(hub.attach("""{"id":0,"method":"initialize","params":{"clientInfo":{"name":"test"}}}"""))
            upstream.sent.receive(); upstream.received.send(BrokerUpstreamFrame.Text("""{"id":0,"result":{}}""")); session.output.receive()
            session.accept("""{"method":"initialized"}"""); upstream.sent.receive()
            return Peer(session,upstream)
        }
        suspend fun bind(peer: Peer,method: String) {
            peer.session.accept("""{"id":1,"method":"$method","params":{"threadId":"thread-1","cwd":"$root"}}""")
            peer.upstream.sent.receive()
            peer.upstream.received.send(BrokerUpstreamFrame.Text("""{"id":1,"result":{"thread":{"id":"thread-1","turns":[]},"cwd":"$root"}}"""))
            peer.session.output.receive()
        }
    }
    companion object {
        private fun <T,E> Refinement<T,E>.refined(): T = (this as Refinement.Refined).value
        private fun <T,E> Validation<T,E>.validated(): T = (this as Validation.Validated).value
    }
}
