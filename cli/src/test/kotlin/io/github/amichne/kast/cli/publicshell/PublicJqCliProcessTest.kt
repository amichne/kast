package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicJqCliProcessTest {
    @TempDir lateinit var temporary: Path
    private val children
        get() = PublicCliProcessFixture(temporary)

    @Test
    fun `plan filters run before JSON and TOON presentation in an uninstalled directory`() {
        val args =
            listOf("query", "plan", "--name", "Order", "--fields", "name,location") + jq(".request.output.fields")
        assertEquals(0 to Json.encodeToString(listOf("name", "location")) + "\n", children.child(args + "--json"))
        assertEquals(0 to "[2]: name,location\n", children.child(args, environment = mapOf("KAST_OUTPUT_TOON" to "1")))
        assertEquals(0 to "null\n", children.child(args.dropLast(4) + jq("null") + "--json"))
    }

    @Test
    fun `request stdin and file modes retain their input while jq receives the produced document`() {
        val input =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("runByName")
                .value
                .toString()
        val file = temporary.resolve("request.json")
        Files.writeString(file, input)
        val options = jq(".type") + "--json"
        val stdin = children.child(listOf("query", "plan", "--request", "-") + options, bytes = input.toByteArray())
        val fromFile = children.child(listOf("query", "plan", "--request", file.toString()) + options)
        assertEquals(0 to "\"planned\"\n", stdin)
        assertEquals(stdin, fromFile)
    }

    @Test
    fun `missing engine compile runtime and cardinality failures have visible finite causes`() {
        val base = listOf("query", "plan", "--name", "Order", "--json")
        val missing =
            children.child(base + listOf("--jq", ".", "--jq-executable", temporary.resolve("missing-jq").toString()))
        assertEquals(4, missing.first)
        assertTrue(missing.second.contains("ENGINE_UNAVAILABLE"), missing.second)
        assertTrue(missing.second.contains("--jq-executable"), missing.second)
        for ((program, code) in
            listOf(
                ".[" to "PROGRAM_INVALID",
                "error(\"private payload\")" to "EVALUATION_FAILED",
                "empty" to "OUTPUT_CARDINALITY",
                "1,2" to "OUTPUT_CARDINALITY",
            )) {
            var diagnostic = ""
            val result = children.child(base + jq(program), diagnostics = { diagnostic = it })
            assertEquals(2, result.first, result.second)
            assertTrue(result.second.contains(code), result.second)
            assertTrue(diagnostic.contains(code), diagnostic)
            assertFalse((result.second + diagnostic).contains("private payload"))
        }
    }

    private fun jq(program: String): List<String> = listOf("--jq", program, "--jq-executable", executable().toString())

    private fun executable(): Path =
        System.getenv("PATH")
            .split(java.io.File.pathSeparatorChar)
            .map { Path.of(it).resolve("jq") }
            .first { Files.isRegularFile(it) && Files.isExecutable(it) }
            .toRealPath()
}
