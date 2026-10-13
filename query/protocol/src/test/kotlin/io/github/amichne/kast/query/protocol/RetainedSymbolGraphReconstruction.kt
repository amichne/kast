package io.github.amichne.kast.query.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals

/** Reads the emitted graph independently of production graph projection and identity generation. */
internal class SymbolGraphReconstruction(document: JsonObject) {
    val graph = document.getValue("relation_graph").jsonObject
    val nodes = table("nodes")
    val connections = table("connection_witnesses")
    private val references = table("witnesses")

    fun connection(link: String): SymbolGraphResolvedConnection {
        val entry = connections.getValue(link)
        return when (entry.getValue("type").jsonPrimitive.content) {
            "direct" -> {
                assertEquals("SOURCE_TO_TARGET", entry.getValue("direction").jsonPrimitive.content)
                SymbolGraphResolvedConnection(node(entry, "source"), node(entry, "target"), entry)
            }
            "reference-backed" -> {
                val witness = references.getValue(entry.getValue("witness").jsonPrimitive.content)
                assertEquals("DECLARATION_TO_TARGET", witness.getValue("direction").jsonPrimitive.content)
                val ownership = witness.getValue("ownership").jsonObject
                assertEquals("declaration-owned", ownership.getValue("type").jsonPrimitive.content)
                SymbolGraphResolvedConnection(node(ownership, "declaration"), node(witness, "target"), witness)
            }
            else -> error("An unknown connection cannot establish a lossless relation")
        }
    }

    private fun node(entry: JsonObject, key: String): JsonObject =
        nodes.getValue(entry.getValue(key).jsonPrimitive.content).getValue("symbol").jsonObject

    private fun table(key: String): Map<String, JsonObject> =
        graph.getValue(key).jsonArray.associate { item ->
            item.jsonObject.getValue("id").jsonPrimitive.content to item.jsonObject
        }
}

internal data class SymbolGraphResolvedConnection(
    val source: JsonObject,
    val target: JsonObject,
    val evidence: JsonObject,
)

internal fun assertLosslessRows(full: JsonObject, compact: JsonObject) {
    val reconstruction = SymbolGraphReconstruction(compact)
    val fullRows = full.getValue("items").jsonArray
    val compactRows = compact.getValue("items").jsonArray
    assertEquals(fullRows.size, compactRows.size)
    for ((fullValue, compactValue) in fullRows.zip(compactRows)) {
        val original = fullValue.jsonObject
        val projected = compactValue.jsonObject
        val node = reconstruction.nodes.getValue(projected.getValue("node").jsonPrimitive.content)
        assertEquals(original.getValue("ref"), node.getValue("symbol").jsonObject.getValue("selector"))
        for (field in listOf("kind", "name", "location", "signature", "source", "row_id", "matches")) {
            assertEquals(original[field] ?: JsonNull, projected[field] ?: JsonNull, field)
        }
        val facts = original.getValue("connections").jsonArray
        val links = projected.getValue("connections").jsonArray
        assertEquals(facts.size, links.size)
        for ((factValue, link) in facts.zip(links)) {
            val fact = factValue.jsonObject
            val restored = reconstruction.connection(link.jsonPrimitive.content)
            assertNodeDescription(fact.getValue("source").jsonObject, restored.source)
            assertNodeDescription(fact.getValue("target").jsonObject, restored.target)
            for (field in listOf("meaning", "occurrence", "provenance", "coverage")) {
                assertEquals(fact.getValue(field), restored.evidence.getValue(field), field)
            }
        }
    }
}

internal fun assertNodeDescription(original: JsonObject, compact: JsonObject) {
    for (field in listOf("selector", "kind", "name", "qualifiedIdentity", "file", "range")) {
        assertEquals(original.getValue(field), compact.getValue(field), field)
    }
    assertEquals(
        original.getValue("compilerEvidence").jsonObject.getValue("identity"),
        compact.getValue("compiler_identity"),
    )
}
