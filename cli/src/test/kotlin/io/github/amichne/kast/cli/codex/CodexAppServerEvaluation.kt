package io.github.amichne.kast.cli.codex

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val MCP_LIST_TIMEOUT_SECONDS = 30L
private const val FUNCTION_TYPE = "function"
private const val NAMESPACE_TYPE = "namespace"

@JvmInline
internal value class CodexMcpServerName private constructor(val value: String) {
    companion object {
        fun admit(raw: String): CodexMcpServerNameAdmission =
            if (raw.isEmpty() || raw.any { !it.isTomlBareKey() }) {
                CodexMcpServerNameAdmission.Rejected
            } else {
                CodexMcpServerNameAdmission.Admitted(CodexMcpServerName(raw))
            }
    }
}

internal sealed interface CodexMcpServerNameAdmission {
    data class Admitted(val name: CodexMcpServerName) : CodexMcpServerNameAdmission
    data object Rejected : CodexMcpServerNameAdmission
}

@Serializable
private data class ConfiguredMcpServerDocument(
    val name: String,
    val enabled: Boolean,
)

internal sealed interface CodexMcpIsolation {
    data class Isolated(val enabledServerNames: List<CodexMcpServerName>) : CodexMcpIsolation
    data object Rejected : CodexMcpIsolation
}

/** Refines inherited Codex configuration into explicit per-server disable arguments. */
internal object CodexMcpIsolationPolicy {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Proof transition: `Path -> CodexMcpIsolation`.
     *
     * An [CodexMcpIsolation.Isolated] result proves every enabled configured MCP server has a
     * printable, bounded name that can be disabled explicitly at the App Server boundary.
     * [CodexMcpIsolation.Rejected] closes unavailable, timed-out, nonzero, malformed, and invalid
     * configuration observations. Raw names leave only after refinement to TOML bare keys.
     */
    fun discover(root: Path): CodexMcpIsolation {
        val process = try {
            ProcessBuilder("codex", "mcp", "list", "--json")
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: IOException) {
            return CodexMcpIsolation.Rejected
        } catch (_: SecurityException) {
            return CodexMcpIsolation.Rejected
        }
        val completed = try {
            process.waitFor(MCP_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return CodexMcpIsolation.Rejected
        }
        if (!completed) {
            process.destroyForcibly()
            return CodexMcpIsolation.Rejected
        }
        val output = try {
            process.inputStream.bufferedReader().readText()
        } catch (_: IOException) {
            return CodexMcpIsolation.Rejected
        }
        if (process.exitValue() != 0) return CodexMcpIsolation.Rejected
        val documents = try {
            json.decodeFromString(ListSerializer(ConfiguredMcpServerDocument.serializer()), output)
        } catch (_: IllegalArgumentException) {
            return CodexMcpIsolation.Rejected
        }
        val enabled = documents.filter(ConfiguredMcpServerDocument::enabled)
        val names = buildList {
            enabled.forEach { server ->
                when (val admission = CodexMcpServerName.admit(server.name)) {
                    is CodexMcpServerNameAdmission.Admitted -> add(admission.name)
                    CodexMcpServerNameAdmission.Rejected -> return CodexMcpIsolation.Rejected
                }
            }
        }
        return CodexMcpIsolation.Isolated(
            names.distinctBy(CodexMcpServerName::value),
        )
    }
}

internal object CodexAppServerLaunchCommand {
    fun create(
        toolAccess: AppServerToolAccess,
        enabledMcpServers: List<CodexMcpServerName>,
    ): List<String> = buildList {
        addAll(
            listOf(
                "codex",
                "app-server",
                "--disable",
                "hooks",
                "--disable",
                "plugins",
                "--disable",
                "apps",
                "--disable",
                "enable_mcp_apps",
            ),
        )
        if (toolAccess == AppServerToolAccess.DYNAMIC_TOOLS_ONLY) {
            addAll(listOf("--disable", "shell_tool"))
        }
        enabledMcpServers.sortedBy(CodexMcpServerName::value).forEach { name ->
            addAll(listOf("-c", "mcp_servers.${name.value}.enabled=false"))
        }
        add("--stdio")
    }
}

private fun Char.isTomlBareKey(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_' || this == '-'

fun main(args: Array<String>) {
    require(args.size == 2) { "expected request and evidence paths" }
    val admission = CodexEvaluationRequestLoader.load(Path.of(args[0]))
    val admitted = admission as? CodexEvaluationRequestAdmission.Admitted
                   ?: error(
                       "evaluation request rejected: " +
                           (admission as CodexEvaluationRequestAdmission.Rejected).failure,
                   )
    CodexAppServerSpike(
        admitted.root,
        Path.of(args[1]).toAbsolutePath().normalize(),
        admitted.request,
    ).run()
}

internal object CodexEvaluationWorkflowPrompt {
    private val json = Json

    fun forSymbol(symbolQuery: String): String {
        val query = json.encodeToString(String.serializer(), symbolQuery)
        return """
            Find the exact Kotlin class named $query, then show its direct callers using Kast.
            Inspect ALL_TOOLS exactly once for Kast capabilities; do not search for shell separately.
            If tools.kast__symbol_resolve and tools.kast__relation_read are present, use only those tools from one exec program. Do not inspect their implementations or use web, filesystem, shell, CLI, MCP, or another tool. The exec API returns the dynamic tool's text content as a JSON string. Run `const resolved = JSON.parse(await tools.kast__symbol_resolve({query:$query}));`, retain that parsed Kast document, run `const selector = resolved.body.result.symbol.selector;`, and pass that exact selector unchanged to tools.kast__relation_read({exactSelector:selector, relation:"callers"}). Do not call symbol_resolve again. Print both returned documents, then answer from them.
            Otherwise, use the public kast CLI.
        """.trimIndent()
    }
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
