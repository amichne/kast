package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.AgentSessionBootstrap
import io.github.amichne.kast.protocol.registry.HostedToolLoading
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Codex-only projection of one already-qualified provider-neutral bootstrap. */
internal data class CodexSessionProjection(
    val developerInstructions: String,
    val namespace: JsonObject,
)

internal fun AgentSessionBootstrap.toCodexSessionProjection(): CodexSessionProjection =
    CodexSessionProjection(
        developerInstructions = policy.text,
        namespace = buildJsonObject {
            put("type", "namespace")
            put("name", "kast")
            put("description", "Compiler-grounded Kotlin source intelligence from Kast.")
            put("tools", buildJsonArray {
                tools.definitions.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", tool.name.value)
                        put("description", tool.description.value)
                        put("inputSchema", tool.inputSchema.document)
                        put("deferLoading", tool.loading == HostedToolLoading.DEFERRED)
                    })
                }
            })
        },
    )
