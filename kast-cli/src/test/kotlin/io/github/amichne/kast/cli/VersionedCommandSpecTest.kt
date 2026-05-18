package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionedCommandSpecTest {

    @Test
    fun `generated spec contains all v1 rpc methods`() {
        val parsed = parseSpec("test")
        val commands = parsed["commands"]!!.jsonObject

        val expectedCommands = listOf(
            "health",
            "runtime/status",
            "capabilities",
            "symbol/scaffold",
            "symbol/resolve",
            "symbol/references",
            "symbol/callers",
            "symbol/rename",
            "symbol/write-and-validate",
            "raw/resolve",
            "raw/references",
            "raw/call-hierarchy",
            "raw/type-hierarchy",
            "raw/semantic-insertion-point",
            "raw/diagnostics",
            "raw/rename",
            "raw/optimize-imports",
            "raw/apply-edits",
            "raw/workspace-refresh",
            "raw/file-outline",
            "raw/workspace-symbol",
            "raw/workspace-search",
            "raw/workspace-files",
            "raw/implementations",
            "raw/code-actions",
            "raw/completions",
            "database/metrics",
        )
        expectedCommands.forEach { command ->
            assertTrue(commands.containsKey(command), "Missing command: $command")
        }
    }

    @Test
    fun `spec categories group methods by family`() {
        val parsed = parseSpec("test")
        val categories = parsed["categories"]!!.jsonObject

        assertEquals(
            listOf("health", "runtime/status", "capabilities"),
            categories["system"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(categories["symbol"]!!.jsonArray.any { it.jsonPrimitive.content == "symbol/resolve" })
        assertTrue(categories["raw"]!!.jsonArray.any { it.jsonPrimitive.content == "raw/workspace-files" })
        assertEquals(
            listOf("database/metrics"),
            categories["database"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `spec version matches input version`() {
        val parsed = parseSpec("1.2.3")
        assertEquals("1.2.3", parsed["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `symbol resolve notes mention simple names`() {
        val parsed = parseSpec("test")
        val resolve = parsed["commands"]!!.jsonObject["symbol/resolve"]!!.jsonObject
        val notes = resolve["notes"].toString()
        assertTrue(notes.contains("simple name"), "symbol/resolve notes should mention simple names")
    }

    @Test
    fun `discriminated commands include variants`() {
        val parsed = parseSpec("test")
        val commands = parsed["commands"]!!.jsonObject

        val rename = commands["symbol/rename"]!!.jsonObject
        val renameVariants = rename["variants"]!!.jsonObject
        assertTrue(renameVariants.containsKey("RENAME_BY_SYMBOL_REQUEST"))
        assertTrue(renameVariants.containsKey("RENAME_BY_OFFSET_REQUEST"))

        val writeAndValidate = commands["symbol/write-and-validate"]!!.jsonObject
        val wavVariants = writeAndValidate["variants"]!!.jsonObject
        assertTrue(wavVariants.containsKey("CREATE_FILE_REQUEST"))
        assertTrue(wavVariants.containsKey("INSERT_AT_OFFSET_REQUEST"))
        assertTrue(wavVariants.containsKey("REPLACE_RANGE_REQUEST"))
    }

    @Test
    fun `each command has category request and response type`() {
        val parsed = parseSpec("test")
        val commands = parsed["commands"]!!.jsonObject

        commands.forEach { (name, element) ->
            val command = element.jsonObject
            assertEquals(name, command["method"]!!.jsonPrimitive.content)
            assertTrue(command.containsKey("category"), "$name missing category")
            assertTrue(command.containsKey("request"), "$name missing request")
            assertTrue(command.containsKey("responseType"), "$name missing responseType")

            val request = command["request"]!!.jsonObject
            assertTrue(request.containsKey("fields"), "$name request missing fields")
        }

        assertEquals(
            "METRICS_SUCCESS",
            commands["database/metrics"]!!.jsonObject["successType"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "backend",
            commands["raw/resolve"]!!.jsonObject["dataSource"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "sqlite",
            commands["database/metrics"]!!.jsonObject["dataSource"]!!.jsonPrimitive.content,
        )
    }

    private fun parseSpec(version: String) = defaultCliJson()
        .parseToJsonElement(VersionedCommandSpec.renderJson(version = version))
        .jsonObject
}
