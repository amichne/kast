package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactSiteOutcomeDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ImpactSiteAccountingPresentationTest {
    @Test
    fun `requested sites retain both original route links and unknown relationship without provider replay`() =
        runTest {
            val f = ImpactFindingFixture(withRequestedSites = true)
            var calls = 0
            val protocol =
                CanonicalQueryProtocol(
                    QueryOperations {
                        calls++
                        error("Unexpected semantic provider")
                    },
                    f.symbols.references,
                    f.store,
                )
            val page = f.readWitness(protocol, ImpactWitnessSectionDocument.SITE_ACCOUNTING).evidence.payload
            val expanded =
                protocol
                    .execute(QueryRunRequest.ReadResult.valuePaths(f.reference), f.symbols.authority, f.budget)
                    .payload()
            val accounting = page.siteAccounting()
            val reached = accounting[0].outcome as ImpactSiteOutcomeDocument.Reached
            assertEquals(listOf(0L, 1L), reached.paths.values.map { it.pathOrdinal.value })
            assertEquals(expanded.items.values.map { it.rowId }, reached.paths.values.map { it.pathRowId })
            assertEquals(0, reached.exclusions.values.size)
            assertEquals(ImpactSiteOutcomeDocument.RelationshipUnproven, accounting[1].outcome)
            assertEquals(
                f.request,
                (f.store.restoreResult(f.reference, f.symbols.authority) as QueryResultRestoration.Restored).request,
            )
            assertEquals(
                f.requestedSites.map { it.site.impactDocument().value() },
                (page.impactAccounting as ImpactAccountingDocument.Investigated).requestedSites.values,
            )
            assertEquals(listOf(1L, 1L), accounting.map { it.admission.examinedWorkUnits.value })
            assertEquals(0, calls)
            assertEquals(Refinement.Refined(Unit), page.validateImpactAccounting())
        }

    @Test
    fun `site cursor pages preserve original ordinal identity native receipts and encoded finite outcome`() = runTest {
        val f = ImpactFindingFixture(withRequestedSites = true)
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic provider") },
                f.symbols.references,
                f.store,
            )
        val whole = f.readWitness(protocol, ImpactWitnessSectionDocument.SITE_ACCOUNTING).evidence.payload
        val first = whole.presentationPrefix(1).value()
        val next =
            f.readWitness(protocol, ImpactWitnessSectionDocument.SITE_ACCOUNTING, first.nextCursor!!.value)
                .evidence
                .payload
        assertEquals(whole.items.values, first.items.values + next.items.values)
        assertEquals(listOf(0L, 1L), whole.siteAccounting().map { it.requestedSiteOrdinal.value })
        assertEquals(whole.question, next.question)
        assertEquals(Refinement.Refined(Unit), next.validateImpactAccounting())
        val encoded =
            Json.encodeToJsonElement(ImpactSiteAccountingDocument.serializer(), next.siteAccounting().single())
                .jsonObject
        assertEquals(setOf("requestedSiteOrdinal", "site", "outcome", "admission"), encoded.keys)
        assertEquals(setOf("type"), encoded.getValue("outcome").jsonObject.keys)
        assertEquals(setOf("budget", "examinedWorkUnits"), encoded.getValue("admission").jsonObject.keys)
        assertInstanceOf(
            ImpactSiteOutcomeDocument.RelationshipUnproven::class.java,
            next.siteAccounting().single().outcome,
        )
    }
}

private fun QueryRunResult.siteAccounting(): List<ImpactSiteAccountingDocument> =
    items.values.map {
        ((it as QueryResultItemDocument.ImpactWitness).item.witness as ImpactWitnessDocument.SiteAccounting).accounting
    }

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value

private fun OperationOutcome<QueryRunResult, *, *>.payload(): QueryRunResult =
    when (this) {
        is OperationOutcome.Complete -> evidence.payload
        is OperationOutcome.Qualified -> evidence.payload
        is OperationOutcome.Rejected -> error("Expected retained presentation: $reason")
    }
