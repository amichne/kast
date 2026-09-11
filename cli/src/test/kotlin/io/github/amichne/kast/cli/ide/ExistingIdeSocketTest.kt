package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
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
            val home = Files.createTempDirectory(Path.of("/tmp").toRealPath(), "kc-")
            val root = CanonicalRoot(home)
            val digest =
                MessageDigest.getInstance("SHA-256").digest(home.toString().toByteArray()).take(16).joinToString("") {
                    "%02x".format(it)
                }
            val directory = home.resolve(".kast/ide-hosted/$digest")
            Files.createDirectories(directory)
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val socket = directory.resolve("host.sock")
            val operations =
                JsonArray(
                    listOf(
                            "DESCRIBE",
                            "CLASS_LOOKUP",
                            "DIRECT_SUPERTYPE",
                            "QUERY_RUN",
                            "SYMBOL_DISCOVER",
                            "SYMBOL_INSPECT",
                            "SOURCE_READ",
                            "RELATION_READ",
                            "TRAVERSAL_RUN",
                            "DIAGNOSTIC_CHECK",
                            "CHANGE_PLAN",
                            "CHANGE_APPROVAL_PREPARE",
                            "CHANGE_APPLY",
                            "CHANGE_RECOVER",
                        )
                        .map(::JsonPrimitive)
                )
            Files.writeString(
                directory.resolve("endpoint.json"),
                buildJsonObject {
                    put("type", "KAST_IDE_ENDPOINT")
                    put("protocol", 3)
                    put("host", "00000000-0000-0000-0000-000000000001")
                    put("querySchema", "kast.query.run.v2")
                    put("root", home.toString())
                    put("socket", socket.toString())
                    put("hostPid", 123)
                    put("operations", operations)
                }
                    .toString(),
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                    server.bind(UnixDomainSocketAddress.of(socket))
                    val response = buildJsonObject {
                        put("type", "KAST_IDE_HOST")
                        put("protocol", 3)
                        put("host", "00000000-0000-0000-0000-000000000001")
                        put("querySchema", "kast.query.run.v2")
                        put("root", home.toString())
                        put("hostPid", 123)
                        put("operations", operations)
                        put("indexAuthority", "existing_ide_kotlin_stub_index")
                    }
                        .toString()
                        .toByteArray()
                    val task = executor.submit {
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
                    val answer = ExistingIdeSocketClient(home).query(root, operation)
                    if (scenario == "valid") assertTrue(answer is ExistingIdeExchange.Received)
                    else if (scenario == "supertype") assertTrue(answer is ExistingIdeExchange.HostRejected)
                    else assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), answer)
                    task.get(3, TimeUnit.SECONDS)
                }
            } finally {
                executor.shutdownNow()
                Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }
}
