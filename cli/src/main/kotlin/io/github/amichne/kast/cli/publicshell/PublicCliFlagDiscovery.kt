package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun flagDescriptor(
    flag: PublicCliFlag,
    input: JsonObject,
    catalog: PublicToolCatalog,
    command: PublicCliCommandId,
): PublicCliFlagDescriptor {
    if (command == PublicCliCommandId.DESCRIBE) return describeFlag(flag)
    if (command == PublicCliCommandId.DIAGNOSTIC_CHECK) return diagnosticFlagDescriptor(flag, input)
    val schema = queryFlagSchema(flag, input, catalog)
    val values =
        when (flag) {
            PublicCliFlag.CATALOG -> PublicToolCatalog.entries.map { it.name.lowercase() }
            PublicCliFlag.TERMINAL -> listOf("emit", "trace")
            PublicCliFlag.OUTPUT -> listOf("symbols", "occurrences")
            PublicCliFlag.TEXT -> listOf("complete", "none")
            PublicCliFlag.SECTION -> listOf("input", "output", "examples")
            else -> enumSpellings(schema)
        }
    return PublicCliFlagDescriptor(flag.spelling, flag.takesValue, flag.repeated, schema, values)
}

private fun queryFlagSchema(flag: PublicCliFlag, input: JsonObject, catalog: PublicToolCatalog): JsonObject? {
    val definitions = input.getValue("\$defs").jsonObject
    fun property(complete: String, common: String, property: String): JsonObject? =
        ((definitions[if (catalog == PublicToolCatalog.COMPLETE) complete else common] as? JsonObject)?.get(
                "properties"
            ) as? JsonObject)
            ?.get(property) as? JsonObject
    return when (flag) {
        PublicCliFlag.NAME -> property("SearchSource", "Search", "declarationName")
        PublicCliFlag.NAME_MATCH -> property("SearchSource", "Search", "nameMatch")
        PublicCliFlag.KIND -> property("SearchSource", "Search", "declarationKinds")
        PublicCliFlag.DIRECTORY -> property("DirectoryScope", "Scope", "relativeDirectoryPath")
        PublicCliFlag.SOURCE_SET -> property("DirectoryScope", "Scope", "sourceSetNames")
        PublicCliFlag.FIELDS -> property("SymbolsOutput", "Symbols", "fields")
        PublicCliFlag.RELATION -> property("ExpandRelation", "Relation", "relation")
        PublicCliFlag.RETAIN -> property("RunAction", "Run", "retention")
        PublicCliFlag.MAX_RESULTS -> property("ExecutionBudget", "ExecutionBudget", "maxResults")
        PublicCliFlag.MAX_BYTES -> property("ExecutionBudget", "ExecutionBudget", "maxReturnedBytes")
        PublicCliFlag.MAX_ELAPSED -> property("ExecutionBudget", "ExecutionBudget", "maxElapsedMs")
        PublicCliFlag.CURSOR ->
            definitions["ResultCursor"] as? JsonObject ?: property("ReadResultAction", "ReadResultAction", "cursor")
        PublicCliFlag.EVIDENCE_CURSOR ->
            definitions["EvidenceCursor"] as? JsonObject
                ?: property("ReadResultAction", "ReadResultAction", "evidence_cursor")
        PublicCliFlag.REGION -> property("ReadSourceAction", "ReadSource", "region")
        PublicCliFlag.FILE -> property("LocationSource", "LocationSource", "file")
        PublicCliFlag.OFFSET -> property("LocationSource", "LocationSource", "offset")
        PublicCliFlag.CONTINUATION -> property("ResumeAction", "Resume", "continuation")
        PublicCliFlag.VERBOSE -> input.getValue("properties").jsonObject["verbose"] as? JsonObject
        PublicCliFlag.WORKSPACE,
        PublicCliFlag.CATALOG,
        PublicCliFlag.JSON,
        PublicCliFlag.JQ,
        PublicCliFlag.JQ_EXECUTABLE,
        PublicCliFlag.JQ_TIMEOUT,
        PublicCliFlag.JQ_BYTES,
        PublicCliFlag.HELP,
        PublicCliFlag.REQUEST,
        PublicCliFlag.REF,
        PublicCliFlag.TERMINAL,
        PublicCliFlag.OUTPUT,
        PublicCliFlag.TEXT,
        PublicCliFlag.SECTION,
        PublicCliFlag.POINTER -> null
        PublicCliFlag.PATH,
        PublicCliFlag.MAX_DIAGNOSTICS -> null
    }
}

private fun diagnosticFlagDescriptor(flag: PublicCliFlag, input: JsonObject): PublicCliFlagDescriptor {
    val property =
        when (flag) {
            PublicCliFlag.PATH -> "relativePath"
            PublicCliFlag.MAX_DIAGNOSTICS -> "maxDiagnostics"
            PublicCliFlag.CONTINUATION -> "continuation"
            PublicCliFlag.VERBOSE -> "verbose"
            else -> null
        }
    val schema = property?.let { input.getValue("properties").jsonObject[it] as? JsonObject }
    return PublicCliFlagDescriptor(flag.spelling, flag.takesValue, flag.repeated, schema)
}

private fun describeFlag(flag: PublicCliFlag): PublicCliFlagDescriptor =
    PublicCliFlagDescriptor(
        flag.spelling,
        flag.takesValue,
        flag.repeated,
        valueSpellings =
            when (flag) {
                PublicCliFlag.SECTION -> listOf("input", "output", "examples")
                PublicCliFlag.CATALOG -> PublicToolCatalog.entries.map { it.name.lowercase() }
                else -> emptyList()
            },
    )

private fun enumSpellings(schema: JsonObject?): List<String> {
    val values = schema?.get("enum") as? JsonArray ?: (schema?.get("items") as? JsonObject)?.get("enum") as? JsonArray
    return values.orEmpty().mapNotNull { value ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()?.replace('_', '-')
    }
}
