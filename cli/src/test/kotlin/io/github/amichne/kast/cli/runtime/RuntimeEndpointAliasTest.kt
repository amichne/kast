package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

class RuntimeEndpointAliasTest {
    @Test
    fun `long runtime endpoint binds a physical installation inode through one admitted alias`() = fixture { root, endpoint ->
        assertFalse(Files.exists(endpoint.socketPath.parent), "discovery must be passive")
        assertFalse(Files.exists(endpoint.physicalSocketPath.parent), "discovery must not create state")
        val prepared = (endpoint.prepareTransport() as RuntimeEndpointResolution.Resolved).endpoint
        assertTrue(prepared.physicalSocketPath.startsWith(root))
        assertTrue(prepared.socketPath.toString().toByteArray().size <= 103)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(prepared.socketPath))
            assertTrue(Files.isSameFile(prepared.socketPath, prepared.physicalSocketPath))
            SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                client.connect(UnixDomainSocketAddress.of(prepared.socketPath))
                server.accept().use { accepted ->
                    client.write(ByteBuffer.wrap(byteArrayOf(41)))
                    val received = ByteBuffer.allocate(1)
                    assertEquals(1, accepted.read(received))
                    assertEquals(41, received.array()[0].toInt())
                }
            }
            assertEquals(RuntimeEndpointReachability.Reachable, JdkUnixDomainEndpointProbe.probe(prepared))
        }
        Files.delete(prepared.physicalSocketPath)
    }

    @Test
    fun `replaced runtime alias rejects connect and cleanup without touching foreign state`() = fixture { root, endpoint ->
        val prepared = (endpoint.prepareTransport() as RuntimeEndpointResolution.Resolved).endpoint
        val foreign = Files.createDirectory(root.resolve("foreign"))
        val sentinel = Files.writeString(foreign.resolve("sentinel"), "foreign owner")
        val alias = prepared.socketPath.parent
        Files.delete(alias)
        Files.createSymbolicLink(alias, foreign)
        try {
            assertInstanceOf(RuntimeEndpointResolution.Rejected::class.java, prepared.observeTransport())
            assertEquals(RuntimeEndpointReachability.Unreachable, JdkUnixDomainEndpointProbe.probe(prepared))
            assertInstanceOf(WireSessionOpening.Rejected::class.java, UnixDomainWireClient().open(prepared))
            assertEquals(RuntimeEndpointArtifactCleaning.Rejected, PosixRuntimeEndpointArtifacts.clean(
                InactiveRuntimeEndpoint.afterObservedAbsence(prepared, RuntimeProcessObservation.Absent, RuntimeEndpointReachability.Unreachable)))
            assertEquals("foreign owner", Files.readString(sentinel))
            assertEquals(foreign, Files.readSymbolicLink(alias))
        } finally {
            // This replacement was created by this test, never by product cleanup.
            if (Files.isSymbolicLink(alias) && Files.readSymbolicLink(alias) == foreign) Files.delete(alias)
        }
    }

    private fun fixture(test: (Path, RuntimeEndpoint) -> Unit) {
        val root = Files.createTempDirectory(Path.of("/private/tmp"), "kast-r-").toRealPath()
        val logical = root.resolve((1..8).joinToString("/") { "physical-release-component-$it" })
        val directory = (InstalledRuntimeDirectory.admit(logical.toString(), null) as InstalledRuntimeDirectoryAdmission.Admitted).directory
        val runtime = (SemanticRuntimeId.parse("sha256:${"a".repeat(64)}") as Refinement.Refined).value
        val endpoint = (Sha256RuntimeEndpointLocator(RuntimeSocketDirectory.from(directory), runtime)
            .locate(CanonicalRoot(root)) as RuntimeEndpointResolution.Resolved).endpoint
        try { test(root, endpoint) }
        finally {
            val receipt = InstalledEndpointAliases.observe(endpoint.socketPath)
            if (receipt is Validation.Validated) Files.delete(receipt.value.alias)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
