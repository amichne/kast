package io.github.amichne.kast.appserver.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun ProviderDefinition.identityDocument(): JsonObject = buildJsonObject {
    put("namespace", namespace.value)
    put("providerVersion", version.value)
    put(
        "tools",
        buildJsonArray {
            toolDocuments.sortedBy(ProviderToolDocument::name).forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name.value)
                        put("description", tool.description.value)
                        put("loading", tool.loading.name.lowercase())
                        put("inputSchema", tool.inputSchema.document)
                        put("outputSchema", tool.outputSchema.document)
                    }
                )
            }
        },
    )
}

internal fun ProviderDefinition.catalogNamespace(): CatalogNamespace =
    CatalogNamespace(
        name = namespace,
        description =
            ToolDescription.admit("Typed read-only tools provided by ${namespace.value}.").let { refinement ->
                when (refinement) {
                    is io.github.amichne.kast.kernel.Refinement.Refined -> refinement.value
                    is io.github.amichne.kast.kernel.Refinement.Rejected ->
                        error("Static catalog description violated its construction proof")
                }
            },
        tools =
            toolDocuments.sortedBy(ProviderToolDocument::name).map { tool ->
                CatalogTool(
                    name = tool.name,
                    description = tool.description,
                    loading = tool.loading,
                    inputSchema = tool.inputSchema.document,
                )
            },
    )
