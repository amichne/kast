package io.github.amichne.kast.cli.broker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread

class PersistentBrokerServiceTest {
    @Test
    fun `missing public socket is an absent broker rather than an indeterminate probe`(
        @TempDir temporary: Path,
    ) {
        assertEquals(
            BrokerSocketReachability.UNREACHABLE,
            JdkBrokerSocketProbe.probe(
                temporary.toRealPath().resolve("app-server-control.sock"),
            ),
        )
    }

    @Test
    fun `raw Unix listener is not broker readiness`() {
        val temporary = Files.createTempDirectory(Path.of("/private/tmp"), "kb-probe.")
        val socket = temporary.resolve("raw.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val acceptor = thread(start = true) {
                server.accept().use { }
            }
            assertEquals(BrokerSocketReachability.REJECTED, JdkBrokerSocketProbe.probe(socket))
            acceptor.join(5_000)
        }
        temporary.toFile().deleteRecursively()
    }

    @Test
    fun `integration launch refines exact executable identity`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val command = resolvedCommand(fixture)
        assertEquals(fixture.kast.toRealPath(), command.kast)
        assertTrue(command.identity.value.startsWith("sha256:"))
        assertTrue(Files.isExecutable(command.javaExecutable))
    }

    @Test
    fun `explicit invalid codex executable fails closed instead of searching path`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)

        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(
                PersistentBrokerServiceFailure.CODEX_EXECUTABLE_UNAVAILABLE,
            ),
            BrokerServiceLaunchCommand.resolve(
                fixture.kast,
                fixture.userHome,
                fixture.environment + ("CODEX_EXECUTABLE" to "relative/codex"),
            ),
        )
    }

    @Test
    fun `explicit invalid codex home fails closed instead of using user default`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)

        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(
                PersistentBrokerServiceFailure.CODEX_HOME_REJECTED,
            ),
            BrokerServiceLaunchCommand.resolve(
                fixture.kast,
                fixture.userHome,
                fixture.environment + ("CODEX_HOME" to "relative-codex-home"),
            ),
        )
    }

    @Test
    fun `invalid user home has its own finite failure`(@TempDir temporary: Path) {
        val fixture = installedFixture(temporary)

        assertEquals(
            BrokerServiceLaunchCommandResolution.Rejected(
                PersistentBrokerServiceFailure.USER_HOME_REJECTED,
            ),
            BrokerServiceLaunchCommand.resolve(
                fixture.kast,
                temporary.resolve("missing-home"),
                fixture.environment,
            ),
        )
    }

    @Test
    fun `homebrew style executable symlink refines to physical codex target`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val links = Files.createDirectory(temporary.resolve("tool-links"))
        val tools = Path.of(fixture.environment.getValue("PATH"))
        Files.createSymbolicLink(links.resolve("codex"), tools.resolve("codex"))

        val resolution = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            mapOf("PATH" to links.toString()),
        ) as BrokerServiceLaunchCommandResolution.Resolved

        assertEquals(tools.resolve("codex").toRealPath(), resolution.command.codex.path)
        assertEquals(
            links.toRealPath().toString(),
            resolution.command.executableSearchPath.value.substringBefore(':'),
        )
    }

    @Test
    fun `Codex launcher directory participates in executable identity`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val tools = Path.of(fixture.environment.getValue("PATH"))
        val firstLinks = Files.createDirectory(temporary.resolve("first-links"))
        val secondLinks = Files.createDirectory(temporary.resolve("second-links"))
        Files.createSymbolicLink(firstLinks.resolve("codex"), tools.resolve("codex"))
        Files.createSymbolicLink(secondLinks.resolve("codex"), tools.resolve("codex"))

        val first = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            mapOf("PATH" to firstLinks.toString()),
        ) as BrokerServiceLaunchCommandResolution.Resolved
        val second = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            mapOf("PATH" to secondLinks.toString()),
        ) as BrokerServiceLaunchCommandResolution.Resolved

        assertNotEquals(first.command.identity, second.command.identity)
    }

    @Test
    fun `executable identity includes user home`(
        @TempDir temporary: Path,
    ) {
        val fixture = installedFixture(temporary)
        val otherHome = Files.createDirectory(temporary.resolve("other-home")).toRealPath()
        val sharedCodexHome = temporary.resolve("shared-codex-home")
        val environment = fixture.environment + ("CODEX_HOME" to sharedCodexHome.toString())
        val first = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            environment,
        ) as BrokerServiceLaunchCommandResolution.Resolved
        val second = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            otherHome,
            environment,
        ) as BrokerServiceLaunchCommandResolution.Resolved

        assertNotEquals(first.command.identity, second.command.identity)
    }

    private fun installedFixture(temporary: Path): InstalledFixture {
        val product = Files.createDirectories(temporary.resolve("product")).toRealPath()
        val bin = Files.createDirectories(product.resolve("bin"))
        val kast = executable(bin.resolve("kast"), "#!/bin/sh\nexit 0\n")
        val tools = Files.createDirectories(temporary.resolve("tools")).toRealPath()
        executable(tools.resolve("codex"), "#!/bin/sh\nexit 0\n")
        val userHome = Files.createDirectories(temporary.resolve("home")).toRealPath()
        return InstalledFixture(
            kast,
            userHome,
            mapOf("PATH" to tools.toString()),
        )
    }

    private fun resolvedCommand(fixture: InstalledFixture): BrokerServiceLaunchCommand = when (
        val resolution = BrokerServiceLaunchCommand.resolve(
            fixture.kast,
            fixture.userHome,
            fixture.environment,
        )
    ) {
        is BrokerServiceLaunchCommandResolution.Resolved -> resolution.command
        is BrokerServiceLaunchCommandResolution.Rejected -> error(resolution.failure)
    }

    private fun executable(path: Path, content: String): Path {
        Files.writeString(path, content)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }
}

private data class InstalledFixture(
    val kast: Path,
    val userHome: Path,
    val environment: Map<String, String>,
)
