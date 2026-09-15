package io.github.amichne.kast.appserver

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BrokerPublicEndpointTest {
    @Test
    fun `two physical installations sharing a host home contend for one public authority`(@TempDir temporary: Path) {
        val first = installedFixture(Files.createDirectory(temporary.resolve("first")))
        val second = installedFixture(Files.createDirectory(temporary.resolve("second")))
        val host = first.home.resolve(".codex")
        val a = resolvedCommand(first.copy(environment = first.environment + ("CODEX_HOME" to host.toString())))
        val b = resolvedCommand(second.copy(environment = second.environment + ("CODEX_HOME" to host.toString())))
        assertNotEquals(a.stateDirectory, b.stateDirectory)
        assertEquals(host.resolve("app-server-control/app-server-control.sock"), a.publicSocket)
        assertEquals(a.publicSocket, b.publicSocket)
        assertNotEquals(a.serviceLabel, b.serviceLabel)
        assertTrue(a.stateDirectory.startsWith(first.kast.parent.parent))
        assertFalse(a.stateDirectory.startsWith(host))
    }

    @Test
    fun `endpoint policy changes service identity and is forwarded to launchd`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)
        val canonical = resolvedCommand(fixture)
        val private =
            resolvedCommand(
                fixture.copy(environment = fixture.environment + ("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "private"))
            )
        assertTrue(canonical.publicEndpoint is BrokerPublicEndpoint.CodexControl)
        assertTrue(private.publicEndpoint is BrokerPublicEndpoint.Private)
        assertNotEquals(canonical.identity, private.identity)
        assertEquals(canonical.stateDirectory, private.stateDirectory)
        assertEquals(canonical.serviceLabel, private.serviceLabel)
        assertTrue(private.childEnvironment.assignments.contains("KAST_APP_SERVER_PUBLIC_ENDPOINT=private"))
    }

    @Test
    fun `unreachable incumbent canonical socket rejects before launchd submission`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)
        val home = Files.createTempDirectory(Path.of("/private/tmp"), "kast-o-")
        try {
            val command =
                resolvedCommand(fixture.copy(environment = fixture.environment + ("CODEX_HOME" to home.toString())))
            Files.createDirectory(command.publicSocket.parent)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use {
                it.bind(UnixDomainSocketAddress.of(command.publicSocket))
            }
            val inode = Files.getAttribute(command.publicSocket, "unix:ino")
            val calls = mutableListOf<String>()
            val host =
                MacOsPersistentBrokerServiceHost(
                    launchctl =
                        LaunchctlInvoker { arguments, _ ->
                            calls += arguments[1]
                            if (arguments[1] == "list") LaunchctlInvocation.Absent else LaunchctlInvocation.Rejected
                        },
                    socketProbe = BrokerSocketProbe { BrokerSocketReachability.UNREACHABLE },
                )
            assertEquals(
                PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.PUBLIC_SOCKET_OWNED),
                host.ensure(command),
            )
            assertEquals(listOf("list"), calls)
            assertEquals(inode, Files.getAttribute(command.publicSocket, "unix:ino"))
        } finally {
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun installedFixture(root: Path): EndpointFixture {
        val home = Files.createDirectories(root.resolve("home")).toRealPath()
        val bin = Files.createDirectories(root.resolve("product/bin")).toRealPath()
        for (name in listOf("kast", "codex")) {
            val path = Files.writeString(bin.resolve(name), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
        return EndpointFixture(bin.resolve("kast"), home, mapOf("PATH" to bin.toString()))
    }

    private fun resolvedCommand(fixture: EndpointFixture): BrokerServiceLaunchCommand =
        when (val result = BrokerServiceLaunchCommand.resolve(fixture.kast, fixture.home, fixture.environment)) {
            is BrokerServiceLaunchCommandResolution.Resolved -> result.command
            is BrokerServiceLaunchCommandResolution.Rejected -> error(result.failure)
        }

    private data class EndpointFixture(val kast: Path, val home: Path, val environment: Map<String, String>)
}
