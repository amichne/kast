package io.github.amichne.kast.cli.codex

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexDynamicToolWorkflowTest {
    @Test
    fun `shared prompt explicitly permits one-search same-program chaining`() {
        val prompt = CodexSpikeWorkflowPrompt.text

        assertTrue(prompt.contains("ALL_TOOLS exactly once"))
        assertTrue(prompt.contains("one exec program"))
        assertTrue(prompt.contains("retain its returned JSON"))
        assertTrue(prompt.contains("pass that exact selector"))
        assertTrue(prompt.contains("Do not call symbol_resolve again"))
        assertTrue(prompt.contains("Do not inspect their wrappers"))
        assertTrue(prompt.contains("or use web, filesystem, shell, CLI, MCP, or another tool"))
        assertTrue(prompt.contains("{exactSelector:selector, relation:\"callers\"}"))
        assertTrue(prompt.contains("Otherwise, use the public kast CLI"))
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
        assertTrue(descriptions.contains("without resolving again"))
    }
}
