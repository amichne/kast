package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRowReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalRequestDtoSerializationTest {
    @Test
    fun `query resume and result read serialize without a repeated plan`() {
        val token =
            (QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000001")
                    as Refinement.Refined)
                .value
        val resume = strictJson.encodeToString(QueryRunRequest.serializer(), QueryRunRequest.Resume(token))
        val resumeShape = strictJson.parseToJsonElement(resume).jsonObject
        assertEquals(setOf("action", "continuation"), resumeShape.keys)
        assertEquals("resume", resumeShape.getValue("action").jsonPrimitive.content)
        assertEquals(token.value, resumeShape.getValue("continuation").jsonPrimitive.content)
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(QueryRunRequest.serializer(), resume.replace("\"resume\"", "\"obsolete\""))
        }
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(QueryRunRequest.serializer(), resume.dropLast(1) + ",\"from\":{}}")
        }

        val result =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
        val fields =
            (BoundedProtocolList.create(emptyList<io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument>())
                    as Refinement.Refined)
                .value
        val read =
            strictJson.encodeToString(
                QueryRunRequest.serializer(),
                QueryRunRequest.ReadResult(result, output = QueryOutputDocument.Symbols(fields)),
            )
        val readShape = strictJson.parseToJsonElement(read).jsonObject
        assertEquals(setOf("action", "result", "cursor", "output"), readShape.keys)
        assertEquals("read-result", readShape.getValue("action").jsonPrimitive.content)
        assertEquals(result.value, readShape.getValue("result").jsonPrimitive.content)
        assertEquals("0", readShape.getValue("cursor").jsonPrimitive.content)
        assertEquals(setOf("fields"), readShape.getValue("output").jsonObject.keys)
        assertTrue(readShape.getValue("output").jsonObject.getValue("fields").jsonArray.isEmpty())
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(QueryRunRequest.serializer(), read.replace("\"cursor\":0", "\"cursor\":-1"))
        }
    }

    @Test
    fun `query composition encodes typed inputs and rejects retired append syntax`() {
        val result =
            (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined).value
        val row =
            (QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-000000000002") as Refinement.Refined)
                .value
        val selected = QueryFromDocument.Result(result, bounded(listOf(row)))
        val references =
            QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(text("exact:v2:opaque")))))
        val request =
            canonicalQueryRequest()
                .copy(
                    steps =
                        bounded(
                            listOf(
                                QueryStepDocument.Concat(references),
                                QueryStepDocument.Concat(selected),
                                QueryStepDocument.Intersect(selected),
                                QueryStepDocument.Union(selected),
                                QueryStepDocument.Difference(selected),
                            )
                        )
                )
        val encoded = strictJson.encodeToString(QueryRunRequest.serializer(), request)
        val steps = strictJson.parseToJsonElement(encoded).jsonObject.getValue("steps").jsonArray
        assertEquals(
            listOf("concat", "concat", "intersect", "union", "difference"),
            steps.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            "references",
            steps[0].jsonObject.getValue("input").jsonObject.getValue("type").jsonPrimitive.content,
        )
        val selectedInput = steps[1].jsonObject.getValue("input").jsonObject
        assertEquals("result", selectedInput.getValue("type").jsonPrimitive.content)
        assertEquals(result.value, selectedInput.getValue("reference").jsonPrimitive.content)
        assertEquals(row.value, selectedInput.getValue("row_ids").jsonArray.single().jsonPrimitive.content)
        assertEquals(request, strictJson.decodeFromString(QueryRunRequest.serializer(), encoded))
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                QueryRunRequest.serializer(),
                encoded.replaceFirst("\"type\":\"concat\"", "\"type\":\"append-references\""),
            )
        }
    }

    @Test
    fun `all canonical requests own their wire serializer`() {
        val serializers = canonicalRequestSerializers()

        assertEquals(12, serializers.size)
        assertFalse(serializers.any { "WireDocument" in it.descriptor.serialName })
    }

    @Test
    fun `request deserialization rejects constrained primitives at parse time`() {
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                DiagnosticCheckRequest.serializer(),
                """{"path":"   ","limit":1}""",
            )
        }
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                DiagnosticCheckRequest.serializer(),
                """{"path":"src/main","limit":1001}""",
            )
        }
    }

    @Test
    fun `request deserialization rejects non-canonical aggregate syntax at parse time`() {
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                SourceReadRequest.serializer(),
                """
                    {
                      "anchor":{"type":"candidate","selector":"${selector("candidate")}"},
                      "region":{"type":"anchor"},
                      "entities":{"type":"matching","containment":"direct","filters":[]},
                      "text":{"type":"none"},
                      "entityLimit":1,
                      "textByteLimit":1,
                      "page":{"type":"first"}
                    }
                """
                    .trimIndent(),
            )
        }
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                QueryRunRequest.serializer(),
                strictJson
                    .encodeToString(QueryRunRequest.serializer(), canonicalQueryRequest())
                    .replace("\"sourceSets\":[\"main\"]", "\"sourceSets\":[\"main\",\"main\"]"),
            )
        }
    }

    @Test
    fun `serializer descriptors retain the constraints enforced during parsing`() {
        val textConstraint =
            ProtocolText.serializer().descriptor.annotations.filterIsInstance<ProtocolStringConstraint>().single()
        val countConstraint =
            ProtocolCount.serializer().descriptor.annotations.filterIsInstance<ProtocolIntegerConstraint>().single()
        val collectionConstraint =
            QueryRunRequest.Run.serializer()
                .descriptor
                .getElementDescriptor(1)
                .annotations
                .filterIsInstance<ProtocolCollectionConstraint>()
                .single()

        assertEquals(1, textConstraint.minimumLength)
        assertEquals(1_048_576, textConstraint.maximumLength)
        assertTrue(textConstraint.pattern.isNotBlank())
        assertEquals(1, countConstraint.minimum)
        assertEquals(1_000, countConstraint.maximum)
        assertEquals(1_000, collectionConstraint.maximumItems)
    }

    private fun canonicalRequestSerializers(): List<KSerializer<*>> =
        listOf(
            IndexSyncRequest.serializer(),
            TopologyBuildRequest.serializer(),
            SymbolDiscoverRequest.serializer(),
            SymbolInspectRequest.serializer(),
            SourceReadRequest.serializer(),
            RelationReadRequest.serializer(),
            TraversalRunRequest.serializer(),
            QueryRunRequest.serializer(),
            DiagnosticCheckRequest.serializer(),
            ChangePlanRequest.serializer(),
            ChangeApplyRequest.serializer(),
            ChangeRecoverRequest.serializer(),
        )

    private fun canonicalQueryRequest(): QueryRunRequest.Run {
        val main = (ProtocolText.parse("main") as Refinement.Refined).value
        val sourceSets = (BoundedProtocolList.create(listOf(main)) as Refinement.Refined).value
        val kinds = (BoundedProtocolList.create(listOf(QueryDeclarationKindDocument.CLASS)) as Refinement.Refined).value
        val steps =
            (BoundedProtocolList.create(emptyList<io.github.amichne.kast.protocol.contract.QueryStepDocument>())
                    as Refinement.Refined)
                .value
        val fields =
            (BoundedProtocolList.create(emptyList<io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument>())
                    as Refinement.Refined)
                .value
        return QueryRunRequest.Run(
            QueryFromDocument.Symbols(
                QueryDiscoveryDocument(QueryMatchDocument.All, QueryScopeDocument(sourceSets, null, null), kinds)
            ),
            steps,
            QueryOutputDocument.Symbols(fields),
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
        )
    }

    private fun <Value> bounded(values: List<Value>): BoundedProtocolList<Value> =
        (BoundedProtocolList.create(values) as Refinement.Refined).value

    private fun text(raw: String): ProtocolText = (ProtocolText.parse(raw) as Refinement.Refined).value

    private fun selector(family: String): String {
        val payload = "{}".encodeToByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest =
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return "$family:v2:$encoded:$digest"
    }

    private companion object {
        val strictJson = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }
    }
}
