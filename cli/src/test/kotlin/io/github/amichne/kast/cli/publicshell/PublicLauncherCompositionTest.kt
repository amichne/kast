package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.direct.DirectToolRegistration
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.direct.directToolName
import io.github.amichne.kast.cli.rpc.QueryDeliveryFixtures
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** This composition proves local cancellation and reaping, not compiler behavior or native semantic settlement. */
@Tag("public-launcher")
class PublicLauncherCompositionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `public native output preserves one JSON document and shell exit for typed delivery fixtures`() {
        val home = temporary.toRealPath()
        installFixtureLauncher(home)
        val cases =
            listOf(
                Triple("inline", 0, "complete"),
                Triple("prefix", 0, "complete"),
                Triple("proof", 10, "qualified"),
                Triple("proofTail", 10, "qualified"),
                Triple("header", 11, "complete"),
                Triple("defect", 1, "rejected"),
            )
        for ((name, status, type) in cases) {
            assertOutputCase(home, name, status, type)
        }
    }

    private fun assertOutputCase(home: Path, name: String, status: Int, type: String) {
        val stdout = home.resolve("$name.stdout")
        val stderr = home.resolve("$name.stderr")
        val parent =
            startPublic(
                home,
                home,
                listOf("query", "--name", "OrderService"),
                mapOf("KAST_PUBLIC_CLI_OUTPUT_CASE" to name),
            ) {
                redirectOutput(stdout.toFile())
                redirectError(stderr.toFile())
            }
        try {
            assertTrue(parent.waitFor(20, TimeUnit.SECONDS), name)
            assertEquals(status, parent.exitValue(), Files.readString(stderr))
            assertOutputFixture(name, type, Files.readString(stdout))
            val ready = Files.readString(stderr).lineSequence().first()
            assertTrue(ready.startsWith("output-child:"), ready)
            val pid = ready.removePrefix("output-child:").toLong()
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
        } finally {
            stopFixture(parent)
        }
    }

    private fun assertOutputFixture(name: String, type: String, text: String) {
        assertTrue(text.endsWith("\n"))
        assertEquals(1, text.count { it == '\n' })
        val output = Json.parseToJsonElement(text).jsonObject
        assertEquals(type, output.getValue("type").jsonPrimitive.content)
        if (name == "defect") {
            assertEquals("INVOCATION_FAILED", output.getValue("failure").jsonPrimitive.content)
        } else {
            val document = output.getValue("document").jsonObject
            assertEquals(type, document.getValue("status").jsonPrimitive.content)
            if (name == "proof" || name == "proofTail") {
                assertEquals(
                    "POLICY_REJECTED_EVIDENCE",
                    document.getValue("interpretation").jsonObject.getValue("type").jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun `public native launcher closes the admitted JVM invocation socket and reaps its child`() {
        val home = temporary.toRealPath()
        val workspace = Files.createDirectory(home.resolve("workspace"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "rootProject.name = \"public-cli-peer\"\n")
        installFixtureLauncher(home)
        for ((signal, exit) in listOf("INT" to 130, "TERM" to 143)) {
            assertOwnedPeerCancellation(home, workspace, signal, exit)
        }
    }

    @Test
    fun `public native launcher cancels an incomplete explicit stdin request`() {
        val home = temporary.toRealPath()
        installFixtureLauncher(home)
        for ((signal, exit) in listOf("INT" to 130, "TERM" to 143)) {
            val parent =
                startPublic(
                    home,
                    home,
                    listOf("query", "--request", "-"),
                    mapOf("KAST_PUBLIC_CLI_FIXTURE_MODE" to "stdin"),
                )
            try {
                val ready = parent.errorReader().readLine()
                assertTrue(ready.startsWith("input-ready:"), ready)
                val childPid = ready.removePrefix("input-ready:").toLong()
                sendSignal(parent, signal)
                assertTrue(parent.waitFor(15, TimeUnit.SECONDS))
                assertEquals(exit, parent.exitValue())
                assertEquals("", parent.inputReader().readText())
                assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
            } finally {
                stopFixture(parent)
            }
        }
    }

    private fun assertOwnedPeerCancellation(home: Path, workspace: Path, signal: String, exit: Int) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 15_000
            val parent =
                startPublic(
                    home,
                    workspace,
                    listOf("query", "--name", "OrderService"),
                    mapOf("KAST_PUBLIC_CLI_PEER_PORT" to listener.localPort.toString()),
                )
            try {
                listener.accept().use { peer ->
                    peer.soTimeout = 15_000
                    val input = peer.getInputStream().bufferedReader()
                    val frame = Json.decodeFromString<PublicLauncherPeerFrame>(input.readLine())
                    assertEquals(PublicToolCatalog.COMPLETE, frame.catalog)
                    val request = frame.arguments.jsonObject.getValue("request").jsonObject
                    assertEquals("RUN", request.getValue("type").jsonPrimitive.content)
                    assertEquals(
                        "OrderService",
                        request.getValue("source").jsonObject.getValue("declarationName").jsonPrimitive.content,
                    )
                    sendSignal(parent, signal)
                    assertEquals(-1, input.read(), "The exact admitted connection must reach EOF")
                    assertTrue(parent.waitFor(15, TimeUnit.SECONDS))
                    assertEquals(exit, parent.exitValue(), parent.errorReader().readText())
                    assertEquals("", parent.inputReader().readText())
                    assertFalse(ProcessHandle.of(frame.pid).map(ProcessHandle::isAlive).orElse(false))
                }
            } finally {
                stopFixture(parent)
            }
        }
    }

    private fun startPublic(
        home: Path,
        workspace: Path,
        arguments: List<String>,
        environment: Map<String, String>,
        configure: ProcessBuilder.() -> Unit = {},
    ): Process =
        ProcessBuilder(listOf(checkNotNull(System.getProperty("kast.publicLauncher"))) + arguments)
            .directory(workspace.toFile())
            .apply {
                environment().clear()
                environment()["HOME"] = home.toString()
                environment().putAll(environment)
            }
            .apply(configure)
            .start()

    private fun sendSignal(parent: Process, signal: String) {
        val sender = ProcessBuilder("/bin/kill", "-s", signal, parent.pid().toString()).start()
        assertTrue(sender.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, sender.exitValue())
    }

    private fun stopFixture(parent: Process) {
        if (parent.isAlive) {
            parent.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            parent.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun installFixtureLauncher(home: Path) {
        val installation = Files.createDirectories(home.resolve(".local/share/kast/installation"))
        val launcher = Files.createDirectories(installation.resolve("bin")).resolve("kast-query-complete")
        val java = Path.of(System.getProperty("java.home"), "bin/java").toString()
        // Only unavoidable host launch glue. The JVM receives argv unchanged through "$@".
        val content =
            "#!/bin/sh\nexec ${shellWord(java)} -cp ${shellWord(System.getProperty("java.class.path"))} " +
                "${PublicLauncherSocketFixture::class.java.name} \"\$@\"\n"
        Files.writeString(launcher, content)
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"))
        val rawLauncher = Files.copy(launcher, installation.resolve("bin/kast-query"))
        Files.setPosixFilePermissions(rawLauncher, PosixFilePermissions.fromString("rwxr-xr-x"))
        val digest =
            MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        Files.writeString(
            installation.resolve("installation.json"),
            Json.encodeToString(
                PublicLauncherFixtureManifest(
                    INSTALLATION_MANIFEST_SCHEMA_VERSION,
                    installation.toString(),
                    listOf(
                        PublicLauncherFixturePayload("bin/kast-query-complete", "sha256:$digest", 493),
                        PublicLauncherFixturePayload("bin/kast-query", "sha256:$digest", 493),
                    ),
                )
            ),
        )
    }

    private fun shellWord(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

@Serializable
private data class PublicLauncherFixtureManifest(
    val schemaVersion: Int,
    val installationRoot: String,
    val payloadFiles: List<PublicLauncherFixturePayload>,
)

@Serializable private data class PublicLauncherFixturePayload(val path: String, val sha256: String, val mode: Int)

@Serializable
internal data class PublicLauncherPeerFrame(
    val pid: Long,
    val catalog: PublicToolCatalog,
    /** The admitted public request is opaque to the local transport fixture. */
    val arguments: JsonElement,
)

/** The public worker owns this real interruptible socket. The peer never supplies semantic success. */
internal object PublicLauncherSocketFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val input = if (System.getenv("KAST_PUBLIC_CLI_FIXTURE_MODE") == "stdin") readyInput() else System.`in`
        val exit =
            runPublicCliInvocation(
                prepare = {
                    preparePublicCli(
                        arguments.toList(),
                        input,
                        Path.of("").toAbsolutePath(),
                        session = ::fixtureSession,
                        invoke = ::invokeFixture,
                    )
                },
                output = FileOutputStream(FileDescriptor.out),
            )
        exitProcess(exit.code)
    }

    /** Portable typed output exercises the real writer and classifier without a compiler claim. */
    private fun invokeFixture(session: KastDirectToolSession, admitted: AdmittedPublicTool): PublicPreparedOutput =
        when (val selected = System.getenv("KAST_PUBLIC_CLI_OUTPUT_CASE")) {
            null -> invokePublicSession(session, admitted)
            else -> {
                System.err.println("output-child:${ProcessHandle.current().pid()}")
                when (selected) {
                    "defect" -> error("Controlled local output-fixture defect")
                    "header" -> preparePublicReply(QueryDeliveryFixtures.presentationHeaders().first())
                    else ->
                        preparePublicReply(
                            Json.decodeFromString<Map<String, ToolRpcReply>>(QueryDeliveryFixtures.serialized())
                                .getValue(selected)
                        )
                }
            }
        }

    private fun fixtureSession(workspace: Path, catalog: PublicToolCatalog): KastDirectToolSession =
        KastDirectToolSession(
            registration =
                DirectToolRegistration(setOf(directToolName(PublicToolIdentity.QUERY_SYMBOLS.toolName))) {
                    error("A semantic call must not project discovery")
                },
            root = { FilesystemCanonicalRootDiscovery.discover(workspace) },
            start = { Refinement.Refined(Unit) },
            queryCatalog = catalog,
            invokePublic = ::awaitPeer,
        )

    private fun awaitPeer(admitted: AdmittedPublicTool): Nothing {
        val query = admitted.canonical as PublicToolCanonical.Query
        check(query.request is QueryRunRequest.Run)
        SocketChannel.open().use { connection ->
            connection.connect(
                InetSocketAddress(
                    InetAddress.getLoopbackAddress(),
                    System.getenv("KAST_PUBLIC_CLI_PEER_PORT").toInt(),
                )
            )
            val frame =
                PublicLauncherPeerFrame(
                    ProcessHandle.current().pid(),
                    admitted.catalog,
                    PublicToolContract.encode(admitted),
                )
            val bytes = ByteBuffer.wrap((Json.encodeToString(frame) + "\n").toByteArray())
            while (bytes.hasRemaining()) connection.write(bytes)
            connection.read(ByteBuffer.allocate(1))
            error("The peer cannot manufacture a semantic result")
        }
    }

    private fun readyInput(): InputStream =
        object : InputStream() {
            private val input = Channels.newInputStream(FileInputStream(FileDescriptor.`in`).channel)
            private var announced = false

            override fun read(): Int {
                announce()
                return input.read()
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                announce()
                return input.read(bytes, offset, length)
            }

            private fun announce() {
                if (!announced) {
                    announced = true
                    System.err.println("input-ready:${ProcessHandle.current().pid()}")
                }
            }
        }
}
