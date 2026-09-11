package io.github.amichne.kast.runtime.hosted

import com.google.gson.Gson
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedEndpointReclamationTest {
    @Test
    fun `dead matching endpoint is reclaimed under exclusive ownership`() = fixture { directory, root, host ->
        stale(directory, root, deadPid())
        val observations = mutableListOf<Pair<HostedEndpointStage, HostedEndpointOutcome>>()
        val opened =
            OwnedHostedEndpoint.open(
                directory = directory,
                root = root,
                host = host,
                observer = HostedEndpointObserver { stage, outcome -> observations += stage to outcome },
            )
        assertEquals(
            listOf(
                HostedEndpointStage.RECLAMATION_ADMISSION to HostedEndpointOutcome.STARTED,
                HostedEndpointStage.RECLAMATION_ADMISSION to HostedEndpointOutcome.COMPLETED,
                HostedEndpointStage.RECLAMATION_RETIREMENT to HostedEndpointOutcome.STARTED,
                HostedEndpointStage.RECLAMATION_RETIREMENT to HostedEndpointOutcome.COMPLETED,
            ),
            observations,
        )
        assertTrue(opened is Refinement.Refined, opened.toString())
        (opened as Refinement.Refined).value.close()
    }

    @Test
    fun `live owner endpoint remains untouched even without its lock`() = fixture { directory, root, host ->
        stale(directory, root, ProcessHandle.current().pid())
        val before = Files.readString(directory.resolve("endpoint.json"))
        assertEquals(
            Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT),
            OwnedHostedEndpoint.open(directory, root, host),
        )
        assertEquals(before, Files.readString(directory.resolve("endpoint.json")))
        assertTrue(Files.exists(directory.resolve("host.sock")))
    }

    @Test
    fun `wrong root and malformed descriptor preserve both artifacts`() {
        for (mutation in
            listOf<(String) -> String>(
                { it.replace("/workspace", "/different") },
                { it + "{}" },
                { it.replaceFirst("{", "{\"type\":\"KAST_IDE_ENDPOINT\",") },
            )) fixture { directory, root, host ->
            stale(directory, root, deadPid())
            val descriptor = directory.resolve("endpoint.json")
            val before = mutation(Files.readString(descriptor))
            Files.writeString(descriptor, before)
            assertEquals(
                Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT),
                OwnedHostedEndpoint.open(directory, root, host),
            )
            assertEquals(before, Files.readString(descriptor))
            assertTrue(Files.exists(directory.resolve("host.sock")))
        }
    }

    @Test
    fun `replacement after admission preserves socket and replacement descriptor`() = fixture { directory, root, host ->
        stale(directory, root, deadPid())
        val observations = mutableListOf<Pair<HostedEndpointStage, HostedEndpointOutcome>>()
        val observer = HostedEndpointObserver { stage, outcome ->
            observations += stage to outcome
            if (stage == HostedEndpointStage.RECLAMATION_RETIREMENT && outcome == HostedEndpointOutcome.STARTED) {
                Files.delete(directory.resolve("endpoint.json"))
                Files.writeString(directory.resolve("endpoint.json"), "replacement")
            }
        }
        assertEquals(
            Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT),
            OwnedHostedEndpoint.open(directory, root, host, observer),
        )
        assertEquals("replacement", Files.readString(directory.resolve("endpoint.json")))
        assertTrue(Files.exists(directory.resolve("host.sock")))
        assertEquals(HostedEndpointStage.RECLAMATION_RETIREMENT to HostedEndpointOutcome.REJECTED, observations.last())
    }

    @Test
    fun `regular socket file and symlink cannot confer endpoint ownership`() {
        for (symlink in listOf(false, true)) fixture { directory, root, host ->
            stale(directory, root, deadPid())
            val socket = directory.resolve("host.sock")
            Files.delete(socket)
            if (symlink) Files.createSymbolicLink(socket, directory.resolve("endpoint.json"))
            else Files.writeString(socket, "not a socket")
            val before = Files.readString(directory.resolve("endpoint.json"))
            val observations = mutableListOf<Pair<HostedEndpointStage, HostedEndpointOutcome>>()
            val observer = HostedEndpointObserver { stage, outcome -> observations += stage to outcome }
            assertEquals(
                Refinement.Rejected(HostedEndpointFailure.OWNERSHIP_CONFLICT),
                OwnedHostedEndpoint.open(directory, root, host, observer),
            )
            assertEquals(before, Files.readString(directory.resolve("endpoint.json")))
            assertTrue(Files.exists(socket))
            assertEquals(
                HostedEndpointStage.RECLAMATION_ADMISSION to HostedEndpointOutcome.REJECTED,
                observations.last(),
            )
        }
    }

    private fun stale(directory: Path, root: CanonicalWorkspaceRoot, pid: Long) {
        val socket = directory.resolve("host.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(socket)) }
        Files.writeString(
            directory.resolve("endpoint.json"),
            Gson()
                .toJson(
                    mapOf(
                        "type" to "KAST_IDE_ENDPOINT",
                        "protocol" to HostedEndpointCapabilities.protocol,
                        "root" to root.value,
                        "socket" to socket.toString(),
                        "hostPid" to pid,
                        "host" to UUID.randomUUID().toString(),
                        "querySchema" to HostedReadCapabilities.querySchema,
                        "operations" to HostedEndpointCapabilities.operations,
                    )
                ),
        )
    }

    private fun deadPid(): Long {
        val process = ProcessBuilder("/usr/bin/true").start()
        assertEquals(0, process.waitFor())
        assertFalse(ProcessHandle.of(process.pid()).isPresent)
        return process.pid()
    }

    private fun fixture(block: (Path, CanonicalWorkspaceRoot, IdeReadHostLifetime) -> Unit) {
        val directory = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "khr-")
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        try {
            block(directory, root, IdeReadHostLifetime.fromBoundary(UUID.randomUUID()))
        } finally {
            for (name in listOf("endpoint.json", "host.sock", "owner.lock")) Files.deleteIfExists(
                directory.resolve(name)
            )
            Files.delete(directory)
        }
    }
}
