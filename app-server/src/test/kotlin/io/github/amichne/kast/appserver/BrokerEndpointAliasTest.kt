package io.github.amichne.kast.appserver

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import io.github.amichne.kast.appserver.runtime.BrokerSocketPath
import io.github.amichne.kast.appserver.runtime.BrokerSocketRoute
import io.github.amichne.kast.appserver.runtime.OwnedUnixSocket
import io.github.amichne.kast.appserver.runtime.UnixSocketOwnershipLeaseAcquisition
import io.github.amichne.kast.appserver.runtime.UnixSocketPathOwnership
import io.github.amichne.kast.appserver.runtime.UnixSocketPathPreparation
import io.github.amichne.kast.kernel.Validation
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

class BrokerEndpointAliasTest {
    @Test
    fun `long physical installation selects a representable private socket`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("physical-version-" + "v".repeat(100))).toRealPath()
        val layout = BrokerInstallationLayout.from(root.resolve("bin/kast"), root.resolve("host"))
        assertTrue(layout.publicSocket.toString().toByteArray(StandardCharsets.UTF_8).size < 104,
            "selected Unix transport must fit while physical state stays under the version root")
        assertTrue(layout.run.startsWith(root))
    }

    @Test
    @Timeout(10)
    fun `native socket exchange through alias retains its inode under the physical version`(@TempDir temporary: Path) {
        val run = privateRun(temporary)
        val socket = (BrokerSocketPath.prepareInstalled(run.resolve("c.sock")) as Validation.Validated).value
        val receipt = (socket.route as BrokerSocketRoute.Aliased).receipt
        try {
            val acquisition = UnixSocketPathOwnership.acquireLease(socket)
            assertTrue(acquisition is UnixSocketOwnershipLeaseAcquisition.Acquired)
            (acquisition as UnixSocketOwnershipLeaseAcquisition.Acquired).lease.use {
                assertEquals(UnixSocketPathPreparation.PREPARED, UnixSocketPathOwnership.prepare(socket))
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                    server.bind(UnixDomainSocketAddress.of(socket.path))
                    val owned = requireNotNull(OwnedUnixSocket.capture(socket))
                    try {
                        assertEquals(BrokerSocketPathObservation.Socket, JdkBrokerSocketPathObserver.observe(socket.path))
                        assertEquals(Files.getAttribute(run.resolve("c.sock"), "unix:ino", LinkOption.NOFOLLOW_LINKS),
                            Files.getAttribute(socket.path, "unix:ino", LinkOption.NOFOLLOW_LINKS))
                        SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                            client.connect(UnixDomainSocketAddress.of(socket.path))
                            server.accept().use { peer ->
                                client.write(ByteBuffer.wrap(byteArrayOf(42)))
                                val received = ByteBuffer.allocate(1)
                                assertEquals(1, peer.read(received))
                                assertEquals(42, received.array()[0].toInt())
                                peer.write(ByteBuffer.wrap(byteArrayOf(43)))
                                received.clear()
                                assertEquals(1, client.read(received))
                                assertEquals(43, received.array()[0].toInt())
                            }
                        }
                    } finally {
                        owned.retire()
                    }
                    assertFalse(Files.exists(run.resolve("c.sock")))
                }
            }
        } finally {
            retireTestAlias(receipt)
        }
    }

    @Test
    fun `replaced alias rejects the retained proof without touching its foreign target`(@TempDir temporary: Path) {
        val run = privateRun(temporary)
        val socket = (BrokerSocketPath.prepareInstalled(run.resolve("c.sock")) as Validation.Validated).value
        val receipt = (socket.route as BrokerSocketRoute.Aliased).receipt
        val held = receipt.alias.resolveSibling(receipt.alias.fileName.toString() + ".held")
        val foreign = Files.createDirectory(temporary.resolve("foreign")).toRealPath()
        Files.writeString(foreign.resolve("c.sock"), "foreign state")
        Files.move(receipt.alias, held)
        Files.createSymbolicLink(receipt.alias, foreign)
        try {
            assertTrue(receipt.validate() is Validation.Rejected)
            assertEquals(UnixSocketPathPreparation.PARENT_REJECTED, UnixSocketPathOwnership.prepare(socket))
            assertEquals(BrokerSocketPathObservation.Rejected, JdkBrokerSocketPathObserver.observe(socket.path))
            assertEquals("foreign state", Files.readString(foreign.resolve("c.sock")))
        } finally {
            Files.delete(receipt.alias)
            Files.move(held, receipt.alias)
            retireTestAlias(receipt)
        }
    }

    @Test
    fun `same target with a replaced alias inode is rejected`(@TempDir temporary: Path) {
        val run = privateRun(temporary)
        val socket = (BrokerSocketPath.prepareInstalled(run.resolve("c.sock")) as Validation.Validated).value
        val receipt = (socket.route as BrokerSocketRoute.Aliased).receipt
        val held = receipt.alias.resolveSibling(receipt.alias.fileName.toString() + ".held")
        Files.move(receipt.alias, held)
        Files.createSymbolicLink(receipt.alias, run)
        try {
            assertTrue(socket.revalidate() is Validation.Rejected)
            assertEquals(BrokerSocketPathObservation.Rejected, JdkBrokerSocketPathObserver.observe(socket.path))
        } finally {
            Files.delete(receipt.alias)
            Files.move(held, receipt.alias)
            retireTestAlias(receipt)
        }
    }

    @Test
    fun `planned absent alias is observed passively without creation`(@TempDir temporary: Path) {
        val physical = temporary.toRealPath().resolve("version-" + "x".repeat(100))
        val layout = BrokerInstallationLayout.from(physical.resolve("bin/kast"), temporary)
        assertEquals(BrokerSocketPathObservation.Absent, JdkBrokerSocketPathObserver.observe(layout.publicSocket))
        assertFalse(Files.exists(layout.publicSocket.parent, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(physical))
    }

    private fun privateRun(temporary: Path): Path {
        val run = Files.createDirectories(temporary.resolve("version-" + "v".repeat(100)).resolve("state/run")).toRealPath()
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"))
        return run
    }

    private fun retireTestAlias(receipt: BrokerEndpointAliasReceipt) {
        assertTrue(receipt.validate() is Validation.Validated)
        Files.delete(receipt.alias)
    }
}
