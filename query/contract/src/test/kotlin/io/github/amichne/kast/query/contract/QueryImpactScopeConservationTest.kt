package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.DeclaredCallbackResultEffect
import io.github.amichne.kast.relation.contract.ReviewedCallbackInvocationEvidence
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryImpactScopeConservationTest {
    @Test
    fun `a composed scope edge cannot disappear from either observation or path`() {
        for (callable in LedgerScopeCallable.entries) {
            val f = QueryImpactScopeFixture(callable)
            val noEdge = f.observe(f.source)
            assertEquals(
                Refinement.Rejected(QueryImpactLedgerFailure.UNPROVEN_REVIEWED_STEP),
                f.ledger(listOf(f.path), listOf(noEdge) + f.observations.drop(1)),
                callable.name,
            )
            val truncated =
                QueryImpactPath.fromEvidence(
                        f.source,
                        emptyList(),
                        QueryImpactRepresentation.NotModeled,
                        QueryImpactTerminal.SupportedDomainEnd.admit(noEdge).scopeEvidence(),
                    )
                    .scopeEvidence()
            assertEquals(
                Refinement.Rejected(QueryImpactLedgerFailure.MISSING_BRANCH),
                f.ledger(listOf(truncated)),
                callable.name,
            )
        }
    }

    @Test
    fun `scope return correspondence cannot discharge an unresolved sibling`() {
        for (callable in LedgerScopeCallable.entries) {
            val f = QueryImpactScopeFixture(callable)
            val obligation = ValueFlowObligation(f.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
            val observed = f.observe(f.source, listOf(f.edge), listOf(obligation))
            assertEquals(
                Refinement.Rejected(QueryImpactLedgerFailure.MISSING_OBLIGATION),
                f.ledger(listOf(f.path), listOf(observed) + f.observations.drop(1)),
                callable.name,
            )
        }
    }

    @Test
    fun `conserved scope paths keep exact correspondence and distinct evidence authorities`() {
        for (callable in LedgerScopeCallable.entries) {
            val f = QueryImpactScopeFixture(callable)
            val ledger = f.ledger(listOf(f.path)).scopeEvidence()
            assertEquals(
                QueryImpactClassification.Established(QueryImpactRelationship.EXACT),
                f.path.classification,
            )
            assertEquals(f.source.valuePath, f.edge.target.valuePath)
            assertEquals(f.edge.target.valuePath, f.compiler.target.valuePath)
            assertEquals(f.edge.target.conditions, f.compiler.target.conditions)
            when (callable) {
                LedgerScopeCallable.LET -> assertEquals(f.source.conditions, f.edge.target.conditions)
                LedgerScopeCallable.ALSO -> {
                    assertEquals(f.source.conditions.facts.size + 1, f.edge.target.conditions.facts.size)
                    val premise =
                        assertInstanceOf(
                            ValueCondition.ReviewedCallbackNormalReturn::class.java,
                            f.edge.target.conditions.facts.last(),
                        )
                    assertSame(f.edge.binding, premise.binding)
                }
            }

            val invocation =
                assertInstanceOf(
                    ReviewedCallbackInvocationEvidence.Declared::class.java,
                    f.edge.binding.invocationEvidence,
                )
            assertSame(f.declared, invocation.binding)
            assertEquals(DeclaredCallbackResultEffect.UNSPECIFIED, invocation.binding.resultEffect)
            val count = QueryImpactWitnessView.count(ledger, QueryImpactWitnessSection.NATIVE_READS).scopeEvidence()
            val page =
                QueryImpactWitnessView.create(ledger, QueryImpactWitnessSection.NATIVE_READS, 0, count.value)
                    .scopeEvidence()
            assertEquals(1, page.entries.count { it.evidence is QueryImpactWitnessEntry.ReviewedTransfer })
            assertEquals(1, page.entries.count { it.evidence is QueryImpactWitnessEntry.CompilerTransfer })
            assertEquals(
                setOf(QueryImpactRequiredObligation.PRODUCER_IDENTITY),
                assertInstanceOf(QueryImpactClosure.Unresolved::class.java, ledger.closure).required,
            )
        }
    }

    @Test
    fun `retained unresolved sibling keeps native flow incomplete beside an exact scope path`() {
        for (callable in LedgerScopeCallable.entries) {
            val f = QueryImpactScopeFixture(callable)
            val obligation = ValueFlowObligation(f.source, ValueFlowUnsupportedCause.UNMODELED_CALL)
            val observed = f.observe(f.source, listOf(f.edge), listOf(obligation))
            val unresolved =
                QueryImpactPath.fromEvidence(
                        f.source,
                        emptyList(),
                        QueryImpactRepresentation.NotModeled,
                        QueryImpactTerminal.Unresolved.Flow(obligation),
                    )
                    .scopeEvidence()
            val ledger = f.ledger(listOf(f.path, unresolved), listOf(observed) + f.observations.drop(1)).scopeEvidence()
            assertEquals(2, ledger.paths.size)
            assertEquals(
                setOf(QueryImpactRequiredObligation.NATIVE_FLOW, QueryImpactRequiredObligation.PRODUCER_IDENTITY),
                assertInstanceOf(QueryImpactClosure.Unresolved::class.java, ledger.closure).required,
            )
            assertEquals(f.path.classification, ledger.paths.first().classification)
        }
    }

    @Test
    fun `composed invocation evidence consumes capacity and shares only established graph identities`() {
        for (callable in LedgerScopeCallable.entries) {
            val f = QueryImpactScopeFixture(callable)
            val uncomposedBytes = f.reviewed.storageBytes(QueryImpactRetainedGraph())
            val graph = QueryImpactRetainedGraph()
            val composedBytes = f.edge.binding.storageBytes(graph)
            assertTrue(composedBytes - uncomposedBytes > f.declared.retainedBytes)
            val allowance = QueryStorageAllowance.Bounded(QueryByteLimit.parse(uncomposedBytes).scopeEvidence())
            assertTrue(allowance.permits(uncomposedBytes))
            assertFalse(allowance.permits(composedBytes))
            assertEquals(8L, f.declared.storageBytes(graph))
            assertEquals(8L, f.edge.binding.storageBytes(graph))
            assertEquals(
                8L,
                graph.node(f.edge.binding.invocationEvidence) { error("Invocation evidence was not retained") },
            )
        }
    }
}
