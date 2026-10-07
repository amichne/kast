package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CallbackReferenceReceiver
import io.github.amichne.kast.relation.contract.CallbackReferenceReceivers
import io.github.amichne.kast.relation.contract.NamedCallbackReference
import io.github.amichne.kast.relation.contract.NamedCallbackReferenceFlow
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallableObservation
import io.github.amichne.kast.relation.contract.RelationCallableTarget
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CompleteOnlyNamedReferenceTest : CompleteOnlyCallbackQueryCase() {
    @Test
    fun `complete named reference proof is retained without creating a named row`() = runTest {
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(namedObservation(true)),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result = protocol.executeAutomatically(strict, fixture.authority, budget, policy())
        assertTrue(result is OperationOutcome.Complete)
        script.assertDrained()
    }

    @Test
    fun `unsupported named reference retains its exact failure in strict rejection`() = runTest {
        val script =
            Script(
                listOf(emptyList()),
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        emptyList(),
                        relationObservations = listOf(namedObservation(false)),
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val result =
            protocol.executeAutomatically(strict, fixture.authority, budget, policy()) as OperationOutcome.Rejected
        val rejected = result.reason as QueryRunRejection.CompletionUnproven
        assertEquals(
            io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument.Unavailable(
                io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.STORED_CALLBACK
            ),
            rejected.callbackGraphFailure?.cause,
        )
        script.assertDrained()
    }

    private fun namedObservation(supported: Boolean): QueryRelationObservation {
        val relation =
            RelationRequest.start(fixture.selector, RelationMeaning.Callees, RelationPagingFixture.published().budget)
        val owner = CompilerGroundedSymbolEvidence.fromSelector(fixture.selector)
        val target = RelationEndpoint.resolve(fixture.authority, fixture.selector.scope, owner).refined()
        val reference =
            NamedCallbackReference.fromCompiler(
                    occurrence(1, 4),
                    target,
                    CallbackReferenceReceivers(CallbackReferenceReceiver.Absent, CallbackReferenceReceiver.Absent),
                    referenceFlow(supported),
                )
                .refined()
        val observed =
            RelationCallableObservation.fromNativeBoundary(
                    relation,
                    occurrence(1, 4),
                    owner,
                    RelationCallableBody.Named.fromCompiler(owner).refined(),
                    RelationCallableTarget.NamedReference(reference),
                )
                .refined()
        val batch =
            RelationBatch.create(
                    relation,
                    emptyList(),
                    RelationByteCount.parse(observed.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .refined(),
                    RelationWorkCount.parse(1).refined(),
                    RelationResultCount.parse(0).refined(),
                    callableObservations = listOf(observed),
                )
                .refined()
        return QueryRelationObservation.from(
            RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        )
    }

    private fun referenceFlow(supported: Boolean): NamedCallbackReferenceFlow {
        val previous = (boundFlow() as CallbackInvocationFlowRead.Observed).flow
        val binding = (previous.binding as CallbackBindingEvidence.Bound).binding
        val formal =
            CallbackParameterIdentity.fromCompiler(binding.invocation.callable, binding.position, binding.parameter)
                .refined()
        val summary =
            CallbackParameterSummary.fromCompiler(
                    formal,
                    previous.invocations,
                    previous.obligations,
                    previous.ownerBindings,
                    previous.scan,
                    previous.forwarding,
                )
                .refined()
        return if (supported) NamedCallbackReferenceFlow.Supplied(binding, summary)
        else NamedCallbackReferenceFlow.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK)
    }
}
