package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetainedGraphDeliveryTest {
    @Test
    fun `compact retained graph reconstructs 53 exact occurrences and repeated witnesses once`() = runTest {
        val fixture = RetainedGraphDeliveryFixture()
        val issued = fixture.issue()
        val outcome =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(issued.reference),
                fixture.source.authority,
                fixture.budget,
            ) as OperationOutcome.Complete
        val document = (CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete).document
        val full = Json.parseToJsonElement(document.present(ToolOutputDetail.VERBOSE).value).jsonObject
        val compactText = document.present(ToolOutputDetail.COMPACT).value
        val compact = Json.parseToJsonElement(compactText).jsonObject
        println(
            "D3 baseline full_bytes=${utf8(full)} compact_bytes=${utf8(compact)} " +
                "rows=53 witnesses=53 semantic_calls=${fixture.semanticCalls}"
        )
        assertEquals(53, full.getValue("items").jsonArray.size)
        assertEquals(53, full.getValue("reference_observations").jsonArray.size)
        assertEquals(0, fixture.semanticCalls)
        val graph = compact["relation_graph"]
        assertNotNull(
            graph,
            "Compact retained relation rows need a result-scoped graph instead of repeated full witnesses",
        )
        val table = graph!!.jsonObject
        assertEquals(issued.reference.value, table.getValue("basis").jsonPrimitive.content)
        assertCompactTable(compact, table)
        assertNodeProofs(fixture, table)
        assertRowIds(issued, compact)
        assertTrue(utf8(compact) < utf8(full), "compact bytes must decrease for this frozen fixture")
        assertReconstructionControls(full, compact)
        val observations = table.getValue("reference_observations").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            compact.getValue("items").jsonArray.map { it.jsonObject.getValue("witness").jsonPrimitive.content },
            observations,
        )
        val repeat =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(issued.reference),
                fixture.source.authority,
                fixture.budget,
            )
        val repeatedDocument =
            (CanonicalQueryCliDocuments.project(repeat) as ProjectedOperationOutcome.Complete).document
        assertEquals(compactText, repeatedDocument.present(ToolOutputDetail.COMPACT).value)
        assertEquals(0, fixture.semanticCalls)
    }

    private fun assertRowIds(issued: QueryResultIssuance.Issued, compact: JsonObject) {
        assertEquals(
            issued.rowIds.map { it.value },
            compact.getValue("items").jsonArray.map { it.jsonObject.getValue("row_id").jsonPrimitive.content },
        )
    }

    private fun assertCompactTable(compact: JsonObject, table: JsonObject) {
        assertEquals(3, table.getValue("nodes").jsonArray.size, "Same-name owners keep distinct exact identities")
        assertEquals(53, table.getValue("witnesses").jsonArray.size)
        assertEquals(53, table.getValue("reference_observations").jsonArray.size)
        assertEquals(53, compact.getValue("items").jsonArray.size)
    }

    private fun assertNodeProofs(fixture: RetainedGraphDeliveryFixture, table: JsonObject) {
        val expected =
            fixture.occurrences
                .flatMap { occurrence ->
                    val owner =
                        occurrence.ownership
                            as io.github.amichne.kast.relation.contract.RelationReferenceOwnership.DeclarationOwned
                    listOf(occurrence.target.compilerIdentity.value, owner.declaration.compilerIdentity.value)
                }
                .toSet()
        val symbols = table.getValue("nodes").jsonArray.map { it.jsonObject.getValue("symbol").jsonObject }
        assertEquals(
            expected,
            symbols.map { it.getValue("compiler_identity").jsonPrimitive.content }.toSet(),
        )
        symbols.forEach { assertEquals("/workspace/Subject.kt", it.getValue("file").jsonPrimitive.content) }
    }

    private fun assertReconstructionControls(full: JsonObject, compact: JsonObject) {
        val reconstructed = reconstruct(compact)
        assertGraph(reconstructed)
        assertThrows(AssertionError::class.java) { assertGraph(reconstructed.dropLast(1)) }
        assertThrows(AssertionError::class.java) { assertGraph(reconstructed + reconstructed.last()) }
        assertThrows(AssertionError::class.java) {
            assertGraph(reconstructed.map { it.copy(direction = "TARGET_TO_DECLARATION") })
        }
        assertThrows(AssertionError::class.java) { assertGraph(reconstructed.map { it.copy(coverage = "unproven") }) }
        assertEquals(full.getValue("coverage"), compact.getValue("coverage"))
        assertEquals(full.getValue("page_progress"), compact.getValue("page_progress"))
        assertEquals(full.getValue("question"), compact.getValue("question"))
        assertEquals(full.getValue("retention"), compact.getValue("retention"))
    }

    @Test
    fun `compact delivery preserves qualified coverage and the original finite blocker`() = runTest {
        val fixture = RetainedGraphDeliveryFixture()
        val issued = fixture.issue(qualified = true)
        val page =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(issued.reference),
                fixture.source.authority,
                fixture.budget,
            )
        val document = (CanonicalQueryCliDocuments.project(page) as ProjectedOperationOutcome.Qualified).document
        val full = Json.parseToJsonElement(document.present(ToolOutputDetail.VERBOSE).value).jsonObject
        val compact = Json.parseToJsonElement(document.present(ToolOutputDetail.COMPACT).value).jsonObject
        assertGraph(reconstruct(compact))
        assertEquals(full.getValue("coverage"), compact.getValue("coverage"))
        assertEquals(full.getValue("qualification"), compact.getValue("qualification"))
        assertEquals("false", compact.getValue("coverage").jsonObject.getValue("exhaustive").jsonPrimitive.content)
        assertEquals(0, fixture.semanticCalls)
    }

    @Test
    fun `expired reference rejects before presentation and an exhausted row cursor preserves evidence`() = runTest {
        val fixture = RetainedGraphDeliveryFixture()
        val issued = fixture.issue()
        val page =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(
                    issued.reference,
                    QueryResultCursor.parse(53).graphFixtureValue(),
                ),
                fixture.source.authority,
                fixture.budget,
            )
        val document = (CanonicalQueryCliDocuments.project(page) as ProjectedOperationOutcome.Complete).document
        val compact = Json.parseToJsonElement(document.present(ToolOutputDetail.COMPACT).value).jsonObject
        assertEquals(0, compact.getValue("items").jsonArray.size)
        assertEquals(53, compact.getValue("reference_observations").jsonArray.size)
        fixture.now = 101_000_000L
        val rejected =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(issued.reference),
                fixture.source.authority,
                fixture.budget,
            ) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE),
            rejected.reason,
        )
        assertEquals(0, fixture.semanticCalls)
    }

    @Test
    fun `stale and unissued references cannot gain compact evidence authority`() = runTest {
        val fixture = RetainedGraphDeliveryFixture()
        val issued = fixture.issue()
        val staleAuthority =
            SemanticReadLease(fixture.source.authority.workspaceRoot, EvidenceGeneration.parse(8).graphFixtureValue())
        val stale =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(issued.reference),
                staleAuthority,
                fixture.budget,
            ) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_STALE_BASIS),
            stale.reason,
        )
        val unissued = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000000").graphFixtureValue()
        val rejected =
            fixture.protocol.executePage(
                QueryRunRequest.ReadResult.occurrences(unissued),
                fixture.source.authority,
                fixture.budget,
            ) as OperationOutcome.Rejected
        assertEquals(
            QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_UNAVAILABLE),
            rejected.reason,
        )
        assertEquals(0, fixture.semanticCalls)
    }

    private fun reconstruct(document: JsonObject): List<GraphEdge> {
        val graph = document.getValue("relation_graph").jsonObject
        val nodes =
            graph.getValue("nodes").jsonArray.associate { entry ->
                val node = entry.jsonObject
                node.getValue("id").jsonPrimitive.content to node.getValue("symbol").jsonObject
            }
        val witnesses =
            graph.getValue("witnesses").jsonArray.associate { entry ->
                val witness = entry.jsonObject
                witness.getValue("id").jsonPrimitive.content to witness
            }
        return document.getValue("items").jsonArray.map { item ->
            val witness = witnesses.getValue(item.jsonObject.getValue("witness").jsonPrimitive.content)
            val target = nodes.getValue(witness.getValue("target").jsonPrimitive.content)
            val owner =
                nodes.getValue(witness.getValue("ownership").jsonObject.getValue("declaration").jsonPrimitive.content)
            val occurrence = witness.getValue("occurrence").jsonObject
            val range = occurrence.getValue("range").jsonObject
            GraphEdge(
                target = target.getValue("qualifiedIdentity").jsonPrimitive.content,
                source = owner.getValue("qualifiedIdentity").jsonPrimitive.content,
                kind = witness.getValue("meaning").jsonPrimitive.content,
                direction = witness.getValue("direction").jsonPrimitive.content,
                start = range.getValue("startInclusive").jsonPrimitive.content.toInt(),
                end = range.getValue("endExclusive").jsonPrimitive.content.toInt(),
                context = witness.getValue("context").jsonPrimitive.content,
                provenance = witness.getValue("provenance").jsonPrimitive.content,
                coverage = witness.getValue("coverage").jsonPrimitive.content,
            )
        }
    }

    private fun assertGraph(actual: List<GraphEdge>) {
        val expected =
            (0 until 53).map { index ->
                GraphEdge(
                    target = "sample.subject",
                    source = "fixture.owner${index % 2}",
                    kind = "references",
                    direction = "DECLARATION_TO_TARGET",
                    start = 100 + index * 16,
                    end = 108 + index * 16,
                    context = "CODE",
                    provenance = "k2-authored-source",
                    coverage = "exact-compiler-confirmed",
                )
            }
        assertEquals(expected, actual)
    }

    private fun utf8(value: JsonElement): Int = value.toString().toByteArray(Charsets.UTF_8).size
}

private data class GraphEdge(
    val target: String,
    val source: String,
    val kind: String,
    val direction: String,
    val start: Int,
    val end: Int,
    val context: String,
    val provenance: String,
    val coverage: String,
)
