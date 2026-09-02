package io.github.amichne.kast.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class IndexSeedActivityTest {
    @Test
    fun `seed activity JSON is bounded and payload free`() {
        val bytes = ByteArrayOutputStream()
        val sink = JsonLineIndexSeedActivitySink(PrintStream(bytes, true, Charsets.UTF_8))

        assertEquals(
            IndexSeedActivityPublication.PUBLISHED,
            sink.publish(IndexSeedActivity.Started(IndexSeedStage.COPY)),
        )
        assertEquals(
            IndexSeedActivityPublication.PUBLISHED,
            sink.publish(
                IndexSeedActivity.Rejected(
                    IndexSeedStage.COPY,
                    IndexSeedFailure.CopyFailure,
                ),
            ),
        )

        val documents = bytes.toString(Charsets.UTF_8).lineSequence()
            .filter(String::isNotBlank)
            .map { line -> Json.parseToJsonElement(line).jsonObject }
            .toList()
        assertEquals(
            listOf("started", "rejected"),
            documents.map { document -> document.getValue("outcome").jsonPrimitive.content },
        )
        assertEquals("copy", documents.last().getValue("stage").jsonPrimitive.content)
        assertEquals(
            "copy-failure",
            documents.last().getValue("reason").jsonPrimitive.content,
        )
        documents.forEach { document ->
            assertEquals("kast-cli", document.getValue("component").jsonPrimitive.content)
            assertEquals("index-seed-stage", document.getValue("event").jsonPrimitive.content)
            assertFalse("sourceSystem" in document)
            assertFalse("cacheRoot" in document)
            assertFalse("manifest" in document)
            assertFalse("payload" in document)
        }
    }
}
