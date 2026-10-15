package io.github.amichne.kast.distribution.cli

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResetSocketPeerRejectionTest {
    @Test
    fun `shared socket peer proof rejects an exhausted acceptance budget`() = withListener { pid, socket ->
        val caller = ProcessHandle.current().pid()
        val baseline = "p$caller\nf100\nd0xaaaa\nn->(none)\n"
        val probe = baseline + "f101\nd0xbbbb\nn->0xcccc\n"
        val documents = ArrayDeque(listOf(baseline, probe, probe, probe))
        val observations = mutableListOf<ResetProcessObservation>()
        assertEquals(
            ResetSocketPeerProof.REJECTED,
            ResetSocketPeerOwnership.prove(
                socket,
                pid,
                "/usr/sbin/lsof",
                effects = ResetPeerEffects(observe = observations::add),
            ) {
                assertTrue(documents.isNotEmpty(), "unexpected descriptor inspection")
                ProcessObservation.Exited(0, documents.removeFirst())
            },
        )
        assertTrue(documents.isEmpty(), "unconsumed descriptor inspection")
        assertEquals(
            listOf(
                ResetProcessResult.PEER_PENDING,
                ResetProcessResult.PEER_PENDING,
                ResetProcessResult.PEER_PENDING,
                ResetProcessResult.OBSERVATION_REJECTED,
            ),
            observations.map { it.outcome },
        )
    }

    @Test
    fun `shared socket peer proof rejects a changed probe or a wrong-path owner immediately`() =
        withListener { pid, socket ->
            val caller = ProcessHandle.current().pid()
            val baseline = "p$caller\nf100\nd0xaaaa\nn->(none)\n"
            val probe = baseline + "f101\nd0xbbbb\nn->0xcccc\n"
            val invalid =
                listOf(
                    baseline + "f101\nd0xbbbb\nn->0xffff\np${pid}\nf50\nd0xffff\nn$socket\n",
                    probe + "p${pid}\nf50\nd0xcccc\nn/foreign/socket\n",
                    "p$caller\nf101\nd0xbbbb\nn->0xcccc\np${pid}\nf50\nd0xcccc\nn$socket\n",
                    probe + "p${pid}\nf50\nd0xcccc\nn$socket\nf51\nd0xcccc\nn$socket\n",
                )
            for (document in invalid) {
                val documents = ArrayDeque(listOf(baseline, probe, document))
                val observations = mutableListOf<ResetProcessObservation>()
                assertEquals(
                    ResetSocketPeerProof.REJECTED,
                    ResetSocketPeerOwnership.prove(
                        socket,
                        pid,
                        "/usr/sbin/lsof",
                        effects = ResetPeerEffects(observe = observations::add),
                    ) {
                        assertTrue(documents.isNotEmpty(), "unexpected descriptor inspection")
                        ProcessObservation.Exited(0, documents.removeFirst())
                    },
                )
                assertTrue(documents.isEmpty(), "unconsumed descriptor inspection")
                assertEquals(
                    listOf(ResetProcessResult.PEER_PENDING, ResetProcessResult.PEER_REJECTED),
                    observations.map { it.outcome },
                )
            }
        }

    @Test
    fun `shared socket peer proof stops before another inspection when the inode changes`() =
        withListener { pid, socket ->
            val caller = ProcessHandle.current().pid()
            val key = Files.readAttributes(socket, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
            val baseline = "p$caller\nf100\nd0xaaaa\nn->(none)\n"
            val probe = baseline + "f101\nd0xbbbb\nn->0xcccc\n"
            val documents = ArrayDeque(listOf(baseline, probe))
            val observations = mutableListOf<ResetProcessObservation>()
            assertEquals(
                ResetSocketPeerProof.REJECTED,
                ResetSocketPeerOwnership.prove(
                    socket,
                    pid,
                    "/usr/sbin/lsof",
                    effects =
                        ResetPeerEffects(
                            unchanged = {
                                Files.readAttributes(socket, java.nio.file.attribute.BasicFileAttributes::class.java)
                                    .fileKey() == key
                            },
                            observe = observations::add,
                        ),
                ) {
                    assertTrue(documents.isNotEmpty(), "unexpected descriptor inspection")
                    val document = documents.removeFirst()
                    if (documents.isEmpty()) {
                        Files.delete(socket)
                        Files.writeString(socket, "foreign replacement")
                    }
                    ProcessObservation.Exited(0, document)
                },
            )
            assertTrue(documents.isEmpty(), "unconsumed descriptor inspection")
            assertEquals(listOf(ResetProcessResult.SOCKET_CHANGED), observations.map { it.outcome })
            assertEquals("foreign replacement", Files.readString(socket))
        }

    private fun withListener(action: (Long, Path) -> Unit) {
        val owned = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kr-")
        try {
            val socket = owned.resolve("peer.sock")
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                listener.bind(UnixDomainSocketAddress.of(socket))
                action(123, socket)
            }
        } finally {
            Files.walkFileTree(owned, ResetDeletingVisitor)
        }
    }
}
