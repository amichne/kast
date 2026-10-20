package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactValueModelApplicationDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactValueModelProjectionTest {
    @Test
    fun `typed application output keeps actual and reviewed artifact proof with conditional whole projection`() {
        val f = ImpactValueModelProjectionCase()
        val document = f.application.impactDocument(f.references).modelValue()
        val encoded = Json.encodeToJsonElement(ImpactValueModelApplicationDocument.serializer(), document).jsonObject
        assertEquals(setOf("type", "source", "target", "binding", "condition"), encoded.keys)
        assertEquals("COLLECTION_SELECTION", encoded.getValue("type").jsonPrimitive.content)
        val binding = encoded.getValue("binding").jsonObject
        assertEquals(setOf("enclosing", "invocation", "callable", "execution", "model", "artifact"), binding.keys)
        assertEquals("DECLARED", binding.getValue("invocation").jsonObject.getValue("type").jsonPrimitive.content)
        for (endpoint in
            listOf(
                binding.getValue("enclosing"),
                binding.getValue("callable"),
                binding.getValue("execution").jsonObject.getValue("enclosing"),
            )) {
            assertEquals(setOf("declaration", "compilerTarget", "domain", "fingerprint"), endpoint.jsonObject.keys)
            assertEquals(
                setOf("file", "range", "name", "kind", "compiler_evidence"),
                endpoint.jsonObject.getValue("compilerTarget").jsonObject.keys,
            )
        }
        assertArtifactProof(binding)
        assertEquals("ANONYMOUS", binding.getValue("execution").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf("occurrence", "compiler_evidence"),
            binding.getValue("execution").jsonObject.getValue("body").jsonObject.keys,
        )
        val condition = encoded.getValue("condition").jsonObject
        assertEquals(setOf("type", "receiver", "result", "index"), condition.keys)
        assertEquals("SELECTED_ELEMENT", condition.getValue("type").jsonPrimitive.content)
        assertEquals("0", condition.getValue("index").jsonPrimitive.content)
        assertTrue(condition.getValue("receiver").jsonObject.getValue("valuePath").jsonArray.isEmpty())
        assertTrue(condition.getValue("result").jsonObject.getValue("valuePath").jsonArray.isEmpty())
        val selection = document as ImpactValueModelApplicationDocument.CollectionSelection
        assertTrue(selection.source.matchesSite(f.source, f.references))
        assertTrue(selection.target.matchesSite(f.application.target, f.references))
        assertEquals(1, selection.target.predicates.values.size)
        assertEquals(40, selection.target.predicates.values.single().subject.range.start.value)
        assertEquals(80, selection.target.predicates.values.single().subject.range.end.value)
    }

    private fun assertArtifactProof(binding: JsonObject) {
        val actual = binding.getValue("artifact").jsonObject
        assertEquals(setOf("basis", "root", "moduleName", "binaryDigest", "version", "origin"), actual.keys)
        assertEquals(ImpactValueModelProjectionCase.ROOT, actual.getValue("root").jsonPrimitive.content)
        assertEquals("packet-stdlib", actual.getValue("moduleName").jsonPrimitive.content)
        assertEquals("LIBRARY", actual.getValue("origin").jsonPrimitive.content)
        assertEquals(ImpactValueModelProjectionCase.BINARY_SHA, actual.getValue("binaryDigest").jsonPrimitive.content)
        assertEquals(ImpactValueModelProjectionCase.VERSION, actual.getValue("version").jsonPrimitive.content)
        val review = binding.getValue("model").jsonObject
        assertEquals(setOf("type", "reference", "artifact", "callable"), review.keys)
        assertEquals("FIRST_OR_NULL", review.getValue("type").jsonPrimitive.content)
        assertEquals(
            ImpactValueModelProjectionCase.SOURCE_SHA,
            review.getValue("artifact").jsonObject.getValue("sourceDigest").jsonPrimitive.content,
        )
        assertEquals(
            "kotlin.collections.firstOrNull",
            review.getValue("callable").jsonObject.getValue("qualifiedIdentity").jsonPrimitive.content,
        )
    }

    @Test
    fun `unknown applications and malformed artifact primitives reject decoded output`() {
        val f = ImpactValueModelProjectionCase()
        val text =
            Json.encodeToString(
                ImpactValueModelApplicationDocument.serializer(),
                f.application.impactDocument(f.references).modelValue(),
            )
        for (malformed in
            listOf(
                text.replace("\"COLLECTION_SELECTION\"", "\"OPAQUE_IDENTITY\""),
                text.replace(ImpactValueModelProjectionCase.BINARY_SHA, "F".repeat(64)),
                text.replace("\"moduleName\":\"packet-stdlib\"", "\"moduleName\":\"\""),
                text.replace("\"index\":0", "\"index\":1"),
            )) {
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString(ImpactValueModelApplicationDocument.serializer(), malformed)
            }
        }
    }
}
