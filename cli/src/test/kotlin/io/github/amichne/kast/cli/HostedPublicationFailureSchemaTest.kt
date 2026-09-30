package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedPublicationFailureSchemaTest {
    @Test
    fun `every actual publication failure preserves a closed cause through installed schemas`() {
        val fixtures = publicationFixtures()
        assertEquals(16, fixtures.size)
        assertEquals(
            setOf(
                "OWNER_RETIRED",
                "CLAIM_UNAVAILABLE",
                "EXPIRED",
                "DEPENDENCY_UNAVAILABLE",
                "PUBLISHED_PAGE_MISMATCH",
                "NON_ADVANCING_SUCCESSOR",
                "INVALID_FITTED_PAGE",
                "CAPACITY_EXCEEDED",
            ),
            fixtures.map { it.getValue("detail").jsonObject.getValue("cause").jsonPrimitive.content }.toSet(),
        )
        fixtures.forEach(::assertDocument)
    }

    private fun assertDocument(document: kotlinx.serialization.json.JsonObject) {
        assertTrue(schema.validate(document.toString(), InputFormat.JSON).isEmpty(), document.toString())
        val detail = document.getValue("detail").jsonObject
        val malformed =
            with(LiveReadOutputSchemaTest()) {
                listOf(
                    document.with("detail", JsonNull),
                    document.with("detail", Json.encodeToJsonElement(MissingCause.serializer(), MissingCause())),
                    document.with("detail", detail.with("cause", JsonNull)),
                    document.with("detail", detail.with("cause", JsonPrimitive("UNKNOWN"))),
                    document.with("detail", detail.with("unexpected", JsonPrimitive("value"))),
                    document.with("failure", JsonPrimitive("STALE_REQUEST")),
                )
            }
        malformed.forEach { candidate ->
            assertTrue(schema.validate(candidate.toString(), InputFormat.JSON).isNotEmpty(), candidate.toString())
        }
        with(LiveReadOutputSchemaTest()) {
            for (operation in
                listOf(
                    CanonicalOperation.QUERY_RUN,
                    CanonicalOperation.SOURCE_READ,
                    CanonicalOperation.DIAGNOSTIC_CHECK,
                )) {
                assertAdmits(operation, document)
                malformed.forEach { assertRejects(operation, it) }
            }
        }
    }

    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(CanonicalOperation::class.java.getResource("/ide-hosted/hosted-query.schema.json"))
                    .readText()
            )

    private fun publicationFixtures() =
        Json.decodeFromString<FailureDocuments>(
                checkNotNull(javaClass.getResource("/hosted-read-failure-encodings.json")).readText()
            )
            .documents
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter {
                it.getValue("failure").jsonPrimitive.content == "PUBLICATION_REJECTED"
            }

    @Serializable private data class FailureDocuments(val documents: List<String>)

    @Serializable private class MissingCause
}
