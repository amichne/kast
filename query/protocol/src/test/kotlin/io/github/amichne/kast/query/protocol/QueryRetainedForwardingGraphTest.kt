package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackForwardingEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CompleteCallbackForwardingGraph
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Actual retained presentation over admitted compiler facts, with no semantic executor available to replay. */
internal class QueryRetainedForwardingGraphTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `retained reads preserve finite recursive graph and reissue every exact site without native replay`() =
        runTest {
            val recursive = recursiveFlow()
            val graph = (recursive.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph
            val store = QueryStateStore(clock = { 0L })
            val issued = retainObservation(store, recursiveObservation(recursive))
            val protocol =
                CanonicalQueryProtocol(
                    QueryOperations { error("Retained graph reads must not replay semantic work") },
                    fixture.references,
                    store,
                )
            val retained =
                protocol.executePage(
                    QueryRunRequest.ReadResult.symbols(issued.reference, output = output),
                    fixture.authority,
                    budget,
                ) as OperationOutcome.Complete
            val document =
                retained.evidence.payload.relationObservations.values.single().callbackObservations.values.single()
            val projected = document.flow as QueryCallbackFlowDocument.Observed
            val evidence = projected.forwarding as QueryCallbackForwardingEvidenceDocument.ExhaustedGraph
            assertEquals(graph.root.position.value, evidence.root.position.value)
            assertEquals(listOf(evidence.root), evidence.formals.values)
            assertEquals(1, evidence.forwardings.values.size)
            assertEquals(0, projected.invocations.values.single().forwardings.values.size)
            val forwarded = evidence.forwardings.values.single()
            assertEquals(evidence.root, forwarded.source)
            assertEquals(142, forwarded.argument.range.startInclusive.value)
            val restored =
                fixture.references.restoreCandidate(forwarded.argument.candidateSelector, fixture.authority)
                    as CanonicalSelectorDecoding.Decoded
            val site = restored.value as io.github.amichne.kast.symbol.contract.CandidateSelector.Range
            assertEquals(fixture.authority, site.lease)
            assertEquals(142, site.startInclusive.value)
            val replay =
                protocol.executePage(
                    QueryRunRequest.ReadResult.symbols(issued.reference, output = output),
                    fixture.authority,
                    budget,
                ) as OperationOutcome.Complete
            assertEquals(retained.evidence.payload.relationObservations, replay.evidence.payload.relationObservations)
        }

    @Test
    fun `supplier activation failure retains the proven graph in qualified result reads`() = runTest {
        val original = recursiveFlow()
        val qualified =
            CallbackInvocationFlow.fromCompiler(
                    original.basis,
                    original.body,
                    original.binding,
                    original.invocations,
                    setOf(CallbackInvocationFlowCause.STORED_CALLBACK),
                    CallbackInvocationScan.INCOMPLETE,
                    original.forwarding,
                )
                .refined()
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(recursiveObservation(qualified)),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejected = protocol.execute(strict, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val failure = rejected.reason as QueryRunRejection.CompletionUnproven
        val handle = (failure.evidence as QueryCompletionEvidenceDocument.Retained).result
        script.assertDrained()
        val read =
            protocol.executePage(QueryRunRequest.ReadResult.symbols(handle, output = output), fixture.authority, budget)
                as OperationOutcome.Qualified
        val flow =
            read.evidence.payload.relationObservations.values.single().callbackObservations.values.single().flow
                as QueryCallbackFlowDocument.Observed
        val graph = flow.forwarding as QueryCallbackForwardingEvidenceDocument.ExhaustedGraph
        assertEquals(QueryCallbackInvocationScanDocument.INCOMPLETE, flow.scan)
        assertEquals(1, graph.formals.values.size)
        assertEquals(1, graph.forwardings.values.size)
        assertEquals("STORED_CALLBACK", flow.obligations.values.single().name)
    }

    private fun retainObservation(
        store: QueryStateStore,
        observation: QueryRelationObservation,
    ): QueryResultIssuance.Issued {
        val execution =
            QueryExecutionResult.Complete.create(
                QueryResult(QueryRows.Symbols.of(emptyList()), emptyList(), relationObservations = listOf(observation)),
                QueryCoverage.Complete(QueryCount.parse(0).refined()),
            )
        return store.issueResult(request, QueryRetainedResult.capture(fixture.authority, execution).refined())
            as QueryResultIssuance.Issued
    }

    private fun recursiveFlow(): CallbackInvocationFlow {
        val flow = (boundFlow() as CallbackInvocationFlowRead.Observed).flow
        val bound = (flow.binding as CallbackBindingEvidence.Bound).binding
        val root =
            CallbackParameterIdentity.fromCompiler(bound.invocation.callable, bound.position, bound.parameter).refined()
        val recursion =
            CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(root.callable, range(140, 150), root.callable).refined(),
                    RelationCallableBody.Named.fromCompiler(wrapperEvidence()).refined(),
                    root.position,
                    root.parameter,
                )
                .refined()
        val edge = CallbackParameterForwarding.fromCompiler(root, occurrence(142, 145), recursion).refined()
        val graph = CompleteCallbackForwardingGraph.fromCompiler(root, listOf(root), listOf(edge), flow.scan).refined()
        return CallbackInvocationFlow.fromCompiler(
                flow.basis,
                flow.body,
                flow.binding,
                flow.invocations,
                flow.obligations,
                flow.scan,
                CallbackForwardingEvidence.ExhaustedGraph(graph),
            )
            .refined()
    }

    private fun recursiveObservation(flow: CallbackInvocationFlow): QueryRelationObservation {
        val relation = RelationRequest.start(fixture.selector, RelationMeaning.Callees, fixture.budget)
        val owner = CompilerGroundedSymbolEvidence.fromSelector(fixture.selector)
        val callback =
            RelationCallbackObservation.fromNativeBoundary(
                    relation,
                    occurrence(2, 3),
                    owner,
                    owner,
                    occurrence(1, 4),
                    CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.NON_INLINE_ARGUMENT, occurrence(1, 4)),
                    CallbackInvocationFlowRead.Observed(flow),
                )
                .refined()
        val batch =
            RelationBatch.create(
                    relation,
                    emptyList(),
                    RelationByteCount.parse(callback.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .refined(),
                    RelationWorkCount.parse(1).refined(),
                    RelationResultCount.parse(0).refined(),
                    callbackObservations = listOf(callback),
                )
                .refined()
        return QueryRelationObservation.from(
            RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        )
    }
}
