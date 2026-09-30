package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryDiscoveryUniverseContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `every discovery progress variant declares its Kotlin declaration universe`() {
        val matches =
            listOf(
                QueryMatchDocument.All,
                QueryMatchDocument.Name(text("accepted"), SymbolDiscoveryMatchDocument.EXACT_NAME),
            )
        val progresses =
            listOf(
                QueryDiscoveryProgressDocument.Exhausted to "exhausted",
                QueryDiscoveryProgressDocument.Resumable(
                    count(1),
                    ProtocolOffset.parse(12).refined(),
                    count(2),
                    count(1),
                    QueryDiscoveryBytesDocument.parse(512).refined(),
                ) to "resumable",
                QueryDiscoveryProgressDocument.Blocked(QueryDiscoveryBlockDocument.PARTITION_CAPACITY) to "blocked",
            )
        for (match in matches) {
            for ((progress, kind) in progresses) {
                val observation = observation(match, progress)
                val encoded = json.parseToJsonElement(json.encodeToString(observation)).jsonObject
                val universe = encoded.getValue("universe").jsonObject
                assertEquals(
                    setOf(
                        "declaration_language",
                        "match",
                        "declaration_kinds",
                        "source_policy",
                        "generated_sources",
                        "libraries",
                        "source_sets",
                        "directory",
                        "package_name",
                    ),
                    universe.keys,
                )
                assertEquals("kotlin", universe.getValue("declaration_language").jsonPrimitive.content)
                assertEquals(kind, encoded.getValue("progress").jsonObject.getValue("kind").jsonPrimitive.content)
                assertEquals(
                    if (match is QueryMatchDocument.All) "all" else "name",
                    universe.getValue("match").jsonObject.getValue("type").jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun `missing or unsupported declaration language cannot be admitted`() {
        val encoded = json.encodeToString(observation(QueryMatchDocument.All, QueryDiscoveryProgressDocument.Exhausted))
        val missing = encoded.replace("\"declaration_language\":\"kotlin\",", "")
        val unsupported = encoded.replace("\"declaration_language\":\"kotlin\"", "\"declaration_language\":\"java\"")
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<QueryDiscoveryObservationDocument>(missing)
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<QueryDiscoveryObservationDocument>(unsupported)
        }
    }

    private fun observation(
        match: QueryMatchDocument,
        progress: QueryDiscoveryProgressDocument,
    ): QueryDiscoveryObservationDocument =
        QueryDiscoveryObservationDocument(
            QueryDiscoveryUniverseDocument(
                QueryDiscoveryDeclarationLanguageDocument.KOTLIN,
                match,
                BoundedProtocolList.create(listOf(QueryDeclarationKindDocument.FUNCTION)).refined(),
                QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
                QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                QueryDiscoverySourceSetsDocument.All,
                null,
                null,
            ),
            if (match is QueryMatchDocument.All) QueryDiscoveryOrderDocument.KOTLIN_FILE_SOURCE_V2
            else QueryDiscoveryOrderDocument.NAMED_CANDIDATE_V1,
            progress,
            BoundedProtocolList.create(emptyList<QueryDiscoveryStopDocument>()).refined(),
            count(0),
            count(0),
            count(0),
            count(0),
            nanos(0),
            nanos(0),
            nanos(0),
        )

    private fun text(value: String): ProtocolText = ProtocolText.parse(value).refined()

    private fun count(value: Long): QueryDiscoveryCountDocument = QueryDiscoveryCountDocument.parse(value).refined()

    private fun nanos(value: Long): QueryDiscoveryNanosecondsDocument =
        QueryDiscoveryNanosecondsDocument.parse(value).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
