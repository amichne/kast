package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryKnownDeclarationsConservationTest {
    @Test
    fun `source admission keeps fresh wrappers and admits only the same full known question`() {
        val f = KnownLedgerCase()
        val source =
            QueryImpactSource.admit(
                    listOf(f.base.producerWitness),
                    emptyList(),
                    emptyList(),
                    f.base.domain.boundary,
                    knownDeclarations = f.known,
                )
                .knownValue()
        assertSame(f.known, source.knownDeclarations)
        val step = f.observations(f.known).first()
        assertSame(step, source.admitObservation(step).knownValue())
        assertEquals(
            Refinement.Rejected(ValueFlowStepFailure.DOMAIN_MISMATCH),
            source.admitObservation(f.observations(f.alternative()).first()),
        )
    }

    @Test
    fun `ledger conserves full known inputs and rejects same identity with changed source scope`() {
        val f = KnownLedgerCase()
        val observed = f.observations(f.known)
        val ledger = f.ledger(observed).knownValue()
        assertSame(f.known, ledger.knownDeclarations)
        assertEquals(f.known.declarations, ledger.knownDeclarations.declarations)
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.DOMAIN_MISMATCH),
            f.ledger(f.observations(f.alternative())),
        )
    }

    @Test
    fun `a raw receipt from another full known question cannot replace observed request evidence`() {
        val f = KnownLedgerCase()
        val observed = f.observations(f.known)
        val receipts = observed.associate { it.source to it.receipts }.toMutableMap()
        receipts[observed.last().source] = f.observations(f.alternative()).last().receipts
        assertEquals(
            Refinement.Rejected(QueryImpactLedgerFailure.DOMAIN_MISMATCH),
            QueryImpactLedger.fromEvidence(
                listOf(f.base.producer),
                f.base.domain.boundary,
                QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                emptyList(),
                emptyList(),
                observed,
                f.paths(observed),
                originalProducers = listOf(f.base.producerWitness),
                readReceipts = receipts,
                knownDeclarations = f.known,
            ),
        )
    }

    @Test
    fun `an excluded producer keeps unused known input proofs with zero native observations`() {
        val f = KnownLedgerCase()
        val directory =
            SymbolDiscoveryDirectoryConstraint(
                SymbolDiscoveryDirectory.parse("src").knownValue(),
                SymbolDiscoveryContainment.DESCENDANTS,
            )
        val domain = RelationSearchBoundary.Explicit(f.base.scope, directory)
        val excluded = QueryImpactScopeExclusion.admit(f.base.producer, domain).knownValue()
        val path =
            QueryImpactPath.fromEvidence(
                    f.base.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.ExplicitScopeExclusion(excluded),
                )
                .knownValue()
        val ledger =
            QueryImpactLedger.fromEvidence(
                    listOf(f.base.producer),
                    domain,
                    QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(path),
                    originalProducers = listOf(f.base.producerWitness),
                    knownDeclarations = f.known,
                )
                .knownValue()
        assertSame(f.known, ledger.knownDeclarations)
        assertTrue(ledger.observations.isEmpty())
        assertTrue(ledger.readReceipts.isEmpty())
        assertSame(excluded, (ledger.paths.single().terminal as QueryImpactTerminal.ExplicitScopeExclusion).exclusion)
    }
}

private class KnownLedgerCase {
    val base = QueryImpactLedgerFixture()
    val known = known(base.owner)

    fun alternative(): ValueKnownDeclarations {
        val scope = (base.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE)
        val changed =
            RelationEndpoint.resolve(base.lease, scope, base.evidence).knownValue() as RelationEndpoint.Resolved
        assertEquals(base.owner.valueIdentity, changed.valueIdentity)
        return known(changed)
    }

    fun observations(known: ValueKnownDeclarations): List<ValueFlowStep> =
        base.observations.map { original ->
            ValueFlowStep.fromCompiler(
                    original.source,
                    original.transfers,
                    original.obligations,
                    original.terminal,
                    original.domain,
                    original.examinedWorkUnits,
                    knownDeclarations = known,
                )
                .knownValue()
        }

    fun paths(observed: List<ValueFlowStep>): List<QueryImpactPath> {
        val terminal = QueryImpactTerminal.SupportedDomainEnd.admit(observed.last()).knownValue()
        return base.paths.map {
            QueryImpactPath.fromEvidence(it.producer, it.steps, it.representation, terminal).knownValue()
        }
    }

    fun ledger(observed: List<ValueFlowStep>) =
        QueryImpactLedger.fromEvidence(
            listOf(base.producer),
            base.domain.boundary,
            QueryImpactFlowSemantics.KOTLIN_FORWARD_V1,
            emptyList(),
            emptyList(),
            observed,
            paths(observed),
            originalProducers = listOf(base.producerWitness),
            knownDeclarations = known,
        )

    private fun known(endpoint: RelationEndpoint.Resolved) =
        ValueKnownDeclarations.admit(
                listOf(RevalidatedRelationEndpoint.validate(endpoint, endpoint.evidence).knownValue()),
                base.lease,
            )
            .knownValue()
}

private fun <T, F> Refinement<T, F>.knownValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Known declaration fixture rejected: $failure")
    }
