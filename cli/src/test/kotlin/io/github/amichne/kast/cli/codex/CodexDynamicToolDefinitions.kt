package io.github.amichne.kast.cli.codex

import kotlinx.serialization.json.Json

private const val FUNCTION_TYPE = "function"
private const val NAMESPACE_TYPE = "namespace"

internal object CodexDynamicToolDefinitions {
    private val json = Json

    fun kastNamespace(): DynamicToolNamespaceDocument = DynamicToolNamespaceDocument(
        type = NAMESPACE_TYPE,
        name = "kast",
        description = "Read-only exact Kotlin symbol discovery and semantic relation evidence.",
        tools = listOf(
            DynamicToolFunctionDocument(
                type = FUNCTION_TYPE,
                name = "symbol_resolve",
                description =
                    "Find one exact Kotlin symbol by source name and return canonical Kast " +
                        "symbol.describe JSON, including its opaque selector.",
                inputSchema = json.parseToJsonElement(SYMBOL_RESOLVE_SCHEMA),
                deferLoading = true,
            ),
            DynamicToolFunctionDocument(
                type = FUNCTION_TYPE,
                name = "relation_read",
                description =
                    "Read one-hop Kast semantic relations from the exact selector returned by " +
                        "symbol_resolve.",
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
