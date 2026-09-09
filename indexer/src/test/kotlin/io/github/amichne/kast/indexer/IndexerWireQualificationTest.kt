package io.github.amichne.kast.indexer

import io.github.amichne.kast.distribution.contract.WireRuntimeIdentity
import io.github.amichne.kast.distribution.contract.WireRuntimeQualification
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.KastRuntimeDispatch
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IndexerWireQualificationTest {
    @Test fun `wrong attempt never reaches semantic dispatch`(@TempDir directory: Path) = fixture(directory) { channel, identity, calls ->
        val wrong = identity(identity.root, "22222222-2222-4222-8222-222222222222")
        IndexerWireFrameCodec.write(channel, WireRuntimeQualification.request(wrong))
        assertEquals(IndexerFrameRead.EndOfStream, IndexerWireFrameCodec.read(channel))
        assertEquals(0, calls.get())
    }
    @Test fun `qualification and request share one exact connection`(@TempDir directory: Path) = fixture(directory) { channel, identity, calls ->
        IndexerWireFrameCodec.write(channel, WireRuntimeQualification.request(identity))
        assertEquals(IndexerFrameRead.Received(WireRuntimeQualification.response(identity)), IndexerWireFrameCodec.read(channel))
        assertEquals(0, calls.get())
        IndexerWireFrameCodec.write(channel, "semantic")
        assertEquals(IndexerFrameRead.Received("answer"), IndexerWireFrameCodec.read(channel))
        assertEquals(1, calls.get())
    }
    private fun fixture(directory: Path, test: (SocketChannel, WireRuntimeIdentity, AtomicInteger) -> Unit) {
        val root = directory.toRealPath()
        val socket = directory.resolve("s")
        val identity = identity(root, "11111111-1111-4111-8111-111111111111")
        val options = (IndexerLaunchOptions.admit(listOf("kast-indexer", "--workspace-root=$root", "--socket-path=$socket",
            "--runtime-id=${identity.runtimeId}")) as IndexerLaunchAdmission.Admitted).options
        val endpoint = (PreparedIndexerEndpoint.prepare(options) as IndexerEndpointPreparation.Prepared).endpoint
        val calls = AtomicInteger()
        val host = KastIndexerHost { calls.incrementAndGet(); KastRuntimeDispatch.Responded("answer") }
        val transport = (InstalledIndexerTransport.activate(endpoint, host,
            authority = IndexerWireAuthority.Installed(identity)) as IndexerTransportActivation.Activated).transport
        val executor = Executors.newSingleThreadExecutor()
        transport.use {
            try {
                val accept = executor.submit { transport.serveNext() }
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socket))
                    test(channel, identity, calls)
                }
                accept.get(2, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    private fun identity(root: Path, attempt: String): WireRuntimeIdentity =
        (WireRuntimeIdentity.admit(root, "sha256:${"a".repeat(64)}",
            (SemanticRuntimeBootstrapAttemptId.admit(attempt) as Refinement.Refined).value) as Refinement.Refined).value
}
