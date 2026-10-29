package io.github.amichne.kast.cli

import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

internal data class IndependentSchemaGraph(val root: JsonObject, val definitions: Map<String, JsonElement>)

/** Compares shared descriptors without allocating their expanded trees. */
internal class LocalSchemaGraphAssertions(
    private val actual: JsonObject,
    private val expected: IndependentSchemaGraph,
) {
    private val definitions = actual["\$defs"]?.jsonObject.orEmpty()
    private val compared = IdentityHashMap<JsonElement, IdentityHashMap<JsonElement, Boolean>>()

    fun assertEquivalent() {
        compare(expected.root, actual, "root", allowDefinitions = true)
        expected.definitions.forEach { (name, shape) ->
            definitions[name]?.let { compare(shape, it, "definition/$name") }
        }
    }

    fun assertDefinitionEquivalent(name: String) {
        compare(expected.root, definitions.getValue(name), "definition/$name")
    }

    private fun compare(left: JsonElement, right: JsonElement, path: String, allowDefinitions: Boolean = false) {
        val expectedShape = resolve(left, expected.definitions)
        val actualShape = resolve(right, definitions)
        if (compared.getOrPut(expectedShape) { IdentityHashMap() }.put(actualShape, true) != null) return
        when (expectedShape) {
            is JsonObject -> compareObject(expectedShape, actualShape, path, allowDefinitions)
            is JsonArray -> {
                assertTrue(actualShape is JsonArray, "Expected array at $path")
                actualShape as JsonArray
                assertEquals(expectedShape.size, actualShape.size, "Array length at $path")
                expectedShape.indices.forEach { index ->
                    compare(expectedShape[index], actualShape[index], "$path/$index")
                }
            }
            else -> assertEquals(expectedShape, actualShape, "Primitive at $path")
        }
    }

    private fun compareObject(
        expectedShape: JsonObject,
        actualShape: JsonElement,
        path: String,
        allowDefinitions: Boolean,
    ) {
        assertTrue(actualShape is JsonObject, "Expected object at $path")
        actualShape as JsonObject
        val actualKeys = if (allowDefinitions) actualShape.keys - "\$defs" else actualShape.keys
        assertEquals(expectedShape.keys, actualKeys, "Object fields at $path")
        expectedShape.forEach { (key, value) -> compare(value, actualShape.getValue(key), "$path/$key") }
    }

    private fun resolve(element: JsonElement, available: Map<String, JsonElement>): JsonElement {
        var current = element
        val seen = mutableSetOf<String>()
        while (current is JsonObject && "\$ref" in current) {
            assertEquals(setOf("\$ref"), current.keys)
            val reference = current.getValue("\$ref").jsonPrimitive.content
            assertTrue(reference.startsWith("#/\$defs/"), reference)
            val name = reference.removePrefix("#/\$defs/")
            assertTrue(seen.add(name), "Reference-only cycle: $name")
            assertTrue(name in available, "Missing local definition: $name")
            current = available.getValue(name)
        }
        return current
    }
}
