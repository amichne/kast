package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.RevalidatedValueSite
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactSiteAccountingTest {
    @Test
    fun `requested native sites preserve both proven routes and an unmatched relationship obligation`() {
        val f = QueryImpactLedgerTest.Fixture()
        val reached = requested(f, f.destination)
        val unmatched = requested(f, ValueSite.fromCompiler(f.owner, range(60, 61), ValueRole.LocalRead).value())
        val ledger = ledger(f, listOf(reached, unmatched)).value()

        assertEquals(2, ledger.siteAccounting.size)
        val first = ledger.siteAccounting[0]
        assertSame(reached, first.requested)
        assertEquals(listOf(0, 1), (first.outcome as QueryImpactSiteOutcome.Reached).pathOrdinals.map { it.value })
        val second = ledger.siteAccounting[1]
        assertSame(unmatched, second.requested)
        assertEquals(QueryImpactSiteOutcome.RelationshipUnproven, second.outcome)
        assertEquals(listOf(0, 1), ledger.siteAccounting.map { it.requestedSiteOrdinal.value })
        assertEquals(f.paths, ledger.paths)
        assertEquals(
            setOf(QueryImpactRequiredObligation.REQUESTED_SITE_RELATIONSHIP),
            (ledger.closure as QueryImpactClosure.Unresolved).required,
        )
    }

    @Test
    fun `duplicate native targets fail closed and unmatched obligations forbid complete execution`() {
        val f = QueryImpactLedgerTest.Fixture()
        val site = requested(f, f.destination)
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.DUPLICATE_REQUESTED_SITE),
            ledger(f, listOf(site, site)),
        )
        val unknown = requested(f, ValueSite.fromCompiler(f.owner, range(60, 61), ValueRole.LocalRead).value())
        val accounting = ledger(f, listOf(unknown)).value()
        val result = QueryResult(QueryRows.ValuePaths.fromInvestigation(accounting).value(), emptyList())
        assertEquals(
            QueryExecutionResult.Rejected(QueryExecutionRejection.INTERNAL_CONTRACT_VIOLATION),
            QueryExecutionResult.Complete.create(result, QueryCoverage.Complete(QueryCount.parse(2).value())),
        )
        assertEquals(f.paths, accounting.paths)
        assertTrue(accounting.retainedStorageBytes() > ledger(f, emptyList()).value().retainedStorageBytes())
    }

    @Test
    fun `native selection receipt rejects substituted occurrence and excess work`() {
        val f = QueryImpactLedgerTest.Fixture()
        val proof = requested(f, f.destination)
        val changed =
            ValueSiteRevalidationRequest.create(
                    proof.request.enclosing,
                    range(60, 61),
                    ValueSiteRoleClaim.LocalRead,
                    f.domain.budget,
                )
                .value()
        assertEquals(
            Refinement.Rejected(
                QueryImpactRequestedSiteFailure.Proof(
                    io.github.amichne.kast.relation.contract.ValueSiteRevalidationFailure.ANCHOR_MISMATCH
                )
            ),
            QueryImpactRequestedSite.admit(changed, proof.proof, RelationWorkCount.parse(1).value()),
        )
        assertEquals(
            Refinement.Rejected(QueryImpactRequestedSiteFailure.WorkReceiptExceedsGrant),
            QueryImpactRequestedSite.admit(proof.request, proof.proof, RelationWorkCount.parse(101).value()),
        )
    }

    @Test
    fun `positive exits preserve both original path ordinals and mixed reached partition`() {
        val f = QueryImpactLedgerTest.Fixture()
        val domain = outsideDomain(f, "Other.kt")
        val target = requested(f, f.destination)
        val excludedPaths = f.paths.map { path -> excluded(path, f.destination, domain) }
        val allExited =
            QueryImpactSiteAccounting.fromRequestedSites(listOf(target), excludedPaths, domain).value().single()
        assertEquals(
            listOf(0, 1),
            (allExited.outcome as QueryImpactSiteOutcome.Excluded).exclusions.map { it.pathOrdinal.value },
        )
        val mixed =
            QueryImpactSiteAccounting.fromRequestedSites(listOf(target), listOf(f.paths[0], excludedPaths[1]), domain)
                .value()
                .single()
                .outcome as QueryImpactSiteOutcome.Reached
        assertEquals(listOf(0), mixed.pathOrdinals.map { it.value })
        assertEquals(listOf(1), mixed.exclusions.map { it.pathOrdinal.value })
        assertSame(
            (excludedPaths[1].terminal as QueryImpactTerminal.ExplicitScopeExclusion).exclusion,
            mixed.exclusions.single().exclusion,
        )
    }

    @Test
    fun `another domain or another exact native site cannot count as requested scope exclusion`() {
        val f = QueryImpactLedgerTest.Fixture()
        val domain = outsideDomain(f, "Other.kt")
        val other = outsideDomain(f, "Elsewhere.kt")
        val target = requested(f, f.destination)
        val wrongDomain = f.paths.map { excluded(it, f.destination, other) }
        assertEquals(
            QueryImpactSiteOutcome.RelationshipUnproven,
            QueryImpactSiteAccounting.fromRequestedSites(listOf(target), wrongDomain, domain).value().single().outcome,
        )
        val short =
            QueryImpactPath.fromEvidence(
                    f.producer,
                    f.paths[0].steps.take(1),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.ExplicitScopeExclusion(
                        QueryImpactScopeExclusion.admit(f.first, domain).value()
                    ),
                )
                .value()
        assertEquals(
            QueryImpactSiteOutcome.RelationshipUnproven,
            QueryImpactSiteAccounting.fromRequestedSites(listOf(target), listOf(short), domain)
                .value()
                .single()
                .outcome,
        )
    }

    private fun outsideDomain(f: QueryImpactLedgerTest.Fixture, filename: String) =
        RelationSearchBoundary.Explicit(
            SymbolSearchScope.ExactFile(
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                        f.lease.workspaceRoot,
                        Path.of("/fixture").resolve(filename),
                    )
                    .value(),
                f.scope.sourceKinds,
                f.scope.generatedSources,
            )
        )

    private fun excluded(path: QueryImpactPath, site: ValueSite, domain: RelationSearchBoundary.Explicit) =
        QueryImpactPath.fromEvidence(
                path.producer,
                path.steps,
                path.representation,
                QueryImpactTerminal.ExplicitScopeExclusion(QueryImpactScopeExclusion.admit(site, domain).value()),
            )
            .value()

    private fun requested(f: QueryImpactLedgerTest.Fixture, site: ValueSite): QueryImpactRequestedSite {
        val owner = SymbolSelector.issue(f.lease, f.scope, f.evidence)
        val request =
            ValueSiteRevalidationRequest.create(owner, site.range, ValueSiteRoleClaim.LocalRead, f.domain.budget)
                .value()
        return QueryImpactRequestedSite.admit(
                request,
                RevalidatedValueSite.fromCompiler(request, site).value(),
                RelationWorkCount.parse(1).value(),
            )
            .value()
    }

    private fun ledger(f: QueryImpactLedgerTest.Fixture, requested: List<QueryImpactRequestedSite>) =
        QueryImpactLedger.fromEvidence(
            listOf(f.producer),
            f.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            f.observations,
            f.paths,
            originalProducers = listOf(f.producerWitness),
            requestedSites = requested,
        )

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
