package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueryResultRetentionSchemaTest {
    @Test
    fun `installed success schema rejects obsolete retention capacity state`() {
        with(LiveReadOutputSchemaTest()) {
            val document = Json.parseToJsonElement(completeQueryDocument().value).jsonObject
            assertAdmits(CanonicalOperation.QUERY_RUN, document)
            assertEquals(
                "not_requested",
                document.getValue("retention").jsonObject.getValue("kind").jsonPrimitive.content,
            )
            val obsolete = Json.encodeToJsonElement(ObsoleteRetentionDocument("capacity_exceeded"))
            assertRejects(CanonicalOperation.QUERY_RUN, document.with("retention", obsolete))
        }
    }
}

@Serializable private data class ObsoleteRetentionDocument(val kind: String)
