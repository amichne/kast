package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeQualifiedClassName
import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.appserver.ide.ManagedDeploymentFixture
import io.github.amichne.kast.appserver.ide.cleanupHostedEndpointFixture
import io.github.amichne.kast.distribution.managed.canonicalRootFixture
import io.github.amichne.kast.protocol.contract.IdeHostApplicationAdvertisement
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("native")
class ExistingIdeSocketTest {
    @Test
    fun `native framed client accepts the exact host and rejects oversized and invalid UTF8 responses`() {
        for (scenario in listOf("valid", "supertype", "oversized", "utf8", "truncated")) {
            val temporary = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kc-")
            val deployment = ManagedDeploymentFixture(temporary)
            val home = deployment.home
            val root = canonicalRootFixture(home)
            val digest =
                MessageDigest.getInstance("SHA-256").digest(home.toString().toByteArray()).take(16).joinToString("") {
                    "%02x".format(it)
                }
            val directory =
                io.github.amichne.kast.distribution.contract.HostedEndpointPaths.family(
                        home,
                        io.github.amichne.kast.distribution.contract.HostedEndpointPaths.Family.SEMANTIC,
                    )
                    .resolve(digest)
            Files.createDirectories(directory)
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val socket = directory.resolve("host.sock")
            val host = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val identity = deployment.identity(host.toString())
            Files.writeString(
                directory.resolve("endpoint.json"),
                HostedDescriptorFixture.endpoint(
                    home.toString(),
                    socket.toString(),
                    host,
                    application = IdeHostApplicationAdvertisement(identity, HostedDescriptorFixture.compatibility),
                ),
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                    server.bind(UnixDomainSocketAddress.of(socket))
                    val response = HostedDescriptorFixture.status(home.toString(), host, identity.pid).toByteArray()
                    val task = executor.submit {
                        if (scenario == "supertype") {
                            server.accept().use { client ->
                                val input = java.io.DataInputStream(Channels.newInputStream(client))
                                val request =
                                    Json.parseToJsonElement(String(input.readNBytes(input.readInt()))).jsonObject
                                assertEquals("DESCRIBE", request["type"]?.jsonPrimitive?.content)
                                val output = java.io.DataOutputStream(Channels.newOutputStream(client))
                                output.writeInt(response.size)
                                output.write(response)
                                output.flush()
                            }
                        }
                        server.accept().use { client ->
                            val input = java.io.DataInputStream(Channels.newInputStream(client))
                            val request = Json.parseToJsonElement(String(input.readNBytes(input.readInt()))).jsonObject
                            if (scenario == "supertype") {
                                assertEquals(setOf("root", "type", "qualifiedName"), request.keys)
                                assertEquals("DIRECT_SUPERTYPE", request["type"]?.jsonPrimitive?.content)
                                assertEquals("example.Child", request["qualifiedName"]?.jsonPrimitive?.content)
                            } else assertEquals("DESCRIBE", request["type"]?.jsonPrimitive?.content)
                            val output = java.io.DataOutputStream(Channels.newOutputStream(client))
                            when (scenario) {
                                "valid" -> {
                                    output.writeInt(response.size)
                                    output.write(response)
                                }
                                "supertype" -> {
                                    val rejected = """{"type":"HOST_REJECTED","failure":"WRONG_ROOT"}""".toByteArray()
                                    output.writeInt(rejected.size)
                                    output.write(rejected)
                                }
                                "oversized" -> output.writeInt(65_537)
                                "utf8" -> {
                                    output.writeInt(2)
                                    output.write(byteArrayOf(0xC3.toByte(), 0x28))
                                }
                                "truncated" -> {
                                    output.writeInt(10)
                                    output.writeByte(0)
                                }
                            }
                            output.flush()
                        }
                    }
                    val operation =
                        if (scenario == "supertype")
                            ExistingIdeOperation.Supertype(
                                (ExistingIdeQualifiedClassName.parse("example.Child")
                                        as io.github.amichne.kast.kernel.Refinement.Refined)
                                    .value
                            )
                        else ExistingIdeOperation.Status
                    val answer = ExistingIdeSocketClient(home, deployment.configuration).query(root, operation)
                    if (scenario == "valid") assertTrue(answer is ExistingIdeExchange.Received, answer.toString())
                    else if (scenario == "supertype")
                        assertTrue(answer is ExistingIdeExchange.HostRejected, answer.toString())
                    else assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), answer)
                    task.get(3, TimeUnit.SECONDS)
                }
            } finally {
                executor.shutdownNow()
                cleanupHostedEndpointFixture(home)
                Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }
}
