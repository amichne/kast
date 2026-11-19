package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases
import io.github.amichne.kast.distribution.managed.endpoint.MAXIMUM_UNIX_SOCKET_PATH_BYTES
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** The alias threshold is the JDK's own bind limit, so no direct socket path is one the JVM cannot bind. */
class UnixSocketPathLimitTest {
    private fun socket(bytes: Int): Path {
        val directory = "/tmp/" + "x".repeat(bytes - "/tmp/".length - "/c.sock".length)
        return Path.of(directory, "c.sock").also { check(it.toString().length == bytes) }
    }

    @Test
    fun `the longest direct path binds and the next byte is aliased`() {
        val longest = socket(MAXIMUM_UNIX_SOCKET_PATH_BYTES)
        assertEquals(longest, InstalledEndpointAliases.transportPath(longest))
        val aliased = socket(MAXIMUM_UNIX_SOCKET_PATH_BYTES + 1)
        assertNotEquals(aliased, InstalledEndpointAliases.transportPath(aliased))
        Files.createDirectories(longest.parent)
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.bind(UnixDomainSocketAddress.of(longest))
            }
        } finally {
            Files.deleteIfExists(longest)
            Files.deleteIfExists(longest.parent)
        }
    }
}
