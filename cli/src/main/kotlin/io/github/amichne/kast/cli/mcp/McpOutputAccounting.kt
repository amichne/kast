package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.protocol.contract.QueryDeferredDetailDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentOriginalOutcome
import io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadQuery
import io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadRequest
import io.github.amichne.kast.protocol.wire.presentation.QueryPresentationHeaderDocument
import io.github.amichne.kast.protocol.wire.presentation.QueryTraceHeadline
import io.github.amichne.kast.protocol.wire.presentation.querySemanticHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** The entire JSON-RPC response plus its LF frame. This allowance is separate from canonical maxReturnedBytes. */
internal const val MCP_RESPONSE_LIMIT_BYTES: Int = 4 * 1024 * 1024

private val headerJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

internal fun encodeBoundedMcpResponse(response: McpResponse): String {
    val encoded = mcpWire.encodeToString(McpResponse.serializer(), response)
    if (encoded.toByteArray(Charsets.UTF_8).size + 1 <= MCP_RESPONSE_LIMIT_BYTES) return encoded
    val result = response.result as? JsonObject
    val document = result?.get("structuredContent") as? JsonObject ?: emptyMcpArguments
    val limit = outputLimit(document)
    val structured = headerJson.encodeToJsonElement(McpTransportRejection.serializer(), limit).jsonObject
    val control = success(response.id, McpCallResult(listOf(McpTextContent(structured.toString())), true, structured))
    val bounded = mcpWire.encodeToString(McpResponse.serializer(), control)
    check(bounded.toByteArray(Charsets.UTF_8).size + 1 <= MCP_RESPONSE_LIMIT_BYTES) {
        "Finite MCP control exceeded its protocol allowance"
    }
    return bounded
}

private fun outputLimit(document: JsonObject): McpTransportRejection.OutputLimit {
    if (document["type"] == JsonPrimitive("QUERY_PRESENTATION_HEADER")) {
        val header = headerJson.decodeFromJsonElement(QueryPresentationHeaderDocument.serializer(), document)
        return McpTransportRejection.OutputLimit(
            MCP_RESPONSE_LIMIT_BYTES,
            header.semantic,
            header.headline,
            header.deferredEntry,
            header.nextQuery,
        )
    }
    val semantic = querySemanticHeader(document, document.originalOutcome())
    if (document["operation"] != JsonPrimitive("query.run")) {
        return McpTransportRejection.OutputLimit(MCP_RESPONSE_LIMIT_BYTES, semantic)
    }
    return queryOutputLimit(document, semantic)
}

private fun JsonObject.originalOutcome(): QuerySemanticHeaderDocumentOriginalOutcome =
    when ((get("status") as? JsonPrimitive)?.contentOrNull) {
        "complete" -> QuerySemanticHeaderDocumentOriginalOutcome.COMPLETE
        "qualified" -> QuerySemanticHeaderDocumentOriginalOutcome.QUALIFIED
        else -> QuerySemanticHeaderDocumentOriginalOutcome.REJECTED_DOCUMENT
    }

private fun queryOutputLimit(
    document: JsonObject,
    semantic: io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocument,
): McpTransportRejection.OutputLimit {
    val evidence =
        ((document["rejection"] as? JsonObject)?.get("detail") as? JsonObject)?.get("evidence") as? JsonObject
    val issued =
        ((document["nextQuery"] ?: evidence?.get("nextQuery")) as? JsonObject)?.let {
            headerJson.decodeFromJsonElement(QueryCompletionPublicReadQuery.serializer(), it)
        }
    val entry = firstDeferredEntry(document, evidence)
    val read = issued?.selectDeferred(entry)
    return McpTransportRejection.OutputLimit(
        MCP_RESPONSE_LIMIT_BYTES,
        if (entry != null && read != null) semantic.copy(presentation = semantic.presentation.copy(deferredCount = 1))
        else semantic,
        document["headline"]?.let { headerJson.decodeFromJsonElement(QueryTraceHeadline.serializer(), it) },
        entry?.takeIf { read != null },
        read,
    )
}

private fun QueryCompletionPublicReadQuery.selectDeferred(
    entry: QueryDeferredDetailDocument?
): QueryCompletionPublicReadQuery? =
    when {
        entry == null -> this
        request.result != entry.selector.result -> null
        request.detail == entry.selector -> this
        else ->
            QueryCompletionPublicReadQuery(
                QueryCompletionPublicReadRequest.detail(request.result, request.output, entry.selector)
            )
    }

private fun firstDeferredEntry(document: JsonObject, evidence: JsonObject?): QueryDeferredDetailDocument? {
    val rows = (document["items"] ?: evidence?.get("preview")) as? JsonArray
    val row = rows?.firstNotNullOfOrNull {
        (it as? JsonObject)?.takeIf { item -> item["type"] == JsonPrimitive("DEFERRED_DETAIL") }?.get("detail")
    }
    val entry = row ?: (document["deferred_evidence"] as? JsonArray)?.firstOrNull() ?: return null
    return headerJson.decodeFromJsonElement(QueryDeferredDetailDocument.serializer(), entry)
}
