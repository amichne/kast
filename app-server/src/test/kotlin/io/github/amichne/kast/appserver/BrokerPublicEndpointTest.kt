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
    fun `incumbent canonical endpoint rejects before protocol probing and permits startup after removal`(
        @TempDir temporary: Path
    ) {
        val fixture = installedFixture(temporary)
        val command =
            resolvedCommand(
                fixture.copy(environment = fixture.environment + ("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control"))
            )
        assertTrue(command.publicEndpoint is BrokerPublicEndpoint.CodexControl)
        Files.createDirectories(command.publicSocket.parent)
        // A foreign endpoint need not speak the Kast status protocol to reserve this path.
        Files.writeString(command.publicSocket, "incumbent")
        var present = false
        var probes = 0
        var submissions = 0
        val host =
            MacOsPersistentBrokerServiceHost(
                launchctl =
                    LaunchctlInvoker { arguments, _ ->
                        when (arguments[1]) {
                            "list" -> if (present) LaunchctlInvocation.Completed else LaunchctlInvocation.Absent
                            "bootstrap" -> {
                                submissions += 1
                                present = true
                                Files.createDirectories(command.readinessFile.parent)
                                Files.writeString(
                                    command.readinessFile,
                                    kotlinx.serialization.json
                                        .Json {
                                            classDiscriminator = "state"
                                            encodeDefaults = true
                                        }
                                        .encodeToString<BrokerServiceStateDocument>(
                                            BrokerServiceStateDocument.Ready(
                                                BROKER_SERVICE_STATE_SCHEMA_VERSION,
                                                command.identity.value,
                                                "123e4567-e89b-42d3-a456-426614174000",
                                                VENDORED_BROKER_VERSION,
                                            )
                                        ),
                                )
                                LaunchctlInvocation.Completed
                            }
                            else -> error("Unexpected lifecycle operation")
                        }
                    },
                socketProbe =
                    BrokerSocketProbe {
                        probes += 1
                        when {
                            present -> BrokerSocketReachability.REACHABLE
                            Files.exists(command.publicSocket) -> BrokerSocketReachability.REJECTED
                            else -> BrokerSocketReachability.UNREACHABLE
                        }
                    },
                sleeper = BrokerServiceSleeper { BrokerServiceSleep.CONTINUE },
            )
        assertEquals(
            PersistentBrokerServiceAdmission.Rejected(PersistentBrokerServiceFailure.PUBLIC_SOCKET_OWNED),
            host.ensure(command),
        )
        assertEquals(0, probes)
        assertEquals(0, submissions)
        assertEquals("incumbent", Files.readString(command.publicSocket))
        Files.delete(command.publicSocket)
        assertEquals(PersistentBrokerServiceAdmission.Ready, host.ensure(command))
        assertEquals(1, submissions)
        assertTrue(probes > 0)
    }

    @Test
    fun `default endpoint is installation owned despite an occupied native Codex endpoint`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)
        val native = fixture.home.resolve(".codex/app-server-control/app-server-control.sock")
        Files.createDirectories(native.parent)
        Files.writeString(native, "foreign-owner")
        val command = resolvedCommand(fixture)
        assertTrue(command.publicEndpoint is BrokerPublicEndpoint.Private)
        assertNotEquals(native, command.publicSocket)
        assertEquals("foreign-owner", Files.readString(native))
    }

    @Test
    fun `two physical installations sharing a host home contend for one public authority`(@TempDir temporary: Path) {
        val first = installedFixture(Files.createDirectory(temporary.resolve("first")))
        val second = installedFixture(Files.createDirectory(temporary.resolve("second")))
        val host = first.home.resolve(".codex")
        val a =
            resolvedCommand(
                first.copy(
                    environment =
                        first.environment +
                            mapOf("CODEX_HOME" to host.toString(), "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control")
                )
            )
        val b =
            resolvedCommand(
                second.copy(
                    environment =
                        second.environment +
                            mapOf("CODEX_HOME" to host.toString(), "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control")
                )
            )
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
        val canonical =
            resolvedCommand(
                fixture.copy(environment = fixture.environment + ("KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control"))
            )
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
                resolvedCommand(
                    fixture.copy(
                        environment =
                            fixture.environment +
                                mapOf(
                                    "CODEX_HOME" to home.toString(),
                                    "KAST_APP_SERVER_PUBLIC_ENDPOINT" to "codex-control",
                                )
                    )
                )
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
