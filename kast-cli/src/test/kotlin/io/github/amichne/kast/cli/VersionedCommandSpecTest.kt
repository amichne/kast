package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.tty.defaultCliJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionedCommandSpecTest {

    @Test
    fun `generated spec contains all wrapper commands`() {
        val json = VersionedCommandSpec.renderJson(version = "test")
        val parsed = defaultCliJson().parseToJsonElement(json).jsonObject
        val commands = parsed["commands"]!!.jsonObject

        val expectedCommands = listOf(
            "workspace-files",
            "scaffold",
            "resolve",
            "references",
            "callers",
            "diagnostics",
            "rename",
            "write-and-validate",
            "metrics",
        )
        expectedCommands.forEach { command ->
            assertTrue(commands.containsKey(command), "Missing command: $command")
        }
    }

    @Test
    fun `spec version matches input version`() {
        val json = VersionedCommandSpec.renderJson(version = "1.2.3")
        val parsed = defaultCliJson().parseToJsonElement(json).jsonObject
        assertEquals("1.2.3", parsed["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resolve command notes mention simple names`() {
        val json = VersionedCommandSpec.renderJson(version = "test")
        val parsed = defaultCliJson().parseToJsonElement(json).jsonObject
        val resolve = parsed["commands"]!!.jsonObject["resolve"]!!.jsonObject
        val notes = resolve["notes"].toString()
        assertTrue(notes.contains("simple name"), "resolve notes should mention simple names")
    }

    @Test
    fun `discriminated commands include variants`() {
        val json = VersionedCommandSpec.renderJson(version = "test")
        val parsed = defaultCliJson().parseToJsonElement(json).jsonObject
        val commands = parsed["commands"]!!.jsonObject

        val rename = commands["rename"]!!.jsonObject
        val renameVariants = rename["variants"]!!.jsonObject
        assertTrue(renameVariants.containsKey("RENAME_BY_SYMBOL_REQUEST"))
        assertTrue(renameVariants.containsKey("RENAME_BY_OFFSET_REQUEST"))

        val writeAndValidate = commands["write-and-validate"]!!.jsonObject
        val wavVariants = writeAndValidate["variants"]!!.jsonObject
        assertTrue(wavVariants.containsKey("CREATE_FILE_REQUEST"))
        assertTrue(wavVariants.containsKey("INSERT_AT_OFFSET_REQUEST"))
        assertTrue(wavVariants.containsKey("REPLACE_RANGE_REQUEST"))
    }

    @Test
    fun `each command has request fields and success type`() {
        val json = VersionedCommandSpec.renderJson(version = "test")
        val parsed = defaultCliJson().parseToJsonElement(json).jsonObject
        val commands = parsed["commands"]!!.jsonObject

        commands.forEach { (name, element) ->
            val command = element.jsonObject
            assertTrue(command.containsKey("request"), "$name missing request")
            assertTrue(command.containsKey("successType"), "$name missing successType")
            assertTrue(command.containsKey("failureType"), "$name missing failureType")

            val request = command["request"]!!.jsonObject
            assertTrue(request.containsKey("fields"), "$name request missing fields")
        }
    }
}
