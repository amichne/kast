package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCallbackSelectionDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ImpactCallbackSelectionProjectionTest {
    private data class SelectedExecutions(
        val host: RelationEndpoint.Resolved,
        val call: ValueInvocation,
        val invocations: List<CallbackParameterInvocation>,
        val owners: List<ValueExecutionOwner>,
    )

    private fun selectedExecutions(): SelectedExecutions {
        val f = ImpactPathProjectionFixture()
        val host = f.owner as RelationEndpoint.Resolved
        val wrapper = f.call.callable as RelationEndpoint.Resolved
        val bodyRange = range(40, 70)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(host.file, bodyRange),
                    null,
                    emptyList(),
                    listOf("Payload"),
                    0,
                )
                .value() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(host.file, bodyRange, signature).value()
        val call = ValueInvocation.fromCompiler(host, range(20, 100), wrapper).value()
        val supply =
            CallbackArgumentBinding.fromCompiler(
                    call,
                    RelationCallableBody.Named.fromCompiler(host.evidence).value(),
                    ValueArgumentPosition.parse(0).value(),
                    RelationOccurrence.fromBoundary(host.file, 215, 220).value(),
                )
                .value()
        val invocations =
            listOf(range(225, 230), range(235, 240)).map {
                CallbackParameterInvocation.fromCompiler(
                        RelationOccurrence.fromBoundary(host.file, it.startInclusive, it.endExclusive).value(),
                        RelationCallableBody.Named.fromCompiler(wrapper.evidence).value(),
                    )
                    .value()
            }
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    f.lease.identity,
                    body,
                    CallbackBindingEvidence.Bound(supply),
                    invocations,
                    emptySet(),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .value()
        val graph = CompleteCallbackFlowGraph.admit(flow).value()
        val owners = invocations.map { ValueExecutionOwner.callback(host, body, graph, supply, it).value() }
        return SelectedExecutions(host = host, call = call, invocations = invocations, owners = owners)
    }

    @Test
    fun `repeated execution projection retains exact selection and complete graph`() {
        val selected = selectedExecutions()
        val invocations = selected.invocations
        val owners = selected.owners
        val documents = owners.map { it.identity.impactDocument().value() as ImpactExecutionOwnerDocument.Callback }
        assertNotEquals(documents[0], documents[1])
        for (index in owners.indices) {
            val exact = documents[index].selection as ImpactCallbackSelectionDocument.Exact
            assertEquals(invocations[index].occurrence.range.impactDocument().value(), exact.invocation.invocation)
            assertEquals(2, documents[index].invocations.values.size)
        }
    }

    @Test
    fun `callback argument projection retains selected invocation formal actual and supplier`() {
        val executions = selectedExecutions()
        val (host, call) = executions
        val owners = executions.owners
        val documents = owners.map { it.identity.impactDocument().value() as ImpactExecutionOwnerDocument.Callback }
        for (index in owners.indices) {
            val selected = (owners[index].context as ValueExecutionContext.Callback).selectedInvocation
            val exact = documents[index].selection as ImpactCallbackSelectionDocument.Exact
            val parameter =
                ValueFormalParameter.fromCompiler(
                        host,
                        ValueArgumentPosition.parse(0).value(),
                        SymbolDiscoveryCandidateName.parse("payload").value(),
                        range(42, 45),
                        owners[index],
                    )
                    .value()
            val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(call.callable)).value()
            val actual = if (index == 0) range(226, 228) else range(236, 238)
            val projected =
                ValueSourceOperation.CallbackArgumentEntry(selected, frame, parameter, actual).impactDocument().value()
                    as ImpactSourceOperationDocument.CallbackArgumentEntry
            assertEquals(exact.invocation, projected.invocation)
            assertEquals(documents[index], projected.parameter.executionOwner)
            assertEquals(actual.impactDocument().value(), projected.argumentRange)
            assertEquals(frame.impactDocument().value(), projected.supplierFrame)
        }
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
