package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.RelationReadResult
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
import io.github.amichne.kast.protocol.contract.SymbolTextScopeDocument
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal object CanonicalSymbolSerializers {
    val discoverRequest = jsonContractSerializer<SymbolDiscoverRequest>(
        "kast.symbol.discover.request.v1",
        encode = { request ->
            buildJsonObject {
                put("target", request.target.asJson())
                put("limit", request.limit.asJson())
            }
        },
        decode = { element ->
            val value = element.objectWithFields("target", "limit")
            SymbolDiscoverRequest(value.getValue("target").discoverTarget(), value.protocolCount("limit"))
        },
    )

    val discoverResult = jsonContractSerializer<SymbolDiscoverResult>(
        "kast.symbol.discover.result.v1",
        encode = { result ->
            JsonObject(mapOf("items" to JsonArray(result.items.values.map { it.asJson() })))
        },
        decode = { element ->
            val value = element.objectWithFields("items")
            SymbolDiscoverResult(value.discoveryDocuments("items"))
        },
    )

    val describeResult = jsonContractSerializer<SymbolDescribeResult>(
        "kast.symbol.describe.result.v1",
        encode = { JsonObject(mapOf("symbol" to it.symbol.asJson())) },
        decode = { SymbolDescribeResult(it.objectWithFields("symbol").getValue("symbol").symbol()) },
    )

    val relationResult = jsonContractSerializer<RelationReadResult>(
        "kast.relation.read.result.v1",
        encode = { JsonObject(mapOf("targets" to it.targets.symbolsJson())) },
        decode = { RelationReadResult(it.objectWithFields("targets").symbols("targets")) },
    )

    val traversalResult = jsonContractSerializer<TraversalRunResult>(
        "kast.traversal.run.result.v1",
        encode = { JsonObject(mapOf("reached" to it.reached.symbolsJson())) },
        decode = { TraversalRunResult(it.objectWithFields("reached").symbols("reached")) },
    )
}

private fun SymbolDiscoverTargetDocument.asJson(): JsonObject = when (this) {
    is SymbolDiscoverTargetDocument.Name -> buildJsonObject {
        put("type", "name")
        put("query", query.asJson())
        put("kind", kind.name.lowercase())
        put("match", match.name.lowercase().replace('_', '-'))
    }
    is SymbolDiscoverTargetDocument.Location -> buildJsonObject {
        put("type", "location")
        put("file", file.asJson())
        put("offset", offset.value)
    }
    is SymbolDiscoverTargetDocument.Structure -> buildJsonObject {
        put("type", "structure")
        put("file", file.asJson())
    }
    is SymbolDiscoverTargetDocument.Text -> buildJsonObject {
        put("type", "text")
        put("query", query.asJson())
        put("scope", scope.asJson())
    }
}

private fun JsonElement.discoverTarget(): SymbolDiscoverTargetDocument {
    val value = try {
        jsonObject
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Invalid discovery target")
    }
    return when (value.getValue("type").stringValue()) {
        "name" -> {
            value.requireFields("type", "query", "kind", "match")
            SymbolDiscoverTargetDocument.Name(
                value.protocolText("query"),
                value.enumValue("kind"),
                value.enumValue("match", hyphenated = true),
            )
        }
        "location" -> {
            value.requireFields("type", "file", "offset")
            SymbolDiscoverTargetDocument.Location(
                value.protocolText("file"),
                value.protocolOffset("offset"),
            )
        }
        "structure" -> {
            value.requireFields("type", "file")
            SymbolDiscoverTargetDocument.Structure(value.protocolText("file"))
        }
        "text" -> {
            value.requireFields("type", "query", "scope")
            SymbolDiscoverTargetDocument.Text(
                value.protocolText("query"),
                value.getValue("scope").textScope(),
            )
        }
        else -> throw SerializationException("Unknown discovery target")
    }
}

private fun SymbolTextScopeDocument.asJson(): JsonObject = when (this) {
    SymbolTextScopeDocument.Workspace -> buildJsonObject { put("type", "workspace") }
    is SymbolTextScopeDocument.File -> buildJsonObject {
        put("type", "file")
        put("file", file.asJson())
    }
}

private fun JsonElement.textScope(): SymbolTextScopeDocument {
    val value = try {
        jsonObject
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Invalid text scope")
    }
    return when (value.getValue("type").stringValue()) {
        "workspace" -> {
            value.requireFields("type")
            SymbolTextScopeDocument.Workspace
        }
        "file" -> {
            value.requireFields("type", "file")
            SymbolTextScopeDocument.File(value.protocolText("file"))
        }
        else -> throw SerializationException("Unknown text scope")
    }
}

private fun SymbolDiscoveryDocument.asJson(): JsonObject = when (this) {
    is SymbolDiscoveryDocument.File -> buildJsonObject {
        put("type", "file")
        put("name", name.asJson())
        put("file", file.asJson())
    }
    is SymbolDiscoveryDocument.Declaration -> buildJsonObject {
        put("type", "declaration")
        put("candidateSelector", candidateSelector.asJson())
        put("kind", kind.name.lowercase())
        put("name", name.asJson())
        put("file", file.asJson())
        put("offset", offset.value)
    }
    is SymbolDiscoveryDocument.TextMatch -> buildJsonObject {
        put("type", "text-match")
        put("query", query.asJson())
        put("file", file.asJson())
        put("range", range.asJson())
    }
}

private fun JsonObject.discoveryDocuments(name: String): BoundedProtocolList<SymbolDiscoveryDocument> {
    val documents = try {
        getValue(name).jsonArray.map { it.discoveryDocument() }
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Invalid $name")
    }
    return BoundedProtocolList.create(documents).refined("Invalid $name")
}

private fun JsonElement.discoveryDocument(): SymbolDiscoveryDocument {
    val value = try {
        jsonObject
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Invalid discovery item")
    }
    return when (value.getValue("type").stringValue()) {
        "file" -> {
            value.requireFields("type", "name", "file")
            SymbolDiscoveryDocument.File(value.protocolText("name"), value.protocolText("file"))
        }
        "declaration" -> {
            value.requireFields("type", "candidateSelector", "kind", "name", "file", "offset")
            SymbolDiscoveryDocument.Declaration(
                value.protocolText("candidateSelector"),
                value.enumValue<SymbolDiscoveryKindDocument>("kind"),
                value.protocolText("name"),
                value.protocolText("file"),
                value.protocolOffset("offset"),
            )
        }
        "text-match" -> {
            value.requireFields("type", "query", "file", "range")
            SymbolDiscoveryDocument.TextMatch(
                value.protocolText("query"),
                value.protocolText("file"),
                value.getValue("range").sourceRange(),
            )
        }
        else -> throw SerializationException("Unknown discovery item")
    }
}

private fun SymbolDocument.asJson(): JsonObject = buildJsonObject {
    put("selector", selector.asJson())
    put("kind", kind.name.lowercase().replace('_', '-'))
    put("name", name.asJson())
    put("qualifiedIdentity", qualifiedIdentity.asJson())
    put("file", file.asJson())
    put("range", range.asJson())
}

private fun JsonElement.symbol(): SymbolDocument {
    val value = objectWithFields("selector", "kind", "name", "qualifiedIdentity", "file", "range")
    return SymbolDocument(
        value.protocolText("selector"),
        value.enumValue("kind", hyphenated = true),
        value.protocolText("name"),
        value.getValue("qualifiedIdentity").qualifiedIdentity(),
        value.protocolText("file"),
        value.getValue("range").sourceRange(),
    )
}

private fun SymbolQualifiedIdentityDocument.asJson(): JsonElement = when (this) {
    is SymbolQualifiedIdentityDocument.Available -> value.asJson()
    SymbolQualifiedIdentityDocument.Unavailable -> JsonNull
}

private fun JsonElement.qualifiedIdentity(): SymbolQualifiedIdentityDocument =
    if (this is JsonPrimitive && isString) {
        SymbolQualifiedIdentityDocument.Available(
            io.github.amichne.kast.protocol.contract.ProtocolText.parse(content)
                .refined("Invalid qualified identity"),
        )
    } else if (this === JsonNull) {
        SymbolQualifiedIdentityDocument.Unavailable
    } else {
        throw SerializationException("Invalid qualified identity")
    }

private fun SourceRangeDocument.asJson(): JsonObject = buildJsonObject {
    put("startInclusive", startInclusive.value)
    put("endExclusive", endExclusive.value)
}

private fun JsonElement.sourceRange(): SourceRangeDocument {
    val value = objectWithFields("startInclusive", "endExclusive")
    return SourceRangeDocument.create(
        value.protocolOffset("startInclusive"),
        value.protocolOffset("endExclusive"),
    ).refined("Invalid source range")
}

private fun BoundedProtocolList<SymbolDocument>.symbolsJson(): JsonArray =
    JsonArray(values.map { it.asJson() })

private fun JsonObject.symbols(name: String): BoundedProtocolList<SymbolDocument> {
    val values = try {
        getValue(name).jsonArray.map { it.symbol() }
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Invalid $name")
    }
    return BoundedProtocolList.create(values).refined("Invalid $name")
}

private fun JsonObject.protocolOffset(name: String): ProtocolOffset {
    val raw = getValue(name).let { element ->
        try {
            element.jsonPrimitive.intOrNull
        } catch (_: IllegalArgumentException) {
            null
        }
    } ?: throw SerializationException("Invalid $name")
    return ProtocolOffset.parse(raw).refined("Invalid $name")
}

private fun JsonObject.requireFields(vararg fields: String) {
    if (keys != fields.toSet()) throw SerializationException("Unexpected JSON object fields")
}

private inline fun <reified Value : Enum<Value>> JsonObject.enumValue(
    field: String,
    hyphenated: Boolean = false,
): Value {
    val raw = getValue(field).stringValue()
    val normalized = if (hyphenated) raw.replace('-', '_') else raw
    return try {
        enumValueOf(normalized.uppercase())
    } catch (_: IllegalArgumentException) {
        throw SerializationException("Invalid $field")
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(message: String): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> throw SerializationException(message)
}
