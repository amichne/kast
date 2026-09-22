package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonUpgradeHubTest {
    @Test
    fun `active invocation keeps update pending without cancelling execution`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val peer = fixture.connect()
            fixture.bind(peer, "thread/start")
            peer.upstream.received.send(
                BrokerUpstreamFrame.Text(
                    Json.encodeToString(
                        ToolRequest(
                            7,
                            "item/tool/call",
                            ToolParams("thread-1", "turn-1", "call-1", "kast", "query", Arguments(false)),
                        )
                    )
                )
            )
            fixture.entered.await()
            val pending = fixture.hub.upgrades.prepare(candidate, fixture.hub::upgradeBlockers).value()
            assertInstanceOf(UpgradeStatus.Pending::class.java, pending)
            assertTrue((pending as UpgradeStatus.Pending).blockers.toList().contains(UpgradeBlocker.INVOCATION_ACTIVE))
            assertFalse(fixture.cancelled.isCompleted)
            assertEquals(pending, fixture.hub.upgrades.observe(pending.request.id).value())
            fixture.allowExecution.complete(Unit)
            withTimeout(1000) { peer.upstream.sent.receive() }
            // A serial ingress round trip proves response publication and its bookkeeping have drained.
            peer.session.accept(Json.encodeToString(StatusRequest(8, "kast/appServer/status")))
            withTimeout(1000) { peer.session.output.receive() }
            assertEquals(1, fixture.invocations.get())
            assertFalse(fixture.cancelled.isCompleted)
        } finally {
            fixture.allowExecution.complete(Unit)
            fixture.hub.close()
        }
    }

    @Test
    fun `sealed idle hub rejects attach and new downstream work before upstream effects`(@TempDir root: Path) =
        runBlocking {
            val fixture = HubTestFixture(root)
            try {
                val peer = fixture.connect()
                fixture.bind(peer, "thread/start")
                val sealed = fixture.hub.upgrades.prepare(candidate, fixture.hub::upgradeBlockers).value()
                assertInstanceOf(UpgradeStatus.Sealed::class.java, sealed)
                assertNull(fixture.hub.attach("unused because admission precedes parsing and connect"))
                peer.session.accept(Json.encodeToString(StartRequest(9, "turn/start", ThreadParams("thread-1"))))
                val rejected = Json.parseToJsonElement(withTimeout(1000) { peer.session.output.receive() }).jsonObject
                assertEquals(
                    "ADMISSION_SEALED",
                    rejected.getValue("error").jsonObject.getValue("message").jsonPrimitive.content,
                )
                assertTrue(peer.upstream.sent.tryReceive().isFailure)
                assertEquals(0, fixture.invocations.get())
                fixture.hub.upgrades.cancel(sealed.request.id).value()
                peer.session.accept(Json.encodeToString(StartRequest(10, "turn/start", ThreadParams("thread-1"))))
                assertEquals(
                    "turn/start",
                    Json.parseToJsonElement(withTimeout(1000) { peer.upstream.sent.receive() })
                        .jsonObject
                        .getValue("method")
                        .jsonPrimitive
                        .content,
                )
                val pending = fixture.hub.upgrades.prepare(candidate, fixture.hub::upgradeBlockers).value()
                assertInstanceOf(UpgradeStatus.Pending::class.java, pending)
                assertTrue(
                    (pending as UpgradeStatus.Pending).blockers.toList().contains(UpgradeBlocker.REQUEST_PENDING)
                )
            } finally {
                fixture.hub.close()
            }
        }

    @Test
    fun `detaching frontend retains a pending upstream request`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val peer = fixture.connect()
            peer.session.accept(
                Json.encodeToString(ThreadStartRequest(12, "thread/start", WorkingDirectory(fixture.root.toString())))
            )
            withTimeout(1000) { peer.upstream.sent.receive() }
            peer.session.detach()
            assertFalse(peer.upstream.closed)
            val pending = fixture.hub.upgrades.prepare(candidate, fixture.hub::upgradeBlockers).value()
            assertInstanceOf(UpgradeStatus.Pending::class.java, pending)
            assertTrue((pending as UpgradeStatus.Pending).blockers.toList().contains(UpgradeBlocker.REQUEST_PENDING))
        } finally {
            fixture.hub.close()
        }
    }

    @Test
    fun `lost pending upstream request prevents a false quiescence proof`(@TempDir root: Path) = runBlocking {
        val fixture = HubTestFixture(root)
        try {
            val peer = fixture.connect()
            peer.session.accept(
                Json.encodeToString(ThreadStartRequest(11, "thread/start", WorkingDirectory(fixture.root.toString())))
            )
            withTimeout(1000) { peer.upstream.sent.receive() }
            peer.session.close()
            val pending = fixture.hub.upgrades.prepare(candidate, fixture.hub::upgradeBlockers).value()
            assertInstanceOf(UpgradeStatus.Pending::class.java, pending)
            assertTrue(
                (pending as UpgradeStatus.Pending).blockers.toList().contains(UpgradeBlocker.RECONCILIATION_REQUIRED)
            )
        } finally {
            fixture.hub.close()
        }
    }

    @Test
    fun `unopened frontend can seal without qualifying Codex`() = runBlocking {
        val frontend = DeferredBrokerFrontend { error("unexpected native qualification") }
        try {
            val sealed = frontend.upgrades.prepare(candidate, frontend::upgradeBlockers).value()
            assertInstanceOf(UpgradeStatus.Sealed::class.java, sealed)
            assertEquals(BrokerFrontendObservation.PENDING, frontend.observe())
            assertEquals(
                Refinement.Rejected(DaemonUpgradeFailure.ADMISSION_SEALED),
                frontend.upgrades.manage { error("unexpected effect") },
            )
        } finally {
            frontend.close()
        }
    }

    @Serializable private data class ThreadStartRequest(val id: Int, val method: String, val params: WorkingDirectory)

    @Serializable private data class WorkingDirectory(val cwd: String)

    @Serializable private data class ToolRequest(val id: Int, val method: String, val params: ToolParams)

    @Serializable
    private data class ToolParams(
        val threadId: String,
        val turnId: String,
        val callId: String,
        val namespace: String,
        val tool: String,
        val arguments: Arguments,
    )

    @Serializable private data class Arguments(val independent: Boolean)

    @Serializable private data class StatusRequest(val id: Int, val method: String)

    @Serializable private data class StartRequest(val id: Int, val method: String, val params: ThreadParams)

    @Serializable private data class ThreadParams(val threadId: String)

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> throw AssertionError("Unexpected rejection: $failure")
        }

    companion object {
        private val candidate = (UpgradeCandidate.admit("a".repeat(64)) as Refinement.Refined).value
    }
}
