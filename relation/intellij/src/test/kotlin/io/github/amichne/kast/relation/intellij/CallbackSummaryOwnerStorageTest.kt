package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackBodySupply
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CallbackParameterSupplier
import io.github.amichne.kast.relation.contract.CallbackSupplierSelection
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class CallbackSummaryOwnerStorageTest {
    private val fixture = SummaryStorageFixture()

    @Test
    fun `an owner shares an exact forwarding graph already retained in a summary`() {
        val summary = fixture.summary()
        val owner = fixture.observedOwner(summary)
        val graph = (summary.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph
        val retention =
            CallbackFlowRetention(fixture.budget(summary.retainedBytes + owner.retainedBytes - graph.retainedBytes))
        retention.admitSummary(summary).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(owner))
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admit(1L))
    }

    @Test
    fun `an equal rebuilt forwarding graph keeps its complete owner cost`() {
        val summary = fixture.summary()
        val rebuilt = fixture.summary()
        val graph = (summary.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph
        val copy = (rebuilt.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph
        assertEquals(graph.root, copy.root)
        assertEquals(graph.forwardings, copy.forwardings)
        assertNotSame(graph, copy)
        val owner = fixture.observedOwner(rebuilt)
        val retention =
            CallbackFlowRetention(fixture.budget(summary.retainedBytes + owner.retainedBytes - graph.retainedBytes))
        retention.admitSummary(summary).refined()
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admitOwner(owner))
    }

    @Test
    fun `immutable supplied execution shares exact summary and both exact value references`() {
        val summary = fixture.summary()
        val (owner, value) = fixture.immutableOwner(summary)
        val retention =
            CallbackFlowRetention(
                fixture.budget(
                    owner.retainedBytes -
                        value.retainedBytes -
                        value.source.retainedBytes -
                        value.destination.retainedBytes
                )
            )
        retention.admitSummary(summary).refined()
        retention.admitImmutableValue(value).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(owner))
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admit(1L))
    }

    @Test
    fun `an equal unowned summary and a foreign grant cannot lend immutable execution storage`() {
        val summary = fixture.summary()
        val rebuilt =
            CallbackParameterSummary.fromCompiler(
                    formal = summary.formal,
                    invocations = summary.invocations,
                    obligations = summary.obligations,
                    ownerBindings = summary.ownerBindings,
                    scan = summary.scan,
                    forwarding = summary.forwarding,
                )
                .refined()
        assertEquals(summary, rebuilt)
        assertNotSame(summary, rebuilt)
        val (owner, value) = fixture.immutableOwner(summary)
        CallbackFlowRetention(fixture.budget(summary.retainedBytes)).admitSummary(summary).refined()
        val retention =
            CallbackFlowRetention(
                fixture.budget(
                    owner.retainedBytes -
                        value.retainedBytes -
                        value.source.retainedBytes -
                        value.destination.retainedBytes
                )
            )
        retention.admitSummary(rebuilt).refined()
        retention.admitImmutableValue(value).refined()
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admitOwner(owner))
    }
}

private fun SummaryStorageFixture.observedOwner(summary: CallbackParameterSummary): CallbackBodyBinding {
    val (body, binding) = supplier(20)
    val occurrence =
        RelationOccurrence.fromBoundary(
                body.file,
                binding.invocation.range.startInclusive,
                binding.invocation.range.endExclusive,
            )
            .refined()
    return CallbackBodyBinding.fromCompiler(
            body = body,
            supply = CallbackBodySupply.Invocation(occurrence),
            binding = CallbackBindingEvidence.Bound(binding),
            obligations = setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
        )
        .refined()
        .withExecution(summary.instantiate(body, binding).refined())
        .refined()
}

private fun SummaryStorageFixture.immutableOwner(
    summary: CallbackParameterSummary
): Pair<CallbackBodyBinding, ImmutableCallbackValue> {
    val (body, binding) = supplier(20)
    val endpoint = binding.invocation.enclosing as RelationEndpoint.Resolved
    val source = ValueSite.fromCompiler(endpoint, body.range, ValueRole.ExpressionResult).refined()
    val argument =
        ValueSite.fromCompiler(endpoint, body.range, ValueRole.Argument(binding.invocation, binding.position)).refined()
    val origin = ImmutableCallbackValueOrigin.Anonymous(body)
    val value =
        ImmutableCallbackValue.fromCompiler(
                origin = origin,
                source = source,
                destination = argument,
                transfers = listOf(ValueTransfer.fromCompiler(source, argument, ValueTransferKind.ARGUMENT).refined()),
            )
            .refined()
    val supplier =
        CallbackParameterSupplier.fromCompiler(binding, CallbackSupplierSelection.Explicit(argument), value).refined()
    val flow =
        ImmutableCallbackInvocationFlow.fromCompiler(
                origin = origin,
                source = source,
                uses = listOf(ImmutableCallbackInvocationUse.Supplied(supplier, summary)),
                obligations = emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
            )
            .refined()
    val owner =
        CallbackBodyBinding.fromCompiler(
                body = body,
                supply = CallbackBodySupply.Stored,
                binding = CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                obligations =
                    setOf(
                        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                        CallbackInvocationFlowCause.STORED_CALLBACK,
                    ),
            )
            .refined()
            .withExecution(flow, RelationCallableBody.Named.fromCompiler(endpoint.evidence).refined())
            .refined()
    return owner to value
}

private fun <V> Refinement<V, *>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
