package io.github.amichne.kast.cli.codex

import kotlinx.serialization.json.Json

private const val FUNCTION_TYPE = "function"
private const val NAMESPACE_TYPE = "namespace"

internal object CodexSpikeWorkflowPrompt {
    val text =
        """
        Find the exact CanonicalSymbolDiscoverHandler, then show its direct callers using Kast.
        Inspect ALL_TOOLS exactly once for Kast capabilities; do not search for shell separately.
        If tools.kast__symbol_resolve and tools.kast__relation_read are present, use only those tools from one exec program. Do not inspect their wrappers or use web, filesystem, shell, CLI, MCP, or another tool. Await tools.kast__symbol_resolve({query:"CanonicalSymbolDiscoverHandler"}), retain its returned JSON in a variable, JSON.parse it once or twice until it is an object, extract body.result.symbol.selector, and pass that exact selector unchanged to tools.kast__relation_read({exactSelector:selector, relation:"callers"}). Do not call symbol_resolve again. Print both returned documents, then answer from them.
        Otherwise, use the public kast CLI.
        """.trimIndent()
}

internal object CodexDynamicToolDefinitions {
    private val json = Json

    fun kastNamespace(): DynamicToolNamespaceDocument = DynamicToolNamespaceDocument(
        type = NAMESPACE_TYPE,
        name = "kast",
        description =
            "Read-only exact Kotlin symbol discovery and relation evidence. The deferred tools " +
                "can be awaited and chained in the same exec program without other tools.",
        tools = listOf(
            DynamicToolFunctionDocument(
                type = FUNCTION_TYPE,
                name = "symbol_resolve",
                description =
                    "Find one exact Kotlin symbol by source name and return canonical Kast " +
                        "symbol.describe JSON; retain its returned JSON and opaque selector for " +
                        "the next call in the same exec program.",
                inputSchema = json.parseToJsonElement(SYMBOL_RESOLVE_SCHEMA),
                deferLoading = true,
            ),
            DynamicToolFunctionDocument(
                type = FUNCTION_TYPE,
                name = "relation_read",
                description =
                    "Read one-hop Kast semantic relations from the exact selector returned by " +
                        "symbol_resolve, passed as exactSelector, without resolving again.",
                inputSchema = json.parseToJsonElement(RELATION_READ_SCHEMA),
                deferLoading = true,
            ),
        ),
    )
}

private val SYMBOL_RESOLVE_SCHEMA =
    """
    {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "query": {
          "type": "string",
          "minLength": 1,
          "description": "Exact Kotlin source name to discover."
        }
      },
      "required": ["query"]
    }
    """.trimIndent()

private val RELATION_READ_SCHEMA =
    """
    {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "exactSelector": {
          "type": "string",
          "minLength": 1,
          "description": "Opaque symbol.selector returned by kast.symbol_resolve."
        },
        "relation": {
          "type": "string",
          "enum": [
            "references",
            "callers",
            "callees",
            "implementations",
            "inheritors",
            "overrides",
            "type_uses"
          ]
        }
      },
      "required": ["exactSelector", "relation"]
    }
    """.trimIndent()
