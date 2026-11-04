package io.github.amichne.kast.cli.publicshell

import com.lukelast.ktoon.Ktoon
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicReplayAdapterTest {
    @Test
    fun `admission preserves scope with equivalent flag spellings`() {
        val first =
            replayAdmission(
                listOf("query", "--name", "OrderService", "--in", "services/orders", "--source-set", "main")
            )
        val second =
            replayAdmission(listOf("query", "--name=OrderService", "--source-set=main", "--in=services/orders"))
        assertEquals(ReplayAdmissionType.ADMITTED, first.type)
        assertEquals(first.arguments, second.arguments)
        val source =
            requireNotNull(first.arguments).jsonObject.getValue("request").jsonObject.getValue("source").jsonObject
        assertEquals(
            "services/orders",
            source.getValue("scope").jsonObject.getValue("relativeDirectoryPath").jsonPrimitive.content,
        )
        assertEquals(
            "main",
            source.getValue("scope").jsonObject.getValue("sourceSetNames").jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(0, first.nativeCalls)
    }

    @Test
    fun `invalid fields preserve the exact production diagnostic and exit`() {
        val receipt = replayAdmission(listOf("query", "--name", "OrderService", "--fields", "name, signature"))
        assertEquals(ReplayAdmissionType.ARGUMENT_REJECTED, receipt.type)
        assertEquals("INVALID_FIELDS", receipt.cause)
        assertEquals(2, receipt.exitStatus)
        assertEquals(publicCliArgumentDiagnostic(PublicCliArgumentFailure.INVALID_FIELDS) + "\n", receipt.stderr)
        assertEquals(
            "INVALID_COMMAND",
            Json.parseToJsonElement(receipt.stdout).jsonObject.getValue("failure").jsonPrimitive.content,
        )
        assertEquals(0, receipt.nativeCalls)
    }

    @Test
    fun `stdin decoding and public admission reject unknown values`() {
        val flags = replayAdmission(listOf("query", "--name", "OrderService"))
        val stdin = replayAdmission(listOf("query", "--request", "-"), flags.arguments.toString())
        assertEquals(ReplayAdmissionType.ADMITTED, stdin.type)
        assertEquals(flags.arguments, stdin.arguments)
        val invalid = replayAdmission(listOf("query", "--request", "-"), fixtureText("unknown-request.json"))
        assertEquals(ReplayAdmissionType.PUBLIC_REJECTED, invalid.type)
        assertEquals(2, invalid.exitStatus)
        assertEquals(0, invalid.nativeCalls)
    }

    @Test
    fun `request files cannot escape fixture ownership or use symbolic links`() {
        val root = Files.createTempDirectory("kast-replay-adapter-")
        try {
            val file = root.resolve("request.json")
            val expected = replayAdmission(listOf("query", "--name", "OrderService"))
            Files.writeString(file, expected.arguments.toString())
            val accepted = replayAdmission(listOf("query", "--request", "request.json"), directory = root)
            assertEquals(ReplayAdmissionType.ADMITTED, accepted.type)
            assertEquals(expected.arguments, accepted.arguments)
            Files.createSymbolicLink(root.resolve("linked.json"), file)
            for (path in listOf("linked.json", "../outside.json", "/etc/passwd")) {
                val rejected = replayAdmission(listOf("query", "--request", path), directory = root)
                assertEquals(ReplayAdmissionType.FIXTURE_FILE_REJECTED, rejected.type)
                assertEquals(0, rejected.nativeCalls)
                assertEquals("", rejected.stdout)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unrestricted integer keeps every digit through the independent decoder`() {
        val document = fixtureText("unrestricted-integer.json")
        assertEquals(document, replayRender(document, CliOutputFormat.JSON))
        val rendered = replayRender(document, CliOutputFormat.TOON)
        assertEquals("n: 1234567890123456789012345678901234567890", rendered)
        assertEquals(Json.parseToJsonElement(document), Ktoon.Default.decodeToonToJson(rendered))
    }

    @Test
    fun `nested empty arrays survive without output repair`() {
        val document = Json.encodeToString(ReplayNestedArrays(listOf(ReplayConnections(emptyList()))))
        val rendered = replayRender(document, CliOutputFormat.TOON)
        assertEquals("items[1]:\n  - connections: []", rendered)
        assertEquals(Json.parseToJsonElement(document), Ktoon.Default.decodeToonToJson(rendered))
    }

    @Test
    fun `literal array syntax remains a string instead of an empty array`() {
        val document =
            Json.encodeToString(
                ReplayLiteralArrays(listOf(ReplayLiteralConnection(Json.encodeToString(emptyList<String>()))))
            )
        val rendered = replayRender(document, CliOutputFormat.TOON)
        assertEquals("items[1]{connections}:\n  \"[]\"", rendered)
        assertEquals(Json.parseToJsonElement(document), Ktoon.Default.decodeToonToJson(rendered))
    }

    @Test
    fun `all declared replay documents preserve values through both independent codecs`() {
        val names =
            listOf(
                "C1",
                "C2",
                "C3",
                "C4",
                "C5",
                "C6",
                "H1",
                "H2",
                "H3",
                "H4",
                "C1-transition",
                "C2-transition",
                "C3-transition",
                "C4-transition",
                "C6-transition",
            )
        assertEquals(15, names.size)
        for (name in names) {
            val document =
                Files.readString(Path.of("../experiments/host-observation/interface-replay/fixtures/$name.json"))
            assertEquals(document, replayRender(document, CliOutputFormat.JSON), name)
            val rendered = replayRender(document, CliOutputFormat.TOON)
            assertEquals(Json.parseToJsonElement(document), Ktoon.Default.decodeToonToJson(rendered), name)
            assertEquals(dev.toonformat.jtoon.JToon.encodeJson(document), rendered, name)
        }
    }

    @Test
    fun `stdio accepts one render and one admission and preserves exact strings`() {
        val document = fixtureText("unrestricted-integer.json")
        val stdin = "λ\n\"quoted\" 1234567890123456789012345678901234567890"
        val replies =
            stdio(
                ReplayStdioRequest.Render(document, ReplayStdioFormat.TOON),
                ReplayStdioRequest.Admit(listOf("query", "--request", "-"), stdin, "/fixture"),
            )
        assertEquals(0, replies.first)
        assertEquals(
            listOf("ready", "rendered", "admission"),
            replies.second.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            "n: 1234567890123456789012345678901234567890",
            replies.second[1].getValue("text").jsonPrimitive.content,
        )
        val receipt = replies.second[2].getValue("receipt").jsonObject
        assertEquals(stdin, receipt.getValue("stdin").jsonPrimitive.content)
        assertEquals("INPUT_REJECTED", receipt.getValue("type").jsonPrimitive.content)
        assertEquals("0", receipt.getValue("nativeCalls").jsonPrimitive.content)
    }

    @Test
    fun `stdio retains production invalid command cause and supports admission before rendering`() {
        val document = Json.encodeToString(ReplayLiteralArrays(listOf(ReplayLiteralConnection("λ\n\"x\""))))
        val replies =
            stdio(
                ReplayStdioRequest.Admit(listOf("query", "--name", "A", "--fields", "name, signature"), "", "/fixture"),
                ReplayStdioRequest.Render(document, ReplayStdioFormat.JSON),
            )
        assertEquals(0, replies.first)
        assertEquals(
            listOf("ready", "admission", "rendered"),
            replies.second.map { it.getValue("type").jsonPrimitive.content },
        )
        val receipt = replies.second[1].getValue("receipt").jsonObject
        assertEquals("ARGUMENT_REJECTED", receipt.getValue("type").jsonPrimitive.content)
        assertEquals("INVALID_FIELDS", receipt.getValue("cause").jsonPrimitive.content)
        assertEquals("2", receipt.getValue("exitStatus").jsonPrimitive.content)
        assertEquals(document, replies.second[2].getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `stdio rejects repeated or third requests before production effects`() {
        val render = ReplayStdioRequest.Render(fixtureText("unrestricted-integer.json"), ReplayStdioFormat.TOON)
        val admit = ReplayStdioRequest.Admit(listOf("query", "--name", "A"), "", "/fixture")
        val third = stdio(render, admit, render)
        assertEquals(2, third.first)
        assertEquals(
            listOf("ready", "rendered", "admission", "rejected"),
            third.second.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals("REQUEST_LIMIT", third.second.last().getValue("cause").jsonPrimitive.content)
        val repeated = stdio(admit, admit.copy(directory = "\u0000"))
        assertEquals(2, repeated.first)
        assertEquals("REQUEST_LIMIT", repeated.second.last().getValue("cause").jsonPrimitive.content)
    }

    @Test
    fun `stdio rejects malformed unknown oversized invalid UTF8 and unterminated frames`() {
        val unknown = Json.encodeToString(ReplayUnknownStdioRequest("unknown")) + "\n"
        val cases =
            listOf(
                unknown.toByteArray() to "INVALID_REQUEST",
                "not-json\n".toByteArray() to "INVALID_REQUEST",
                (Json.encodeToString(ReplayUnknownRenderRequest("render", "not-json", "YAML")) + "\n").toByteArray() to
                    "INVALID_REQUEST",
                byteArrayOf(0xff.toByte(), 10) to "INVALID_UTF8",
                ByteArray(io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH + 1) { 'a'.code.toByte() } to
                    "FRAME_TOO_LARGE",
                unknown.removeSuffix("\n").toByteArray() to "INCOMPLETE_FRAME",
            )
        for ((input, cause) in cases) {
            val output = ByteArrayOutputStream()
            assertEquals(2, runReplayStdio(input.inputStream(), output), cause)
            val replies =
                output
                    .toString(Charsets.UTF_8)
                    .lineSequence()
                    .filter(String::isNotEmpty)
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .toList()
            assertEquals(2, replies.size, cause)
            assertEquals("rejected", replies.last().getValue("type").jsonPrimitive.content)
            assertEquals(cause, replies.last().getValue("cause").jsonPrimitive.content)
            assertTrue(output.size() < 256)
        }
        val empty = ByteArrayOutputStream()
        assertEquals(0, runReplayStdio(ByteArray(0).inputStream(), empty))
        assertEquals(1, empty.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).count())
    }

    @Test
    fun `stdio keeps explicit JSON precedence and bounds serialized replies`() {
        val request =
            ReplayStdioRequest.Admit(
                listOf("query", "--name", "A", "--fields", "name, signature", "--json"),
                "",
                "/fixture",
            )
        val input = Json.encodeToString(ReplayStdioRequest.serializer(), request) + "\n"
        val output = ByteArrayOutputStream()
        assertEquals(0, runReplayStdio(input.byteInputStream(), output, CliOutputFormat.TOON))
        val receipt =
            output
                .toString(Charsets.UTF_8)
                .lineSequence()
                .filter(String::isNotEmpty)
                .map { Json.parseToJsonElement(it).jsonObject }
                .toList()[1]
                .getValue("receipt")
                .jsonObject
        assertEquals(
            "INVALID_COMMAND",
            Json.parseToJsonElement(receipt.getValue("stdout").jsonPrimitive.content)
                .jsonObject
                .getValue("failure")
                .jsonPrimitive
                .content,
        )
        val oversized =
            request.copy(
                argv = listOf("query", "--name", "A"),
                stdin = " ".repeat(io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH - 256),
            )
        assertTrue(
            Json.encodeToString(ReplayStdioRequest.serializer(), oversized).toByteArray().size <=
                io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
        )
        val bounded = stdio(oversized)
        assertEquals(2, bounded.first)
        assertEquals("RESPONSE_TOO_LARGE", bounded.second.last().getValue("cause").jsonPrimitive.content)
    }

    @Test
    fun `stdio stops after an encoding failure before later admission`() {
        val replies =
            stdio(
                ReplayStdioRequest.Render("{", ReplayStdioFormat.JSON),
                ReplayStdioRequest.Admit(emptyList(), "", "\u0000"),
            )
        assertEquals(2, replies.first)
        assertEquals(listOf("ready", "rejected"), replies.second.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("ENCODING_REJECTED", replies.second.last().getValue("cause").jsonPrimitive.content)
    }

    @Test
    fun `production rendering keeps exact large integers escaping omission and token bytes`() {
        val document = Json {
            encodeDefaults = true
        }
            .encodeToString(
                ReplayEncodingEvidence(
                    Long.MAX_VALUE,
                    "exact:v5:AAAAAAAAAAAAAAAAAAAAAA",
                    null,
                    emptyList(),
                    "λ\\\"\n,:|",
                    listOf(ReplayEncodingRow("A", "fun A(x: Int)")),
                )
            )
        val json = replayRender(document, CliOutputFormat.JSON)
        val toon = replayRender(document, CliOutputFormat.TOON)
        assertEquals(document, json)
        assertEquals(
            Json.parseToJsonElement(document),
            Ktoon.Default.decodeToonToJson(toon),
        )
        assertTrue(toon.contains("9223372036854775807"))
        assertTrue(toon.contains("exact:v5:AAAAAAAAAAAAAAAAAAAAAA"))
        assertTrue(!toon.contains("omittedValue"))
    }
}

private fun fixtureText(name: String): String =
    requireNotNull(PublicReplayAdapterTest::class.java.getResourceAsStream("/public-replay/$name"))
        .bufferedReader()
        .use { it.readText() }

@Serializable
private data class ReplayEncodingEvidence(
    val generation: Long,
    val ref: String,
    val nullValue: String?,
    val empty: List<String>,
    val source: String,
    val rows: List<ReplayEncodingRow>,
)

@Serializable private data class ReplayEncodingRow(val name: String, val signature: String)

@Serializable private data class ReplayNestedArrays(val items: List<ReplayConnections>)

@Serializable private data class ReplayConnections(val connections: List<String>)

@Serializable private data class ReplayLiteralArrays(val items: List<ReplayLiteralConnection>)

@Serializable private data class ReplayLiteralConnection(val connections: String)

private fun stdio(vararg requests: ReplayStdioRequest): Pair<Int, List<kotlinx.serialization.json.JsonObject>> {
    val input =
        requests.joinToString("", transform = { Json.encodeToString(ReplayStdioRequest.serializer(), it) + "\n" })
    val output = ByteArrayOutputStream()
    val status = runReplayStdio(input.byteInputStream(), output)
    return status to
        output
            .toString(Charsets.UTF_8)
            .lineSequence()
            .filter(String::isNotEmpty)
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()
}

@Serializable private data class ReplayUnknownStdioRequest(val type: String)

@Serializable private data class ReplayUnknownRenderRequest(val type: String, val document: String, val format: String)
