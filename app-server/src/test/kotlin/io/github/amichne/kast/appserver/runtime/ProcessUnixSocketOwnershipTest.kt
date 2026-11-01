package io.github.amichne.kast.appserver.runtime

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessUnixSocketOwnershipTest {
    @Test
    fun `macOS and Linux records preserve the exact process and stream socket`() {
        val target = Path.of("/tmp/kast-owner.sock")
        for (name in listOf("n$target", "n$target type=STREAM")) {
            assertTrue(ProcessUnixSocketOwnership.matchesOwnerRecords(42, target, listOf("p42", "f7", name)))
        }
    }

    @Test
    fun `foreign processes paths and unknown socket metadata remain rejected`() {
        val target = Path.of("/tmp/kast-owner.sock")
        for (records in
            listOf(
                listOf("p43", "f7", "n$target type=STREAM"),
                listOf("p42", "f7", "n$target.other type=STREAM"),
                listOf("p42", "f7", "n$target type=DGRAM"),
                listOf("p42", "f7", "n$target type=STREAM extra"),
                listOf("p42", "f7", "n/tmp/other.sock", "p43", "f8", "n$target"),
            )) {
            assertFalse(ProcessUnixSocketOwnership.matchesOwnerRecords(42, target, records))
        }
    }

    @Test
    fun `the current process owns its real Unix socket`() {
        val directory = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kpo-")
        val socket = directory.resolve("s")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(socket))
                assertTrue(ProcessUnixSocketOwnership.holds(ProcessHandle.current().pid(), socket, 5_000))
                assertFalse(ProcessUnixSocketOwnership.holds(0, socket, 5_000))
            }
        } finally {
            Files.deleteIfExists(socket)
            Files.delete(directory)
        }
    }
}
