package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_FENCE
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

/** Actual native public route and Unix peers; these peers do not establish compiler semantics. */
@Tag("public-launcher")
class PublicLauncherCompositionTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) lateinit var temporary: Path

    @Test
    fun `native metadata help plans and invalid input avoid host effects and unrequested stdin`() {
        NativePublicFixture(temporary).use { fixture ->
            // A stopped installation may still serve local metadata and reject local input.
            Files.writeString(fixture.installation.parent.resolve(INSTALLATION_SHUTDOWN_FENCE), "fenced\n")
            val commands =
                listOf(
                    listOf("query", "--help") to 0,
                    listOf("describe") to 0,
                    listOf("describe", "query", "--section", "input") to 0,
                    listOf("describe", "query", "--section", "examples") to 0,
                    listOf("query", "plan", "--name", "OrderService") to 0,
                    listOf("query", "--name", "OrderService", "--max-returned-bytes", "4095") to 2,
                    listOf("query", "--name", "OrderService", "--unknown") to 2,
                )
            commands.forEachIndexed { index, (arguments, exit) ->
                val label = "local-$index"
                val parent = fixture.start(label, arguments)
                // Intentionally leave stdin open and empty: local commands must not request it.
                fixture.assertExit(parent, exit, label)
                val text = Files.readString(temporary.resolve("$label.stdout"))
                if (arguments.contains("--help")) {
                    assertTrue(text.contains("query"), text)
                } else {
                    val document = oneDocument(text)
                    when {
                        arguments.first() == "describe" ->
                            assertEquals("kast.cli.describe.v1", document.getValue("schema").jsonPrimitive.content)
                        arguments.contains("plan") ->
                            assertEquals("planned", document.getValue("type").jsonPrimitive.content)
                        else -> assertEquals("rejected", document.getValue("type").jsonPrimitive.content)
                    }
                }
            }
            assertFalse(Files.exists(fixture.installation.resolve("state/run/one-shot")))
            assertFalse(Files.exists(fixture.home.resolve(".kast")))
        }
    }

    @Test
    fun `native file and explicit stdin plans preserve the same admitted document`() {
        NativePublicFixture(temporary).use { fixture ->
            val request =
                PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                    .examples
                    .getValue("runByName")
                    .value
                    .toString()
            val file = fixture.home.resolve("request with spaces.json")
            Files.writeString(file, request)
            val fromFile = fixture.start("file", listOf("query", "plan", "--request", file.toString()))
            fixture.assertExit(fromFile, 0, "file")
            val fromStdin = fixture.start("stdin", listOf("query", "plan", "--request", "-"))
            fromStdin.outputStream.use { it.write(request.toByteArray()) }
            fixture.assertExit(fromStdin, 0, "stdin")
            val output = Files.readString(temporary.resolve("file.stdout"))
            oneDocument(output)
            assertEquals(output, Files.readString(temporary.resolve("stdin.stdout")))
            val invalid = fixture.start("invalid", listOf("query", "--request", "-"))
            invalid.outputStream.use { it.write("{} {}".toByteArray()) }
            fixture.assertExit(invalid, 2, "invalid")
            assertEquals(
                "rejected",
                oneDocument(Files.readString(temporary.resolve("invalid.stdout")))
                    .getValue("type")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun `public native launcher cancels proven incomplete stdin and reaps its exact child`() {
        NativePublicFixture(temporary).use { fixture ->
            for ((signal, exit) in listOf("INT" to 130, "TERM" to 143)) {
                val label = "stdin-$signal"
                val parent = fixture.start(label, listOf("query", "--request", "-"))
                parent.outputStream.write("{\"request\":".toByteArray())
                parent.outputStream.flush()
                val child = fixture.awaitQueryChild(parent)
                awaitNativePipeStack(child, temporary, "read")
                signalNativeProcess(parent, signal)
                fixture.assertExit(parent, exit, label)
                fixture.assertReaped(child)
                assertEquals("", Files.readString(temporary.resolve("$label.stdout")))
            }
        }
    }

    @Test
    fun `signals close the production admitted Unix query socket without replay and reap the native child`() {
        for ((signal, exit) in listOf("INT" to 130, "TERM" to 143)) {
            val evidence = Files.createDirectory(temporary.resolve(signal))
            NativePublicFixture(evidence).use { fixture ->
                NativePublicPeer(fixture, NativePeerReply.HOLD_QUERY).use { peer ->
                    val parent = fixture.start("socket", semanticArguments(fixture))
                    val child = fixture.awaitQueryChild(parent)
                    peer.awaitQuery()
                    signalNativeProcess(parent, signal)
                    fixture.assertExit(parent, exit, "socket")
                    fixture.assertReaped(child)
                    peer.assertConsumed()
                    assertEquals("", Files.readString(evidence.resolve("socket.stdout")))
                    assertInvocationRecordRemoved(fixture)
                }
            }
        }
    }

    @Test
    fun `native endpoint response admission rejects changed host duplicate keys and trailing data`() {
        for (reply in
            listOf(
                NativePeerReply.WRONG_HOST,
                NativePeerReply.WRONG_ROOT,
                NativePeerReply.INCOMPATIBLE,
                NativePeerReply.REPLACED_SOCKET,
                NativePeerReply.MALFORMED,
                NativePeerReply.DUPLICATE_KEY,
                NativePeerReply.TRAILING_DOCUMENT,
                NativePeerReply.HOST_REJECTION,
            )) {
            val evidence = Files.createDirectory(temporary.resolve(reply.name))
            NativePublicFixture(evidence).use { fixture ->
                NativePublicPeer(fixture, reply).use { peer ->
                    val parent = fixture.start("reply", semanticArguments(fixture))
                    fixture.assertExit(parent, if (reply == NativePeerReply.HOST_REJECTION) 2 else 6, "reply")
                    peer.assertConsumed()
                    assertEquals(
                        "rejected_document",
                        oneDocument(Files.readString(evidence.resolve("reply.stdout")))
                            .getValue("type")
                            .jsonPrimitive
                            .content,
                    )
                    assertInvocationRecordRemoved(fixture)
                }
            }
        }
    }

    private fun semanticArguments(fixture: NativePublicFixture): List<String> =
        listOf("query", "--workspace", fixture.workspace.toString(), "--name", "OrderService")

    private fun assertInvocationRecordRemoved(fixture: NativePublicFixture) {
        val records = fixture.installation.resolve("state/run/one-shot")
        assertTrue(Files.isDirectory(records), "Native invocation did not establish observation ownership")
        Files.list(records).use { assertEquals(0L, it.count()) }
    }

    private fun oneDocument(text: String): JsonObject {
        assertTrue(text.endsWith("\n"))
        assertEquals(1, text.count { it == '\n' })
        return Json.parseToJsonElement(text).jsonObject
    }
}
