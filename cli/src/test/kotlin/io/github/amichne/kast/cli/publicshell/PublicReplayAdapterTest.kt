package io.github.amichne.kast.cli.publicshell

import com.lukelast.ktoon.Ktoon
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
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
