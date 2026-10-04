package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.query.contract.QueryImpactLedger
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactReadRejection
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.query.contract.QueryImpactWitnessSection
import io.github.amichne.kast.query.contract.QueryImpactWitnessView
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowWorkReceipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactRejectedReadReceiptTest {
    @Test
    fun `rejected read witness encodes its actual grant with and without a suspended prefix`() {
        val f = ImpactWitnessPresentationFixture(resumedBinding = true)
        for (preservePrefix in listOf(false, true)) {
            val ledger = rejectedLedger(f, preservePrefix)
            val view = QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.READ_REJECTIONS, 0, 1).value()
            val projection = QueryRows.ImpactWitness.of(view).projectWitnessItems() as QueryProjection.Projected
            val witness = (projection.values.single() as QueryResultItemDocument.ImpactWitness).item.witness
            val encoded = Json.encodeToJsonElement(ImpactWitnessDocument.serializer(), witness).jsonObject
            assertEquals(setOf("type", "rejection", "receipts"), encoded.keys)
            val actual = encoded.getValue("receipts").jsonArray.single().jsonObject
            assertEquals(setOf("domain", "examinedWorkUnits", "returnedResults", "returnedBytes"), actual.keys)
            assertEquals("1", actual.getValue("examinedWorkUnits").jsonPrimitive.content)
            assertEquals("0", actual.getValue("returnedResults").jsonPrimitive.content)
            assertEquals("0", actual.getValue("returnedBytes").jsonPrimitive.content)
            val grant = actual.getValue("domain").jsonObject.getValue("budget").jsonObject
            assertEquals("100", grant.getValue("maxWorkUnits").jsonPrimitive.content)
            assertEquals("10", grant.getValue("maxResults").jsonPrimitive.content)
            assertEquals("100000", grant.getValue("maxReturnedBytes").jsonPrimitive.content)
        }
    }

    private fun rejectedLedger(f: ImpactWitnessPresentationFixture, preservePrefix: Boolean): QueryImpactLedger {
        val source = f.ledger.observations.last().source
        val native = f.ledger.observations.first().domain
        val suspended =
            ValueFlowStep.fromCompiler(
                    source,
                    emptyList(),
                    emptyList(),
                    ValueFlowTerminal.ResourceSuspended,
                    native,
                    RelationWorkCount.parse(3).value(),
                )
                .value()
        val work = RelationWorkCount.parse(1).value()
        val rejection =
            QueryImpactReadRejection.Native(source, native.boundary, ValueFlowRejection.AUTHORITY_MOVED, work)
        val receipt = ValueFlowWorkReceipt.rejected(ValueFlowRequest(source, native.budget, native.boundary), work)
        val original = f.ledger.paths.single()
        val path =
            QueryImpactPath.fromEvidence(
                    original.producer,
                    original.steps,
                    original.representation,
                    QueryImpactTerminal.Unresolved.ReadRejected(rejection),
                )
                .value()
        val observed = if (preservePrefix) listOf(suspended) else emptyList()
        return QueryImpactLedger.fromEvidence(
                f.ledger.seeds,
                native.boundary,
                f.ledger.semantics,
                emptyList(),
                emptyList(),
                listOf(f.ledger.observations.first()) + observed,
                listOf(path),
                listOf(rejection),
                listOf(f.producer),
                readReceipts =
                    mapOf(
                        original.producer to f.ledger.observations.first().receipts,
                        source to observed.flatMap { it.receipts } + receipt,
                    ),
            )
            .value()
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
