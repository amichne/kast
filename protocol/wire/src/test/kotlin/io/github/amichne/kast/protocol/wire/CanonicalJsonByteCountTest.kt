package io.github.amichne.kast.protocol.wire

import com.sun.management.ThreadMXBean
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.lang.management.ManagementFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter

class CanonicalJsonByteCountTest {
    @Test
    fun `measurement preserves escapes unicode and malformed surrogate replacement`() {
        val cases =
            listOf(
                "" to 2L,
                "ascii" to 7L,
                "é水🚀" to 11L,
                "\"\\\n\t\b\r\u000c\u0001" to 22L,
                "\uD800" to 3L,
                "\uDC00" to 3L,
                "\uD800\uD800\uDC00" to 7L,
            )
        for ((value, expected) in cases) {
            assertEquals(expected, CanonicalJsonDocument.encodedBytes(String.serializer(), value))
            assertEquals(
                expected,
                CanonicalJsonDocument.generated(String.serializer())
                    .create(value)
                    .value
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong(),
            )
        }
    }

    @Test
    fun `measurement preserves canonical discriminators defaults nulls and collection punctuation`() {
        val serializer = ListSerializer(Document.serializer())
        val values = listOf(Document.Text("é"), Document.Text("", optional = ""))
        val document = CanonicalJsonDocument.generated(serializer).create(values).value
        val rows = Json.parseToJsonElement(document).jsonArray
        assertEquals(2, rows.size)
        for (row in rows.map { it.jsonObject }) {
            assertEquals(setOf("type", "text", "number", "optional"), row.keys)
            assertEquals("TEXT", row.getValue("type").jsonPrimitive.content)
            assertEquals(Long.MIN_VALUE, row.getValue("number").jsonPrimitive.long)
        }
        assertEquals("é", rows[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(JsonNull, rows[0].jsonObject.getValue("optional"))
        assertEquals("", rows[1].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("", rows[1].jsonObject.getValue("optional").jsonPrimitive.content)
        assertEquals(145, document.toByteArray(Charsets.UTF_8).size)
        assertEquals(145L, CanonicalJsonDocument.encodedBytes(serializer, values))
        assertEquals(2L, CanonicalJsonDocument.encodedBytes(serializer, emptyList()))
    }

    @Test
    fun `measurement avoids a document sized allocation`(reporter: TestReporter) {
        val bean = assertInstanceOf(ThreadMXBean::class.java, ManagementFactory.getThreadMXBean())
        assertTrue(bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled)
        val serializer = ListSerializer(String.serializer())
        val factory = CanonicalJsonDocument.generated(serializer)
        val field = "x".repeat(8_192)
        // Initialize serializers and their reusable buffers outside the measured interval.
        repeat(3) {
            CanonicalJsonDocument.encodedBytes(serializer, listOf(field, field))
            factory.create(listOf(field, field)).value.toByteArray(Charsets.UTF_8)
        }
        for (count in listOf(128, 256, 512)) {
            val values = List(count) { field }
            val expectedBytes = count.toLong() * (field.length + 3) + 1
            val thread = Thread.currentThread().threadId()
            val materializedBefore = bean.getThreadAllocatedBytes(thread)
            val materializedSize = factory.create(values).value.toByteArray(Charsets.UTF_8).size
            val materialized = bean.getThreadAllocatedBytes(thread) - materializedBefore
            val before = bean.getThreadAllocatedBytes(thread)
            val actualBytes = CanonicalJsonDocument.encodedBytes(serializer, values)
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            reporter.publishEntry(
                "fields-$count",
                "encodedBytes=$actualBytes allocatedBytes=$allocated materializedBytes=$materialized",
            )
            assertEquals(expectedBytes, materializedSize.toLong())
            assertEquals(expectedBytes, actualBytes)
            assertTrue(
                materialized - allocated >= expectedBytes,
                "Counting allocated $allocated vs materialized $materialized bytes",
            )
        }
    }

    @Serializable
    private sealed interface Document {
        @Serializable
        @SerialName("TEXT")
        data class Text(val text: String, val number: Long = Long.MIN_VALUE, val optional: String? = null) : Document
    }
}
