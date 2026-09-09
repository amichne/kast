package io.github.amichne.kast.indexer

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.KastRuntimeDispatch
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IndexerRequestLifetimeTest {
    @Test fun `peer disconnect cancels only its executing request before dispatch deadline`(@TempDir root: Path) {
        val entered = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        fixture(root, KastIndexerHost {
            entered.countDown()
            try { release.await(); KastRuntimeDispatch.Responded("late") }
            finally { retired.countDown() }
        }, policy = policy(dispatch = 10_000, retirement = 1_000)).use { fixture ->
            try {
                fixture.executor.submit { fixture.transport.serveNext() }
                val client = fixture.connect()
                IndexerWireFrameCodec.write(client, "request")
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                client.close()
                assertTrue(retired.await(1, TimeUnit.SECONDS), "closed peer did not cancel its semantic request")
                assertTrue(fixture.activities.contains(IndexerRequestActivity(IndexerRequestStage.DISPATCH, IndexerRequestOutcome.CANCELLED)))
            } finally { release.complete(Unit) }
        }
    }
    @Test fun `silent connection does not monopolize admission for another client`(@TempDir root: Path) {
        fixture(root).use { fixture ->
            val silent = fixture.connect()
            val serving = fixture.executor.submit { repeat(2) { fixture.transport.serveNext() } }
            val responsive = fixture.connect()
            IndexerWireFrameCodec.write(responsive, "request")
            val response = fixture.executor.submit<IndexerFrameRead> { IndexerWireFrameCodec.read(responsive) }
            assertEquals(IndexerFrameRead.Received("response:request"), response.get(2, TimeUnit.SECONDS))
            assertTrue(fixture.activities.contains(IndexerRequestActivity(IndexerRequestStage.DISPATCH, IndexerRequestOutcome.COMPLETED)))
            assertTrue(silent.isOpen)
            responsive.close()
            silent.close()
            serving.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `partial frame expires with bounded typed evidence`(@TempDir root: Path) {
        fixture(root).use { fixture ->
            fixture.executor.submit { fixture.transport.serveNext() }
            val client = fixture.connect()
            client.write(ByteBuffer.wrap(byteArrayOf(0, 0)))
            val closed = fixture.executor.submit<Int> { client.read(ByteBuffer.allocate(1)) }
            assertEquals(-1, closed.get(2, TimeUnit.SECONDS))
            assertTrue(fixture.activities.contains(IndexerRequestActivity(IndexerRequestStage.FRAME_READ, IndexerRequestOutcome.DEADLINE_EXCEEDED)))
        }
    }

    @Test fun `elapsed dispatch cancels the host and proves semantic retirement`(@TempDir root: Path) {
        val release = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val calls = AtomicInteger()
        fixture(root, KastIndexerHost {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                try { release.await(); KastRuntimeDispatch.Responded("response") }
                finally { retired.countDown() }
            } else KastRuntimeDispatch.Responded("next")
        }).use { fixture ->
            try {
                fixture.executor.submit { repeat(2) { fixture.transport.serveNext() } }
                val client = fixture.connect()
                IndexerWireFrameCodec.write(client, "request")
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertTrue(retired.await(2, TimeUnit.SECONDS), "elapsed request did not cancel the host")
                val closed = fixture.executor.submit<Int> { client.read(ByteBuffer.allocate(1)) }
                assertEquals(-1, closed.get(2, TimeUnit.SECONDS))
                assertTrue(fixture.activities.contains(IndexerRequestActivity(IndexerRequestStage.DISPATCH, IndexerRequestOutcome.DEADLINE_EXCEEDED)))
                assertTrue(fixture.activities.contains(IndexerRequestActivity(IndexerRequestStage.RETIREMENT, IndexerRequestOutcome.COMPLETED)))
                val next = fixture.connect()
                IndexerWireFrameCodec.write(next, "next")
                val response = fixture.executor.submit<IndexerFrameRead> { IndexerWireFrameCodec.read(next) }
                assertEquals(IndexerFrameRead.Received("next"), response.get(2, TimeUnit.SECONDS))
            } finally { release.complete(Unit) }
        }
    }

    @Test fun `connection capacity rejects an excess incomplete client`(@TempDir root: Path) {
        fixture(root, policy = policy(frameRead = 3_000)).use { fixture ->
            fixture.executor.submit { repeat(3) { fixture.transport.serveNext() } }
            fixture.connect()
            fixture.connect()
            val excess = fixture.connect()
            val closed = fixture.executor.submit<Int> { excess.read(ByteBuffer.allocate(1)) }
            assertEquals(-1, closed.get(2, TimeUnit.SECONDS))
            assertTrue(fixture.activities.contains(IndexerRequestActivity(IndexerRequestStage.CONNECTION_ADMISSION, IndexerRequestOutcome.CAPACITY_EXCEEDED)))
        }
    }

    @Test fun `close cancels its semantic job closes incomplete peers and ends the accept loop`(@TempDir root: Path) {
        val release = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val admitted = CountDownLatch(2)
        fixture(root, KastIndexerHost {
            entered.countDown()
            try { release.await(); KastRuntimeDispatch.Responded("late") }
            finally { retired.countDown() }
        }, policy = policy(frameRead = 3_000, dispatch = 3_000, retirement = 1_000), onActivity = {
            if (it.stage == IndexerRequestStage.CONNECTION_ADMISSION && it.outcome == IndexerRequestOutcome.COMPLETED) admitted.countDown()
        }).use { fixture ->
            try {
                val serving = fixture.executor.submit { fixture.transport.serve() }
                val active = fixture.connect()
                IndexerWireFrameCodec.write(active, "request")
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val incomplete = fixture.connect()
                assertTrue(admitted.await(1, TimeUnit.SECONDS))
                fixture.transport.close()
                assertTrue(retired.await(1, TimeUnit.SECONDS))
                serving.get(2, TimeUnit.SECONDS)
                assertEquals(-1, incomplete.read(ByteBuffer.allocate(1)))
                assertFalse(Files.exists(fixture.socket))
                assertTrue(fixture.activities.contains(IndexerRequestActivity(IndexerRequestStage.TRANSPORT_CLOSE, IndexerRequestOutcome.COMPLETED)))
            } finally { release.complete(Unit) }
        }
    }

    @Test fun `unproven retirement fences the semantic lane against another request`(@TempDir root: Path) {
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val recovery = CountDownLatch(1)
        fixture(root, KastIndexerHost {
            calls.incrementAndGet()
            withContext(NonCancellable) { release.await() }
            KastRuntimeDispatch.Responded("late")
        }, onActivity = { if (it.outcome == IndexerRequestOutcome.RECOVERY_REQUIRED) recovery.countDown() }).use { fixture ->
            try {
                fixture.executor.submit { repeat(2) { fixture.transport.serveNext() } }
                val first = fixture.connect()
                IndexerWireFrameCodec.write(first, "first")
                assertTrue(recovery.await(2, TimeUnit.SECONDS), "unretired work did not fence its workspace")
                val second = fixture.connect()
                IndexerWireFrameCodec.write(second, "second")
                val closed = fixture.executor.submit<Int> { second.read(ByteBuffer.allocate(1)) }
                assertEquals(-1, closed.get(2, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
                assertFalse(fixture.activities.any { it.toString().contains("first") || it.toString().contains("second") })
            } finally { release.complete(Unit) }
        }
    }

    private fun fixture(
        root: Path,
        host: KastIndexerHost = KastIndexerHost { KastRuntimeDispatch.Responded("response:$it") },
        policy: IndexerRequestPolicy = policy(),
        onActivity: (IndexerRequestActivity) -> Unit = {},
    ): Fixture {
        val workspace = Files.createDirectory(root.resolve("workspace")).toRealPath()
        val socket = root.resolve("s")
        val options = when (val admitted = IndexerLaunchOptions.admit(listOf("kast-indexer", "--workspace-root=$workspace",
            "--socket-path=$socket", "--runtime-id=sha256:${"a".repeat(64)}"))) {
            is IndexerLaunchAdmission.Admitted -> admitted.options
            is IndexerLaunchAdmission.Rejected -> error("${admitted.failures}")
        }
        val endpoint = when (val prepared = PreparedIndexerEndpoint.prepare(options)) {
            is IndexerEndpointPreparation.Prepared -> prepared.endpoint
            is IndexerEndpointPreparation.Rejected -> error("${prepared.failure}")
        }
        val activities = CopyOnWriteArrayList<IndexerRequestActivity>()
        val transport = when (val activated = InstalledIndexerTransport.activate(endpoint, host, policy,
            IndexerRequestActivitySink { activities.add(it); onActivity(it) }, authority = IndexerWireAuthority.Fixture)) {
            is IndexerTransportActivation.Activated -> activated.transport
            is IndexerTransportActivation.Rejected -> error("${activated.failure}")
        }
        return Fixture(socket, transport, activities)
    }

    private fun policy(frameRead: Long = 150, dispatch: Long = 100, retirement: Long = 100): IndexerRequestPolicy =
        IndexerRequestPolicy(connections = when (val admitted = IndexerConnectionLimit.admit(2)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("${admitted.failure}")
        }, frameRead = millis(frameRead), frameWrite = millis(150), dispatch = millis(dispatch), retirement = millis(retirement))

    private class Fixture(val socket: Path, val transport: InstalledIndexerTransport, val activities: CopyOnWriteArrayList<IndexerRequestActivity>) : AutoCloseable {
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "indexer-request-test").apply { isDaemon = true } }
        private val clients = mutableListOf<SocketChannel>()
        fun connect(): SocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            clients.add(this)
            connect(UnixDomainSocketAddress.of(socket))
        }
        override fun close() {
            clients.forEach { it.close() }
            transport.close()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun millis(value: Long): ElapsedTimeLimitMillis = when (val admitted = ElapsedTimeLimitMillis.parse(value)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("${admitted.failure}")
    }
}
