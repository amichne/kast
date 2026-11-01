package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.symbol.contract.SymbolDescription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuerySymbolFieldProjectionTest {
    @Test
    fun `default empty and selected fields preserve encoded identity and original proof`() {
        val fixture = RelationPagingFixture.published()
        val cases =
            listOf(
                FieldCase(
                    listOf(QuerySymbolFieldDocument.NAME, QuerySymbolFieldDocument.LOCATION),
                    ExpectedFieldRow(fixture.exact.value, name = "subject", location = ExpectedFieldLocation()),
                ),
                FieldCase(emptyList(), ExpectedFieldRow(fixture.exact.value)),
                FieldCase(
                    listOf(QuerySymbolFieldDocument.SIGNATURE),
                    ExpectedFieldRow(fixture.exact.value, signature = ExpectedFieldSignature()),
                ),
            )
        for (case in cases) {
            val evidence = projectedEvidence(fixture, case.fields)
            val complete =
                CanonicalQueryCliDocuments.project(OperationOutcome.Complete(evidence))
                    as ProjectedOperationOutcome.Complete
            val qualified =
                CanonicalQueryCliDocuments.project(OperationOutcome.Qualified(evidence, subsetQualification()))
                    as ProjectedOperationOutcome.Qualified
            for (detail in ToolOutputDetail.entries) {
                val completeJson = Json.parseToJsonElement(complete.document.present(detail).value).jsonObject
                val qualifiedJson = Json.parseToJsonElement(qualified.document.present(detail).value).jsonObject
                assertRowAndCompletion(completeJson, case.expected)
                assertRowAndCompletion(qualifiedJson, case.expected)
                assertEquals("complete", completeJson.getValue("status").jsonPrimitive.content)
                assertEquals(
                    true,
                    completeJson.getValue("coverage").jsonObject.getValue("exhaustive").jsonPrimitive.boolean,
                )
                assertEquals("qualified", qualifiedJson.getValue("status").jsonPrimitive.content)
                assertEquals(
                    false,
                    qualifiedJson.getValue("coverage").jsonObject.getValue("exhaustive").jsonPrimitive.boolean,
                )
                assertQualification(qualifiedJson)
            }
        }
    }

    private fun projectedEvidence(
        fixture: RelationPagingFixture,
        fields: List<QuerySymbolFieldDocument>,
    ): EvidenceEnvelope<QueryRunResult> {
        val output = QueryOutputDocument.Symbols(bounded(fields))
        val symbol = QuerySymbol(SymbolDescription.from(fixture.selector), emptyList())
        val items =
            QueryItemProjector(fixture.references).projectItems(output, QueryRows.Symbols.of(listOf(symbol)))
                as QueryProjection.Projected
        val result =
            QueryRunResult(
                QueryQuestionDocument(
                    QueryFromDocument.Location(text("Subject.kt"), ProtocolOffset.parse(0).refined()),
                    bounded(emptyList()),
                    output,
                ),
                bounded(items.values),
                bounded(emptyList()),
            )
        return EvidenceEnvelope(
            CanonicalOperation.QUERY_RUN.id,
            EvidenceBasis.Published(EvidenceGeneration.parse(7).refined()),
            result,
        )
    }

    private fun subsetQualification(): QueryRunQualification =
        QueryRunQualification.create(
                QueryKnownMinimum.parse(1).refined(),
                listOf(QueryLimitationDocument.ROW_SELECTION_INCOMPLETE),
                QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
            )
            .refined()

    private fun assertQualification(document: JsonObject) {
        val qualification = document.getValue("qualification").jsonObject
        assertEquals(setOf("knownMinimum", "limitations", "progress"), qualification.keys)
        assertEquals("1", qualification.getValue("knownMinimum").jsonPrimitive.content)
        assertEquals(
            listOf("row-selection-incomplete"),
            qualification.getValue("limitations").jsonArray.map { it.jsonPrimitive.content },
        )
        val progress = qualification.getValue("progress").jsonObject
        assertEquals(setOf("type", "reason"), progress.keys)
        assertEquals("terminal_incomplete", progress.getValue("type").jsonPrimitive.content)
        assertEquals("upstream-incomplete", progress.getValue("reason").jsonPrimitive.content)
    }

    private fun assertRowAndCompletion(document: JsonObject, expected: ExpectedFieldRow) {
        val row = document.getValue("items").jsonArray.single().jsonObject
        val json = Json { encodeDefaults = true }
        assertEquals(json.encodeToJsonElement(ExpectedFieldRow.serializer(), expected), row)
        assertEquals(setOf("type", "ref", "kind", "name", "location", "signature", "connections"), row.keys)
        val completion = document.getValue("question").jsonObject.getValue("completion").jsonObject
        assertEquals(setOf("type", "model"), completion.keys)
        assertEquals("COMPLETE_ONLY", completion.getValue("type").jsonPrimitive.content)
        assertEquals("COMPILER_RESOLVED_STATIC_V1", completion.getValue("model").jsonPrimitive.content)
    }

    private data class FieldCase(val fields: List<QuerySymbolFieldDocument>, val expected: ExpectedFieldRow)

    private fun text(value: String) = ProtocolText.parse(value).refined()

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid independent field fixture: $failure")
        }
}

@Serializable
private data class ExpectedFieldRow(
    val ref: String,
    val type: String = "exact-symbol",
    val kind: String = "function",
    val name: String? = null,
    val location: ExpectedFieldLocation? = null,
    val signature: ExpectedFieldSignature? = null,
    val connections: List<String> = emptyList(),
)

@Serializable
private data class ExpectedFieldLocation(
    val file: String = "/workspace/Subject.kt",
    val range: ExpectedFieldRange = ExpectedFieldRange(),
)

@Serializable private data class ExpectedFieldRange(val startInclusive: Int = 0, val endExclusive: Int = 6)

@Serializable
private data class ExpectedFieldSignature(
    val type: String = "function",
    val qualifiedIdentity: String = "sample.subject",
    val receiver: ExpectedFieldReceiver = ExpectedFieldReceiver(),
    val contextReceivers: List<String> = emptyList(),
    val valueParameters: List<String> = emptyList(),
    val typeParameterCount: Int = 0,
)

@Serializable private data class ExpectedFieldReceiver(val type: String = "absent")
