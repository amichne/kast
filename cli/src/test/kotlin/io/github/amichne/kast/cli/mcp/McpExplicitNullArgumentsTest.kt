package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.direct.directSupportTools
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpExplicitNullArgumentsTest {
    @TempDir lateinit var temporary: Path

    private fun admittedRoot() = FilesystemCanonicalRootDiscovery.discover(temporary)

    @Test
    fun `explicit null arguments are rejected before invoking a tool`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val output = ByteArrayOutputStream()
        val input =
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"health_check","arguments":null}}
            """
                .trimIndent() + "\n"
        KastMcpServer(
                catalog = directSupportTools().filter { it.name == "health_check" },
                invoke = { _, _ -> error("explicit null must not reach invocation") },
                root = { admittedRoot() },
                diagnostic = PrintStream(ByteArrayOutputStream()),
            )
            .run(BufferedInputStream(ByteArrayInputStream(input.toByteArray())), PrintStream(output))
        val result =
            Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim().lineSequence().last())
                .jsonObject
                .getValue("result")
                .jsonObject
        assertTrue(result.getValue("isError").jsonPrimitive.content.toBoolean())
        assertEquals(
            "INVALID_ARGUMENTS",
            result
                .getValue("structuredContent")
                .jsonObject
                .getValue("error")
                .jsonObject
                .getValue("code")
                .jsonPrimitive
                .content,
        )
    }
}
