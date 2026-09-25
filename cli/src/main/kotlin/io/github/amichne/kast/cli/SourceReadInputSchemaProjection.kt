package io.github.amichne.kast.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Exact public MCP input schema generated from the source request serializers. */
internal object SourceReadInputSchemaProjection {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty())
        val tool = installedHostedBindings().single { it.tool.name == "source_read" }.tool
        println(Json.encodeToString(JsonElement.serializer(), tool.inputSchema))
    }
}
