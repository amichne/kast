package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.WireRuntimeIdentity
import io.github.amichne.kast.distribution.contract.WireRuntimeQualification
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("native")
class WireQualificationTest {
    @Test
    fun `reused qualified session sends qualification only once`() = fixture { session, peer, identity ->
        WireFrameCodec.write(peer, WireRuntimeQualification.response(identity))
        repeat(2) { WireFrameCodec.write(peer, "answer") }
        repeat(2) { assertEquals(WireExchange.Received("answer"), session.exchange("semantic", identity)) }
        assertEquals(WireFrameRead.Received(WireRuntimeQualification.request(identity)), WireFrameCodec.read(peer))
        repeat(2) { assertEquals(WireFrameRead.Received("semantic"), WireFrameCodec.read(peer)) }
    }

    @Test
    fun `exact peer qualification precedes semantic payload on the connected channel`() =
        fixture { session, peer, identity ->
            WireFrameCodec.write(peer, WireRuntimeQualification.response(identity))
            WireFrameCodec.write(peer, "answer")
            assertEquals(WireExchange.Received("answer"), session.exchange("semantic", identity))
            assertEquals(WireFrameRead.Received(WireRuntimeQualification.request(identity)), WireFrameCodec.read(peer))
            assertEquals(WireFrameRead.Received("semantic"), WireFrameCodec.read(peer))
        }

    @Test
    fun `foreign qualification rejects before semantic payload is sent`() = fixture { session, peer, identity ->
        WireFrameCodec.write(peer, "{}")
        assertEquals(
            WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER),
            session.exchange("semantic-secret", identity),
        )
        session.close()
        assertEquals(WireFrameRead.Received(WireRuntimeQualification.request(identity)), WireFrameCodec.read(peer))
        assertTrue(WireFrameCodec.read(peer) is WireFrameRead.Rejected)
    }

    private fun fixture(test: (WireSession, SocketChannel, WireRuntimeIdentity) -> Unit) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kwq-")
        val socket = root.resolve("s")
        val identity =
            (WireRuntimeIdentity.admit(
                    root,
                    "sha256:${"a".repeat(64)}",
                    (SemanticRuntimeBootstrapAttemptId.admit("11111111-1111-4111-8111-111111111111")
                            as Refinement.Refined)
                        .value,
                ) as Refinement.Refined)
                .value
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))
                SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    client.connect(UnixDomainSocketAddress.of(socket))
                    server.accept().use { peer ->
                        WireSession(client, (ElapsedTimeLimitMillis.parse(1_000) as Refinement.Refined).value).use {
                            session ->
                            test(session, peer, identity)
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(socket)
            Files.deleteIfExists(root)
        }
    }
}
