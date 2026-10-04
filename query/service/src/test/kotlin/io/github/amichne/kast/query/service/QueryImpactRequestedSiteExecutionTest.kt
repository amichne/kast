package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryImpactRequestedSite
import io.github.amichne.kast.query.contract.QueryImpactRequiredObligation
import io.github.amichne.kast.query.contract.QueryImpactSiteOutcome
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.query.contract.QueryValuePathAccounting
import io.github.amichne.kast.query.contract.QueryValuePathAccountingStatus
import io.github.amichne.kast.query.contract.accountingStatus
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Detached native evidence exercises the existing interpreter; this is not installed role qualification. */
class QueryImpactRequestedSiteExecutionTest {
    @Test
    fun `requested unmatched site qualifies two original compiler routes without target flow enumeration`() = runTest {
        val s = Scenario()
        val requested = listOf(s.requested(s.target), s.requested(s.unmatched))
        val result =
            assertInstanceOf(
                QueryExecutionResult.Qualified::class.java,
                s.f.service(s.native.port).run(s.f.request(plan = s.f.plan(requestedSites = requested))),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        val ledger = (rows.accounting as QueryValuePathAccounting.Investigated).ledger
        assertEquals(2, rows.values.size)
        assertEquals(
            listOf(0, 1),
            (ledger.siteAccounting[0].outcome as QueryImpactSiteOutcome.Reached).pathOrdinals.map { it.value },
        )
        assertEquals(QueryImpactSiteOutcome.RelationshipUnproven, ledger.siteAccounting[1].outcome)
        assertEquals(
            setOf(QueryImpactRequiredObligation.REQUESTED_SITE_RELATIONSHIP),
            (rows.accountingStatus as QueryValuePathAccountingStatus.Unresolved).required,
        )
        assertEquals(s.expectedReads, s.native.examined)
        s.native.assertConsumed()
    }

    @Test
    fun `every requested site reached permits conserved completion without adding original routes`() = runTest {
        val s = Scenario()
        val result =
            assertInstanceOf(
                QueryExecutionResult.Complete::class.java,
                s.f
                    .service(s.native.port)
                    .run(s.f.request(plan = s.f.plan(requestedSites = listOf(s.requested(s.target))))),
            )
        val rows = result.result.rows as QueryRows.ValuePaths
        assertEquals(2, rows.values.size)
        assertEquals(QueryValuePathAccountingStatus.Conserved, rows.accountingStatus)
        assertEquals(s.expectedReads, s.native.examined)
        s.native.assertConsumed()
    }

    private class Scenario {
        val f = QueryImpactExecutionFixture()
        private val b = f.site(30, ValueRole.LocalBinding)
        private val c = f.site(40, ValueRole.LocalBinding)
        private val call = f.call("display", 50, 60)
        val target = f.argument(call)
        val unmatched = f.site(70, ValueRole.LocalRead)
        val expectedReads = listOf(f.producer.site, b, target, c)
        private val edges =
            listOf(
                f.edge(f.producer.site, b, ValueTransferKind.LOCAL_BINDING),
                f.edge(f.producer.site, c, ValueTransferKind.LOCAL_BINDING),
                f.edge(b, target, ValueTransferKind.ARGUMENT),
                f.edge(c, target, ValueTransferKind.ARGUMENT),
            )
        val native =
            f.script(
                listOf(
                    ImpactReadExpectation(f.producer.site, edges.take(2)),
                    ImpactReadExpectation(b, listOf(edges[2])),
                    ImpactReadExpectation(target),
                    ImpactReadExpectation(c, listOf(edges[3])),
                )
            )

        fun requested(site: ValueSite): QueryImpactRequestedSite {
            val owner = SymbolSelector.issue(f.lease, f.scope, (f.owner as RelationEndpoint.Resolved).evidence)
            val role =
                if (site == target)
                    ValueSiteRoleClaim.Argument(
                        call.range,
                        SymbolSelector.issue(f.lease, f.scope, (call.callable as RelationEndpoint.Resolved).evidence),
                        (target.role as ValueRole.Argument).position,
                    )
                else ValueSiteRoleClaim.LocalRead
            val request =
                ValueSiteRevalidationRequest.create(
                        owner,
                        site.range,
                        role,
                        RelationBudget(f.request().budget.resources, RelationByteLimit.parse(100000).value()),
                    )
                    .value()
            return QueryImpactRequestedSite.admit(
                    request,
                    RevalidatedValueSite.fromCompiler(request, site).value(),
                    RelationWorkCount.parse(1).value(),
                )
                .value()
        }
    }
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
