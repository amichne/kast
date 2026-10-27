package io.github.amichne.kast.cli

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryOriginalEvidenceSummaryDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledQueryMetadataSchemaTest {
    @Test
    fun `reused query metadata expands to exact canonical serializer constraints`() {
        val definitions = installedServerOutputSchema(CanonicalOperation.QUERY_RUN).getValue("\$defs").jsonObject
        for ((name, serializer) in metadataSerializers) {
            assertTrue(name in definitions, "Missing shared canonical definition: $name")
            assertEquivalent(
                generatedOutputSchema(serializer),
                definitions.getValue(name),
                definitions,
                name,
            )
        }
    }

    @Test
    fun `shared metadata keeps completion summary and selector constraints necessary`() {
        val definitions = installedServerOutputSchema(CanonicalOperation.QUERY_RUN).getValue("\$defs").jsonObject
        assertEquals(
            setOf("from", "steps", "output", "completion"),
            definitions.getValue("queryQuestion").requiredNames(),
        )
        assertEquals(
            setOf("failure_count", "omission_count", "evidence_count"),
            definitions.getValue("queryOriginalEvidenceSummary").requiredNames(),
        )
        val detail = definitions.getValue("queryEntryDetailSelector")
        assertEquals(
            setOf("result", "section", "target", "ordinal", "kind", "sha256", "offset"),
            detail.requiredNames(),
        )
        assertSelectorDigest(detail.jsonObject)
    }

    @Test
    fun `summary schema keeps optional reason sets closed and rejects an absent count`() {
        val definitions = installedServerOutputSchema(CanonicalOperation.QUERY_RUN).getValue("\$defs").jsonObject
        val summary = definitions.getValue("queryOriginalEvidenceSummary").jsonObject
        val properties = summary.getValue("properties").jsonObject
        assertEquals(summaryReasonFields + summary.requiredNames(), properties.keys)
        for (name in summaryReasonFields) {
            val reason = properties.getValue(name).jsonObject
            assertEquals(JsonPrimitive("array"), reason.getValue("type"), name)
            assertEquals(JsonPrimitive(1000), reason.getValue("maxItems"), name)
            assertTrue(reason.getValue("items").jsonObject.getValue("enum").jsonArray.isNotEmpty(), name)
        }
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(summary.toString())
        val valid = fixtureJson.encodeToString(SummaryReasonFixture.serializer(), SummaryReasonFixture())
        assertTrue(schema.validate(valid, InputFormat.JSON).isEmpty())
        val unknownKind =
            fixtureJson.encodeToString(
                SummaryReasonFixture.serializer(),
                SummaryReasonFixture(failureKinds = listOf("UNRECOGNIZED_REASON")),
            )
        assertTrue(schema.validate(unknownKind, InputFormat.JSON).isNotEmpty())
        val missingCount =
            fixtureJson.encodeToString(MissingFailureCountFixture.serializer(), MissingFailureCountFixture())
        assertTrue(schema.validate(missingCount, InputFormat.JSON).isNotEmpty())
    }

    private fun assertSelectorDigest(detail: JsonObject) {
        val sha256 = detail.getValue("properties").jsonObject.getValue("sha256").jsonObject
        val patterns =
            sha256.getValue("allOf").jsonArray.map { it.jsonObject.getValue("pattern").jsonPrimitive.content }
        assertEquals(setOf("[\\s\\S]*\\S[\\s\\S]*", "^[0-9a-f]{64}$"), patterns.toSet())
        assertEquals(JsonPrimitive(64), sha256.getValue("minLength"))
        assertEquals(JsonPrimitive(64), sha256.getValue("maxLength"))
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(detail.toString())
        val valid = fixtureJson.encodeToString(SelectorShapeFixture.serializer(), SelectorShapeFixture())
        assertTrue(schema.validate(valid, InputFormat.JSON).isEmpty())
        for (digest in listOf("A".repeat(64), "a".repeat(63))) {
            val invalid =
                fixtureJson.encodeToString(SelectorShapeFixture.serializer(), SelectorShapeFixture(sha256 = digest))
            assertTrue(schema.validate(invalid, InputFormat.JSON).isNotEmpty())
        }
    }

    private fun JsonElement.requiredNames(): Set<String> =
        jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun assertEquivalent(
        expected: JsonElement,
        advertised: JsonElement,
        definitions: JsonObject,
        path: String,
    ) {
        // Recursive canonical callback graphs keep their original reference addresses.
        if (expected == advertised) return
        val canonical = resolve(expected, definitions)
        val actual = resolve(advertised, definitions)
        when (canonical) {
            is JsonObject -> {
                assertTrue(actual is JsonObject, path)
                assertEquals(canonical.keys, actual.jsonObject.keys, path)
                canonical.forEach { (name, child) ->
                    assertEquivalent(child, actual.jsonObject.getValue(name), definitions, "$path/$name")
                }
            }
            is JsonArray -> {
                assertTrue(actual is JsonArray, path)
                assertEquals(canonical.size, actual.jsonArray.size, path)
                canonical.forEachIndexed { index, child ->
                    assertEquivalent(child, actual.jsonArray[index], definitions, "$path/$index")
                }
            }
            else -> assertEquals(canonical, actual, path)
        }
    }

    private fun resolve(value: JsonElement, definitions: JsonObject): JsonElement {
        var current = value
        val visited = hashSetOf<String>()
        while (current is JsonObject) {
            val reference = current["\$ref"]?.jsonPrimitive?.content ?: return current
            assertEquals(setOf("\$ref"), current.keys)
            assertTrue(reference.startsWith("#/\$defs/"), reference)
            assertTrue(visited.add(reference), "Cyclic schema alias: $reference")
            current = definitions.getValue(reference.removePrefix("#/\$defs/"))
        }
        return current
    }

    private companion object {
        val fixtureJson = Json { encodeDefaults = true }
        val summaryReasonFields =
            setOf(
                "failure_kinds",
                "omission_reasons",
                "refinement_reasons",
                "exact_reasons",
                "predicate_reasons",
                "source_reasons",
                "source_limitations",
                "relation_reasons",
                "walk_one_hop_reasons",
                "walk_reasons",
            )
        val metadataSerializers: Map<String, KSerializer<*>> =
            mapOf(
                "queryQuestion" to QueryQuestionDocument.serializer(),
                "queryOriginalEvidenceSummary" to QueryOriginalEvidenceSummaryDocument.serializer(),
                "queryEntryDetailSelector" to QueryEntryDetailSelectorDocument.serializer(),
            )
    }
}

@Serializable
private data class SummaryReasonFixture(
    @SerialName("failure_count") val failureCount: Int = 1,
    @SerialName("omission_count") val omissionCount: Int = 0,
    @SerialName("evidence_count") val evidenceCount: Int = 1,
    @SerialName("failure_kinds") val failureKinds: List<String> = listOf("RELATION"),
)

@Serializable
private data class MissingFailureCountFixture(
    @SerialName("omission_count") val omissionCount: Int = 0,
    @SerialName("evidence_count") val evidenceCount: Int = 1,
    @SerialName("failure_kinds") val failureKinds: List<String> = listOf("RELATION"),
)

@Serializable
private data class SelectorShapeFixture(
    val result: String = "result:schema-fixture",
    val section: String = "CANONICAL_ENTRY_JSON",
    val target: String = "ROW",
    val ordinal: Int = 0,
    val kind: String = "EXACT_SYMBOL",
    val sha256: String = "a".repeat(64),
    val offset: Int = 0,
)
