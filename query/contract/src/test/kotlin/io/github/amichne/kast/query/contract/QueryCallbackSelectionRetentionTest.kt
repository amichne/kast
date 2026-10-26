package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallbackInputFailure
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCallbackSelectionRetentionTest {
    private fun selectedSites(f: QueryImpactLedgerFixture): List<ValueSite> {
        val wrapper = wrapper(f)
        val bodyRange = range(30, 70)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(f.file, bodyRange),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .value() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(f.file, bodyRange, signature).value()
        val call = ValueInvocation.fromCompiler(f.owner, range(20, 80), wrapper).value()
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    call,
                    RelationCallableBody.Named.fromCompiler(f.evidence).value(),
                    ValueArgumentPosition.parse(0).value(),
                    RelationOccurrence.fromBoundary(f.file, 215, 220).value(),
                )
                .value()
        val calls =
            listOf(range(230, 240), range(250, 260)).map {
                CallbackParameterInvocation.fromCompiler(
                        RelationOccurrence.fromBoundary(f.file, it.startInclusive, it.endExclusive).value(),
                        RelationCallableBody.Named.fromCompiler(wrapper.evidence).value(),
                    )
                    .value()
            }
        val complete =
            CompleteCallbackFlowGraph.admit(
                    CallbackInvocationFlow.fromCompiler(
                            f.lease.identity,
                            body,
                            CallbackBindingEvidence.Bound(binding),
                            calls,
                            emptySet(),
                            CallbackInvocationScan.EXHAUSTIVE,
                        )
                        .value()
                )
                .value()
        return calls.map {
            val owner = ValueExecutionOwner.callback(f.owner, body, complete, binding, it).value()
            ValueSite.fromCompiler(f.owner, range(40, 50), ValueRole.ExpressionResult, owner).value()
        }
    }

    @Test
    fun `selected execution retains complete invocation graph and charges shared selection reference`() {
        val f = QueryImpactLedgerFixture()
        val sites = selectedSites(f)
        assertNotEquals(sites[0].identity, sites[1].identity)
        for (site in sites) {
            val context = site.executionOwner.context as ValueExecutionContext.Callback
            val graph = QueryImpactRetainedGraph()
            val retained = graph.site(site)
            assertTrue(retained > QueryImpactRetainedGraph().site(f.producer))
            assertEquals(8L, context.selectedInvocation.storageBytes(graph))
            for (invocation in context.invocations) assertEquals(8L, invocation.storageBytes(graph))
            assertFalse(site.hasForeignBasis(f.lease))
            assertTrue(site.hasForeignBasis(QueryImpactLedgerFixture(generation = 2).lease))
        }
    }

    @Test
    fun `callback failure payload is charged once with shared evidence retained`() {
        val f = QueryImpactLedgerFixture()
        val failure = ValueCallbackInputFailure.Callback(CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING)
        val obligation = ValueFlowObligation.fromCallbackInput(f.producer, failure).value()
        val graph = QueryImpactRetainedGraph()
        assertTrue(obligation.storageBytes(graph) > failure.retainedBytes)
        assertEquals(8L, obligation.storageBytes(graph))
        assertEquals(8L, graph.node(failure) { error("Retained failure must be shared") })
        assertFalse(obligation.hasForeignBasis(f.lease))
        val path =
            QueryImpactPath.fromEvidence(
                    f.producer,
                    emptyList(),
                    QueryImpactRepresentation.NotModeled,
                    QueryImpactTerminal.Unresolved.Flow(obligation),
                )
                .value()
        val terminalGraph = QueryImpactRetainedGraph()
        assertTrue(terminalGraph.path(path) > failure.retainedBytes)
        assertEquals(8L, terminalGraph.node(failure) { error("Terminal lost its callback failure payload") })
    }

    private fun wrapper(f: QueryImpactLedgerFixture): RelationEndpoint.Resolved =
        RelationEndpoint.resolve(
                f.lease,
                f.scope,
                CompilerGroundedSymbolEvidence.fromBoundary(
                        f.file,
                        210,
                        300,
                        "wrapper",
                        "fixture.wrapper",
                        CompilerSymbolKind.FUNCTION,
                        CanonicalCompilerSignature.function(
                                "fixture.wrapper",
                                null,
                                emptyList(),
                                listOf("()->Unit"),
                                0,
                            )
                            .value(),
                    )
                    .value(),
            )
            .value()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
