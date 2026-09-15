package io.github.amichne.kast.appserver.runtime

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnixSocketOwnershipTest {
    @Test
    fun `connection refusal does not authorize deletion of an unknown canonical socket`() {
        val directory = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "ko-")
        val socket = directory.resolve("app-server-control.sock")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.bind(UnixDomainSocketAddress.of(socket))
            }
            val identity = Files.getAttribute(socket, "unix:ino")
            assertEquals(UnixSocketPathPreparation.REJECTED, UnixSocketPathOwnership.prepare(socket))
            assertTrue(Files.exists(socket))
            assertEquals(identity, Files.getAttribute(socket, "unix:ino"))
        } finally {
            Files.deleteIfExists(socket)
            Files.delete(directory)
        }
    }
}
