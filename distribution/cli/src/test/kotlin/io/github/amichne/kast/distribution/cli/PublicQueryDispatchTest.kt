package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.InstalledToolInvocation
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sun.misc.Signal

class PublicQueryDispatchTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `public namespaces reach the selected JVM without native semantic parsing`() {
        for (arguments in
            listOf(
                listOf("query", "--name", "OrderService"),
                listOf("query", "--future-option"),
                listOf("result", "read", "--ref", "issued token"),
                listOf("source", "read", "--ref", "issued token"),
                listOf("describe", "query", "--section", "input"),
            )) {
            assertTrue(parseManagementCommand(arguments) is ManagementParsing.Selected, arguments.toString())
            assertEquals(
                ManagementParsing.Selected(ManagementCommand.PublicQuery(arguments)),
                parseManagementCommand(arguments),
            )
        }
    }

    @Test
    fun `real child preserves argv cwd environment streams and nonzero exit`() {
        val directory = Files.createDirectory(temporary.resolve("workspace with spaces"))
        val arguments = listOf("two words", "\$HOME; no shell", "", "--name=OrderService")
        val child = startOwner(directory, "echo", *arguments.toTypedArray())
        try {
            val stdout = child.inputReader().readText()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(10, child.exitValue())
            assertEquals("fixture diagnostic\n", child.errorReader().readText())
            assertEquals(
                PublicQueryProbeDocument(arguments, directory.toRealPath().toString(), "selected"),
                managementJson.decodeFromString<PublicQueryProbeDocument>(stdout),
            )
            assertTrue(stdout.endsWith("\n"))
            assertEquals(1, stdout.count { it == '\n' })
        } finally {
            stopFixture(child)
        }
    }

    @Test
    fun `signals close the owned peer and parent reaps only its child`() {
        for (signal in PublicQuerySignal.entries) assertOwnedSignal(signal)
    }

    private fun assertOwnedSignal(signal: PublicQuerySignal) {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 10_000
            val parent = startOwner(temporary, "peer", listener.localPort.toString())
            try {
                listener.accept().use { peer ->
                    peer.soTimeout = 10_000
                    val input = peer.getInputStream().bufferedReader()
                    val childPid = input.readLine().toLong()
                    val sender = ProcessBuilder("/bin/kill", "-s", signal.signalName, parent.pid().toString()).start()
                    assertTrue(sender.waitFor(5, TimeUnit.SECONDS))
                    assertEquals(0, sender.exitValue())
                    assertEquals(-1, input.read())
                    assertTrue(parent.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(signal.exit, parent.exitValue(), parent.errorReader().readText())
                    assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                    assertTrue(ProcessHandle.current().isAlive)
                }
            } finally {
                stopFixture(parent)
            }
        }
    }

    @Test
    fun `closed pipe preserves child output failure without a wrapper success`() {
        val parent = startOwner(temporary, "pipe")
        try {
            assertEquals("ready", parent.errorReader().readLine())
            parent.inputStream.close()
            parent.outputStream.use { it.write(1) }
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS))
            assertEquals(5, parent.exitValue())
        } finally {
            stopFixture(parent)
        }
    }

    @Test
    fun `missing selected installation returns one typed failure before a child starts`() {
        val parent =
            ProcessBuilder(javaCommand("io.github.amichne.kast.distribution.cli.KastManagementMainKt") + "query")
                .directory(temporary.toFile())
                .apply {
                    environment().clear()
                    environment()["HOME"] = temporary.toString()
                }
                .start()
        try {
            val stdout = parent.inputReader().readText()
            val document = managementJson.decodeFromString<JsonObject>(stdout)
            assertEquals(setOf("type", "failure"), document.keys)
            assertEquals("rejected", document.getValue("type").jsonPrimitive.content)
            assertEquals("CATALOG_UNAVAILABLE", document.getValue("failure").jsonPrimitive.content)
            assertTrue(stdout.endsWith("\n"))
            assertEquals(1, stdout.count { it == '\n' })
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS))
            assertEquals(4, parent.exitValue())
            assertEquals("", parent.errorReader().readText())
        } finally {
            stopFixture(parent)
        }
    }

    @Test
    fun `lifecycle ownership admits public JVM only for its recorded installed incarnation`() {
        val installation = temporary.resolve("installation")
        val snapshot =
            LifecycleProcessSnapshot(
                42,
                100,
                "/fixture/bin/java",
                listOf(
                    "-cp",
                    installation.resolve("lib/kast.jar").toString(),
                    "io.github.amichne.kast.cli.publicshell.KastPublicCliMain",
                    "query",
                ),
            )
        val record = InstalledToolInvocation(1, 42, 100)
        assertTrue(
            OwnedLifecycleProcess.admit(record, LifecycleProcessObservation.Present(snapshot), installation)
                is ShutdownProcessAdmission.Owned
        )
        assertEquals(
            ShutdownProcessAdmission.Finished,
            OwnedLifecycleProcess.admit(
                record,
                LifecycleProcessObservation.Present(snapshot.copy(startEpochMillis = 101)),
                installation,
            ),
        )
        assertEquals(
            ShutdownProcessAdmission.Rejected,
            OwnedLifecycleProcess.admit(
                record,
                LifecycleProcessObservation.Present(snapshot),
                temporary.resolve("foreign"),
            ),
        )
        assertTrue(ResetScopedProcess.select(snapshot, temporary) is ResetProcessSelection.Selected)
        assertEquals(ResetProcessSelection.Unrelated, ResetScopedProcess.select(snapshot, temporary.resolve("foreign")))
    }

    @Test
    fun `selected launcher needs both manifest-owned executable payloads`() {
        val installation = temporary.toRealPath()
        val paths = listOf("bin/kast-query", "bin/kast-query-complete")
        Files.createDirectory(installation.resolve("bin"))
        val payloads = paths.map { relative ->
            val path = Files.writeString(installation.resolve(relative), "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
            BundledPayload(relative, "sha256:${sha256(path)}", 493)
        }
        val manifest = BundledManifest(INSTALLATION_MANIFEST_SCHEMA_VERSION, installation.toString(), payloads)
        assertTrue(
            PublicQueryLauncher.Admitted.fromInstallation(installation, manifest) is PublicQueryLauncher.Admitted
        )
        Files.delete(installation.resolve("bin/kast-query"))
        assertEquals(
            PublicQueryLauncher.Unavailable,
            PublicQueryLauncher.Admitted.fromInstallation(installation, manifest),
        )
    }

    private fun startOwner(directory: Path, vararg arguments: String): Process =
        ProcessBuilder(javaCommand(PublicQueryOwnerFixture::class.java.name) + arguments)
            .directory(directory.toFile())
            .apply {
                environment().clear()
                environment()["KAST_PUBLIC_QUERY_FIXTURE"] = "selected"
            }
            .start()

    private fun stopFixture(child: Process) {
        if (child.isAlive) {
            child.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            child.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

private fun javaCommand(main: String): List<String> =
    listOf(
        Path.of(System.getProperty("java.home"), "bin/java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        main,
    )

@Serializable
internal data class PublicQueryProbeDocument(val arguments: List<String>, val directory: String, val marker: String)

internal object PublicQueryOwnerFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        exitProcess(
            runOwnedPublicQueryChild(
                javaCommand(PublicQueryProbe::class.java.name) + arguments,
                Path.of("").toAbsolutePath(),
                System.getenv(),
            )
        )
    }
}

/** Real process/pipe/socket fixture. It supplies no semantic or compiler result. */
internal object PublicQueryProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.first()) {
            "echo" -> {
                println(
                    managementJson.encodeToString(
                        PublicQueryProbeDocument(
                            arguments.drop(1),
                            Path.of("").toRealPath().toString(),
                            System.getenv("KAST_PUBLIC_QUERY_FIXTURE"),
                        )
                    )
                )
                System.err.println("fixture diagnostic")
                exitProcess(10)
            }
            "peer" -> {
                Socket(java.net.InetAddress.getLoopbackAddress(), arguments[1].toInt()).use { peer ->
                    for (signal in PublicQuerySignal.entries) {
                        Signal.handle(Signal(signal.signalName)) {
                            peer.close()
                            exitProcess(signal.exit)
                        }
                    }
                    peer.getOutputStream().write("${ProcessHandle.current().pid()}\n".toByteArray())
                    peer.getOutputStream().flush()
                    CountDownLatch(1).await()
                }
            }
            "pipe" -> {
                System.err.println("ready")
                System.`in`.read()
                System.out.println("x".repeat(65_536))
                System.out.flush()
                exitProcess(if (System.out.checkError()) 5 else 0)
            }
            else -> error("Unknown fixture mode")
        }
    }
}
