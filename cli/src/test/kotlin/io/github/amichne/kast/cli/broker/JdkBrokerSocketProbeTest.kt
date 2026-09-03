package io.github.amichne.kast.cli.broker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread

class JdkBrokerSocketProbeTest {
    @Test
    fun `real JDK path observation distinguishes absent socket and rejected paths`(
        @TempDir temporary: Path,
    ) {
        val admittedParent = Files.createDirectory(temporary.resolve("admitted")).toRealPath()
        val missingParentSocket = admittedParent.resolve("missing/broker.sock")
        assertEquals(
            BrokerSocketPathObservation.Absent,
            JdkBrokerSocketPathObserver.observe(missingParentSocket),
        )
        assertEquals(
            BrokerSocketReachability.UNREACHABLE,
            JdkBrokerSocketProbe.probe(missingParentSocket),
        )

        val regularFile = Files.writeString(admittedParent.resolve("regular"), "not a socket")
        assertEquals(
            BrokerSocketPathObservation.WrongType,
            JdkBrokerSocketPathObserver.observe(regularFile),
        )
        assertEquals(
            BrokerSocketReachability.REJECTED,
            JdkBrokerSocketProbe.probe(regularFile),
        )

        val symbolicLink = Files.createSymbolicLink(
            admittedParent.resolve("link"),
            regularFile.fileName,
        )
        assertEquals(
            BrokerSocketPathObservation.WrongType,
            JdkBrokerSocketPathObserver.observe(symbolicLink),
        )
        assertEquals(
            BrokerSocketReachability.REJECTED,
            JdkBrokerSocketProbe.probe(symbolicLink),
        )

        val inaccessibleParent = Files.createDirectory(admittedParent.resolve("inaccessible"))
        Files.setPosixFilePermissions(
            inaccessibleParent,
            PosixFilePermissions.fromString("---------"),
        )
        try {
            assertEquals(
                BrokerSocketPathObservation.Rejected,
                JdkBrokerSocketPathObserver.observe(inaccessibleParent.resolve("broker.sock")),
            )
            assertEquals(
                BrokerSocketReachability.REJECTED,
                JdkBrokerSocketProbe.probe(inaccessibleParent.resolve("broker.sock")),
            )
        } finally {
            Files.setPosixFilePermissions(
                inaccessibleParent,
                PosixFilePermissions.fromString("rwx------"),
            )
        }
    }

    @Test
    fun `real JDK probe distinguishes broker protocol from stale and absent sockets`(
        @TempDir temporary: Path,
    ) {
        val admittedParent = Files.createDirectory(temporary.resolve("sockets")).toRealPath()
        val absent = admittedParent.resolve("absent.sock")
        assertEquals(BrokerSocketReachability.UNREACHABLE, JdkBrokerSocketProbe.probe(absent))

        val live = admittedParent.resolve("live.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
            listener.bind(UnixDomainSocketAddress.of(live))
            val acceptor = thread(start = true) { listener.accept().use { } }
            assertEquals(
                BrokerSocketPathObservation.Socket,
                JdkBrokerSocketPathObserver.observe(live),
            )
            assertEquals(BrokerSocketReachability.REJECTED, JdkBrokerSocketProbe.probe(live))
            acceptor.join(5_000)
        }

        val stale = admittedParent.resolve("stale.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
            listener.bind(UnixDomainSocketAddress.of(stale))
        }
        assertEquals(BrokerSocketReachability.UNREACHABLE, JdkBrokerSocketProbe.probe(stale))
        assertEquals(
            BrokerSocketReachability.REJECTED,
            JdkBrokerSocketProbe.probe(Files.writeString(admittedParent.resolve("regular"), "x")),
        )
    }
}
