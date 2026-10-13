package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationProvenanceDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceContextDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOwnershipDocument
import io.github.amichne.kast.protocol.contract.ToolOutputDetail
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetainedSymbolGraphSharingTest {
    @Test
    fun `unequal reference proof keeps a direct connection witness`() = runTest {
        val (fixture, page) = page()
        val original = page.evidence.payload.referenceObservations.values.single()
        val controls =
            listOf(
                emptyList(),
                listOf(original.copy(ownership = RelationReferenceOwnershipDocument.DeclarationOwned(original.target))),
                listOf(
                    original.copy(
                        target = (original.ownership as RelationReferenceOwnershipDocument.DeclarationOwned).declaration
                    )
                ),
                listOf(original.copy(meaning = RelationKindDocument.CALLEES)),
                listOf(original.copy(provenance = RelationProvenanceDocument.K2_GENERATED_SOURCE)),
                listOf(
                    original.copy(occurrence = original.occurrence.copy(candidateSelector = original.target.selector))
                ),
                listOf(
                    original.copy(
                        ownership = RelationReferenceOwnershipDocument.FileScoped(RelationReferenceContextDocument.CODE)
                    )
                ),
            )
        for (references in controls) {
            val candidate = withReferences(page, references)
            val (full, compact) = documents(candidate)
            assertLosslessRows(full, compact)
            assertEquals("direct", connection(compact).getValue("type").jsonPrimitive.content)
        }
        assertEquals(0, fixture.retained.semanticCalls)
    }

    @Test
    fun `a reversed connection cannot borrow declaration-to-target proof`() = runTest {
        val (fixture, page) = page()
        val item = page.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol
        val fact = item.connections.values.single()
        val reversed =
            item.copy(connections = graphFixtureList(listOf(fact.copy(source = fact.target, target = fact.source))))
        val candidate =
            page.copy(
                evidence =
                    page.evidence.copy(payload = page.evidence.payload.copy(items = graphFixtureList(listOf(reversed))))
            )
        val (full, compact) = documents(candidate)
        assertLosslessRows(full, compact)
        assertEquals("direct", connection(compact).getValue("type").jsonPrimitive.content)
        assertEquals(0, fixture.retained.semanticCalls)
    }

    @Test
    fun `one-node one-connection graph fits the frozen single-record cap without semantic work`() = runTest {
        val (fixture, page) = page()
        val item = page.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol
        val fact = item.connections.values.single()
        val self = item.copy(connections = graphFixtureList(listOf(fact.copy(target = fact.source))))
        val reference = page.evidence.payload.referenceObservations.values.single().copy(target = fact.source)
        val candidate =
            withReferences(page, listOf(reference)).let {
                it.copy(
                    evidence =
                        it.evidence.copy(payload = it.evidence.payload.copy(items = graphFixtureList(listOf(self))))
                )
            }
        val (full, compact) = documents(candidate)
        val reconstruction = SymbolGraphReconstruction(compact)
        assertLosslessRows(full, compact)
        assertEquals(1, reconstruction.nodes.size)
        assertEquals(1, reconstruction.connections.size)
        assertEquals("reference-backed", connection(compact).getValue("type").jsonPrimitive.content)
        val restored = reconstruction.connection(connection(compact).getValue("id").jsonPrimitive.content)
        assertEquals(restored.source, restored.target)
        val encoded =
            (CanonicalOperationWireBindings.queryRun.encodeOutcome(candidate) as WireEncoding.Encoded).document
        val canonicalBytes = encoded.toByteArray(Charsets.UTF_8).size
        assertTrue(canonicalBytes <= 20_000)
        assertEquals(0, fixture.retained.semanticCalls)
        println(
            "D3 SYMBOLS one_node_one_connection canonical_bytes=$canonicalBytes " +
                "full_bytes=${full.toString().toByteArray(Charsets.UTF_8).size} " +
                "compact_bytes=${compact.toString().toByteArray(Charsets.UTF_8).size} " +
                "cap=20000 semantic_provider_calls=0 physical_rpcs=NOT_RUN"
        )
    }

    private fun connection(compact: JsonObject): JsonObject =
        compact.getValue("relation_graph").jsonObject.getValue("connection_witnesses").jsonArray.single().jsonObject

    private fun withReferences(
        page: OperationOutcome.Complete<QueryRunResult>,
        references: List<RelationReferenceOccurrenceDocument>,
    ): OperationOutcome.Complete<QueryRunResult> =
        page.copy(
            evidence =
                page.evidence.copy(
                    payload = page.evidence.payload.copy(referenceObservations = graphFixtureList(references))
                )
        )

    private fun documents(page: OperationOutcome.Complete<QueryRunResult>): Pair<JsonObject, JsonObject> {
        val document = (CanonicalQueryCliDocuments.project(page) as ProjectedOperationOutcome.Complete).document
        return Json.parseToJsonElement(document.present(ToolOutputDetail.VERBOSE).value).jsonObject to
            Json.parseToJsonElement(document.present(ToolOutputDetail.COMPACT).value).jsonObject
    }

    private suspend fun page(): Pair<RetainedSymbolGraphFixture, OperationOutcome.Complete<QueryRunResult>> {
        val fixture = RetainedSymbolGraphFixture()
        val issued = fixture.issue()
        val page =
            fixture.retained.protocol.executePage(
                QueryRunRequest.ReadResult.symbols(issued.reference, output = fixture.selectedOutput),
                fixture.retained.source.authority,
                fixture.retained.budget.copy(
                    resources =
                        fixture.retained.budget.resources.copy(resultLimit = ResultLimit.parse(1).graphFixtureValue())
                ),
            ) as OperationOutcome.Complete
        return fixture to page
    }
}
