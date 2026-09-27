package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.AgentSessionBootstrap
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import io.github.amichne.kast.protocol.registry.PUBLIC_TOOL_NAMESPACE_DESCRIPTION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Codex-only projection of one already-qualified provider-neutral bootstrap. */
internal data class CodexSessionProjection(
    val developerInstructions: String,
    val namespace: JsonObject,
)

@Serializable
private enum class CodexNamespaceType {
    @SerialName("namespace") NAMESPACE
}

@Serializable
private enum class CodexFunctionType {
    @SerialName("function") FUNCTION
}

@Serializable
private data class CodexNamespaceDocument(
    val type: CodexNamespaceType = CodexNamespaceType.NAMESPACE,
    val name: String = "kast",
    val description: String = PUBLIC_TOOL_NAMESPACE_DESCRIPTION,
    val tools: List<CodexFunctionDocument>,
)

@Serializable
private data class CodexFunctionDocument(
    val type: CodexFunctionType = CodexFunctionType.FUNCTION,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val deferLoading: Boolean,
)

private val codexDynamicToolJson = Json { encodeDefaults = true }

internal fun AgentSessionBootstrap.toCodexSessionProjection(): CodexSessionProjection =
    CodexSessionProjection(
        developerInstructions = policy.text,
        namespace =
            codexDynamicToolJson
                .encodeToJsonElement(
                    CodexNamespaceDocument(
                        tools =
                            tools.definitions.map { tool ->
                                CodexFunctionDocument(
                                    name = tool.name.value,
                                    description = tool.description.value,
                                    inputSchema = tool.generationSchema,
                                    deferLoading = tool.loading == HostedToolLoading.DEFERRED,
                                )
                            }
                    )
                )
                .jsonObject,
    )
