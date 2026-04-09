package io.github.amichne.kast.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

class ParityComparatorTest {

    // --- Exact ---

    @Test
    fun `exact - identical primitives match`() {
        val result = ParityComparator.Exact.compare(
            JsonPrimitive("hello"),
            JsonPrimitive("hello"),
        )
        assertNull(result)
    }

    @Test
    fun `exact - different primitives mismatch`() {
        val result = ParityComparator.Exact.compare(
            JsonPrimitive("hello"),
            JsonPrimitive("world"),
        )
        assertNotNull(result)
    }

    @Test
    fun `exact - identical objects match`() {
        val obj = buildJsonObject {
            put("a", 1)
            put("b", "two")
        }
        assertNull(ParityComparator.Exact.compare(obj, obj))
    }

    @Test
    fun `exact - different field order in JSON objects still matches`() {
        // Kotlin JsonObject uses LinkedHashMap internally but equals is order-independent
        val a = JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))
        val b = JsonObject(mapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1)))
        assertNull(ParityComparator.Exact.compare(a, b))
    }

    @Test
    fun `exact - different array order mismatches`() {
        val a = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        val b = JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1)))
        assertNotNull(ParityComparator.Exact.compare(a, b))
    }

    // --- Unordered ---

    @Test
    fun `unordered - arrays with same elements in different order match`() {
        val comparator = ParityComparator.Unordered(unorderedArrayKeys = emptySet())
        val a = buildJsonObject {
            putJsonArray("refs") {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            }
        }
        val b = buildJsonObject {
            putJsonArray("refs") {
                add(JsonPrimitive("b"))
                add(JsonPrimitive("a"))
            }
        }
        // Top-level object keys are compared exactly; "refs" is not in unorderedArrayKeys
        assertNotNull(comparator.compare(a, b))
    }

    @Test
    fun `unordered - designated array keys compared as sets`() {
        val comparator = ParityComparator.Unordered(unorderedArrayKeys = setOf("refs"))
        val a = buildJsonObject {
            putJsonArray("refs") {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            }
        }
        val b = buildJsonObject {
            putJsonArray("refs") {
                add(JsonPrimitive("b"))
                add(JsonPrimitive("a"))
            }
        }
        assertNull(comparator.compare(a, b))
    }

    @Test
    fun `unordered - different array sizes mismatch`() {
        val comparator = ParityComparator.Unordered(unorderedArrayKeys = setOf("refs"))
        val a = buildJsonObject {
            putJsonArray("refs") {
                add(JsonPrimitive("a"))
            }
        }
        val b = buildJsonObject {
            putJsonArray("refs") {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            }
        }
        assertNotNull(comparator.compare(a, b))
    }

    // --- Structural ---

    @Test
    fun `structural - ignores default volatile keys`() {
        val comparator = ParityComparator.Structural()
        val a = buildJsonObject {
            put("fqName", "sample.greet")
            put("schemaVersion", 1)
            put("backendName", "standalone")
        }
        val b = buildJsonObject {
            put("fqName", "sample.greet")
            put("schemaVersion", 99)
            put("backendName", "intellij")
        }
        assertNull(comparator.compare(a, b))
    }

    @Test
    fun `structural - detects semantic mismatch`() {
        val comparator = ParityComparator.Structural()
        val a = buildJsonObject { put("fqName", "sample.greet") }
        val b = buildJsonObject { put("fqName", "sample.welcome") }
        assertNotNull(comparator.compare(a, b))
    }

    @Test
    fun `structural - custom ignored keys`() {
        val comparator = ParityComparator.Structural(ignoredKeys = setOf("timing"))
        val a = buildJsonObject {
            put("result", "ok")
            put("timing", 123)
        }
        val b = buildJsonObject {
            put("result", "ok")
            put("timing", 999)
        }
        assertNull(comparator.compare(a, b))
    }

    @Test
    fun `structural - nested objects stripped recursively`() {
        val comparator = ParityComparator.Structural(ignoredKeys = setOf("schemaVersion"))
        val a = buildJsonObject {
            put("outer", buildJsonObject {
                put("inner", "value")
                put("schemaVersion", 1)
            })
        }
        val b = buildJsonObject {
            put("outer", buildJsonObject {
                put("inner", "value")
                put("schemaVersion", 42)
            })
        }
        assertNull(comparator.compare(a, b))
    }

    @Test
    fun `structural - missing key in one side reports mismatch`() {
        val comparator = ParityComparator.Structural()
        val a = buildJsonObject { put("a", 1) }
        val b = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        assertNotNull(comparator.compare(a, b))
    }

    @Test
    fun `structural - null values match`() {
        val comparator = ParityComparator.Structural()
        assertNull(comparator.compare(JsonNull, JsonNull))
    }
}
