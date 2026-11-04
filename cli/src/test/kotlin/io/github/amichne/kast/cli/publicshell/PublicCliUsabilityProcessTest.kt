package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicCliUsabilityProcessTest {
    @TempDir lateinit var temporary: Path

    private val children
        get() = PublicCliProcessFixture(temporary)

    @Test
    fun `schema discovery pipes bounded JSON without preparing a workspace`() {
        val args =
            listOf(
                "describe",
                "query",
                "--section",
                "input",
                "--pointer",
                "",
                "--json",
                "--max-results",
                "1",
                "--max-returned-bytes",
                "1024",
            )
        var stderr = ""
        val first = children.child(args, diagnostics = { stderr = it })
        assertEquals(0, first.first)
        assertEquals("", stderr)
        assertTrue(first.second.toByteArray(Charsets.UTF_8).size <= 1024)
        val content = Json.parseToJsonElement(first.second).jsonObject.getValue("content").jsonObject
        assertEquals("page", content.getValue("type").jsonPrimitive.content)
        assertEquals("false", content.getValue("complete").jsonPrimitive.content)
        assertEquals(
            0,
            children.child(args + listOf("--cursor", content.getValue("next_cursor").jsonPrimitive.content)).first,
        )
        val missing =
            children.child(listOf("describe", "query", "--section", "input", "--pointer", "/absent", "--json"))
        assertEquals(2, missing.first)
        assertTrue(missing.second.contains("POINTER_NOT_FOUND"))
    }

    @Test
    fun `stdin request and file request produce the same JSON plan`() {
        val input =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("runByName")
                .value
                .toString()
        val file = temporary.resolve("request.json")
        Files.writeString(file, input)
        val fromFile = children.child(listOf("query", "plan", "--request", file.toString(), "--json"))
        val fromPipe = children.child(listOf("query", "plan", "--request", "-", "--json"), bytes = input.toByteArray())
        assertEquals(0, fromPipe.first)
        assertEquals(fromFile, fromPipe)
        assertEquals(
            "planned",
            Json.parseToJsonElement(fromPipe.second).jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `namespace help exposes read commands without host effects`() {
        for ((namespace, commands) in
            listOf("source" to listOf("source read"), "result" to listOf("result read", "result resume"))) {
            val result = children.child(listOf(namespace, "--help"))
            assertEquals(0, result.first)
            for (command in commands) {
                assertTrue(result.second.contains("kast $command"), result.second)
                assertTrue(
                    result.second.contains(
                        "kast describe ${command.replace(' ', '.')} --section input --pointer '' --json"
                    ),
                    result.second,
                )
            }
        }
        assertEquals(2, children.child(listOf("source", "unknown", "--help")).first)
        assertEquals(2, children.child(listOf("result")).first)
    }

    @Test
    fun `json flag overrides TOON for plans descriptors and input failures`() {
        for (args in
            listOf(
                listOf("query", "plan", "--name", "OrderService"),
                listOf("describe", "query"),
                listOf("query", "--name", "OrderService", "--fields", "name,name"),
            )) {
            val expected = children.child(args)
            for (setting in listOf("1", "", "invalid")) {
                assertEquals(
                    expected,
                    children.child(args + "--json", environment = mapOf("KAST_OUTPUT_TOON" to setting)),
                )
            }
        }
    }

    @Test
    fun `request path errors explain file and stdin modes without exposing the input`() {
        var diagnostic = ""
        val inline =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("runByName")
                .value
                .toString()
        val rejected = children.child(listOf("query", "plan", "--request", inline), diagnostics = { diagnostic = it })
        assertEquals(5, rejected.first)
        assertTrue(diagnostic.contains("--request FILE"), diagnostic)
        assertTrue(diagnostic.contains("--request -"), diagnostic)
        assertFalse(diagnostic.contains(inline), diagnostic)
        assertFalse(diagnostic.contains("Order"), diagnostic)
        val missing =
            children.child(
                listOf("query", "plan", "--request", temporary.resolve("absent.json").toString()),
                diagnostics = { diagnostic = it },
            )
        assertEquals(5, missing.first)
        assertTrue(diagnostic.contains("--request FILE"), diagnostic)
    }

    @Test
    fun `field rejection explains accepted values and incompatible output`() {
        var diagnostic = ""
        val invalid =
            children.child(
                listOf("query", "plan", "--name", "OrderService", "--fields", "name,name"),
                diagnostics = { diagnostic = it },
            )
        assertEquals(2, invalid.first)
        assertTrue(diagnostic.contains("name,location,signature,source"), diagnostic)
        val incompatible =
            children.child(
                listOf("query", "plan", "--name", "OrderService", "--fields", "name", "--output", "occurrences"),
                diagnostics = { diagnostic = it },
            )
        assertEquals(2, incompatible.first)
        assertTrue(diagnostic.contains("--output symbols"), diagnostic)
        assertEquals(
            0,
            children.child(listOf("query", "plan", "--name", "OrderService", "--fields", "name,signature")).first,
        )
    }
}
