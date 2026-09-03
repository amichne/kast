package io.github.amichne.kast.cli.codex

import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexDynamicToolWorkflowTest {
    @Test
    fun `shared prompt explicitly permits one-search same-program chaining`() {
        val prompt = CodexEvaluationWorkflowPrompt.forSymbol("EnterpriseService")

        assertTrue(prompt.contains("EnterpriseService"))
        assertTrue(prompt.contains("ALL_TOOLS exactly once"))
        assertTrue(prompt.contains("one exec program"))
        assertTrue(prompt.contains("retain that parsed Kast document"))
        assertTrue(prompt.contains("const inspected = JSON.parse(await tools.kast__symbol_inspect"))
        assertTrue(prompt.contains("const selector = inspected.body.result.symbol.selector"))
        assertTrue(prompt.contains("pass that exact selector"))
        assertTrue(prompt.contains("Do not call symbol_inspect again"))
        assertTrue(prompt.contains("Do not inspect their implementations"))
        assertTrue(prompt.contains("or use web, filesystem, shell, CLI, MCP, or another tool"))
        assertTrue(prompt.contains("{exactSelector:selector, relation:\"callers\"}"))
        assertTrue(prompt.contains("Otherwise, use the public kast CLI"))
    }

    @Test
    fun `workflow prompt JSON quotes the configured symbol query`() {
        val prompt = CodexEvaluationWorkflowPrompt.forSymbol("Enterprise\"Service")

        assertTrue(prompt.contains("{query:\"Enterprise\\\"Service\"}"))
        assertFalse(prompt.contains("{query:\"Enterprise\"Service\"}"))
    }

    @Test
    fun `versioned request preserves safe mode and expected callers`() {
        val document = CodexAppServerEvaluationRequestDocument(
            schemaVersion = 1,
            mode = CodexAppServerEvaluationModeDocument.DYNAMIC_ONLY,
            workspaceRoot = "/enterprise/repository",
            symbolQuery = "EnterpriseService",
            expectedCallerNames = listOf("createEnterpriseService"),
            model = "gpt-enterprise-test",
        )

        val encoded = Json.encodeToString(document)
        val decoded = Json.decodeFromString(
            CodexAppServerEvaluationRequestDocument.serializer(),
            encoded,
        )

        assertEquals(document, decoded)
        assertEquals(CodexAppServerEvaluationModeDocument.DYNAMIC_ONLY, decoded.mode)
    }

    @Test
    fun `deferred definitions advertise retained-result chaining`() {
        val namespace = CodexDynamicToolDefinitions.kastNamespace()
        val descriptions = buildList {
            add(namespace.description)
            addAll(namespace.tools.map { it.description })
        }.joinToString("\n")

        assertTrue(descriptions.contains("same exec program"))
        assertTrue(descriptions.contains("retain"))
        assertTrue(descriptions.contains("without inspecting again"))
    }

    @Test
    fun `deferred definitions project the canonical Kast agent tool models`() {
        val namespace = CodexDynamicToolDefinitions.kastNamespace()
        val definitions = CanonicalAgentToolDefinitions.all

        assertEquals(definitions.map { it.name.value }, namespace.tools.map { it.name })
        assertEquals(
            definitions.map { it.description.value },
            namespace.tools.map { it.description },
        )

        val symbolSchema = namespace.tools[0].inputSchema.jsonObject
        assertEquals(
            false,
            symbolSchema.getValue("additionalProperties").jsonPrimitive.content.toBoolean(),
        )
        assertEquals(
            "query",
            symbolSchema.getValue("required").jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(
            1,
            symbolSchema.getValue("properties").jsonObject
                .getValue("query").jsonObject
                .getValue("minLength").jsonPrimitive.content.toInt(),
        )

        val relationSchema = namespace.tools[1].inputSchema.jsonObject
        assertEquals(
            listOf(
                "references",
                "callers",
                "callees",
                "implementations",
                "inheritors",
                "overrides",
                "type_uses",
            ),
            relationSchema.getValue("properties").jsonObject
                .getValue("relation").jsonObject
                .getValue("enum").jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `App Server command suppresses every inherited capability source`() {
        val command = CodexAppServerLaunchCommand.create(
            AppServerToolAccess.DYNAMIC_TOOLS_ONLY,
            listOf(
                admittedMcpName("intellij-index"),
                admittedMcpName("node_repl"),
            ),
        )

        assertTrue(command.containsAll(listOf("--disable", "apps")))
        assertTrue(command.containsAll(listOf("--disable", "enable_mcp_apps")))
        assertTrue(command.containsAll(listOf("--disable", "shell_tool")))
        assertTrue(command.contains("mcp_servers.intellij-index.enabled=false"))
        assertTrue(command.contains("mcp_servers.node_repl.enabled=false"))
        assertFalse(command.contains("mcp_servers={}"))
        assertInstanceOf(
            CodexMcpServerNameAdmission.Rejected::class.java,
            CodexMcpServerName.admit("not\u00a0a\u00a0bare\u00a0key"),
        )
    }

    private fun admittedMcpName(raw: String): CodexMcpServerName =
        assertInstanceOf(
            CodexMcpServerNameAdmission.Admitted::class.java,
            CodexMcpServerName.admit(raw),
        ).name
}
