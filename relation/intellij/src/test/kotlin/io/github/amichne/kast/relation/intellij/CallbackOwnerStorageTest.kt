package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackBodyBinding
import io.github.amichne.kast.relation.contract.CallbackBodySupply
import io.github.amichne.kast.relation.contract.CallbackDirectInvocationBinding
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackOwnerExecution
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationFlow
import io.github.amichne.kast.relation.contract.ImmutableCallbackInvocationUse
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackOwnerStorageTest {
    private val observation = CallbackStorageObservation()
    private val fixture = OwnerStorageFixture(observation)

    @Test
    fun `an owned nested execution consumes its reference cost instead of a second execution footprint`() {
        val outer = fixture.outer()
        val inner = fixture.inner(outer)
        val allowance = outer.retainedBytes + inner.retainedBytes - outer.execution.retainedBytes
        val retention = fixture.retention(allowance)
        retention.admitOwner(outer).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(inner))
        assertEquals(allowance, observation.retainedBytes)
    }

    @Test
    fun `an equal rebuilt child keeps its complete diagnostic estimate`() {
        val outer = fixture.outer()
        val rebuilt = fixture.outer()
        assertEquals(outer, rebuilt)
        assertNotSame(outer, rebuilt)
        val inner = fixture.inner(rebuilt)
        val allowance = outer.retainedBytes + inner.retainedBytes - outer.execution.retainedBytes
        val retention = fixture.retention(allowance)
        retention.admitOwner(outer).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(inner))
        assertEquals(outer.retainedBytes + inner.retainedBytes, observation.retainedBytes)
    }

    @Test
    fun `an owner admitted by another grant keeps its standalone execution cost`() {
        val outer = fixture.outer()
        val inner = fixture.inner(outer)
        fixture.retention(outer.retainedBytes).admitOwner(outer).refined()
        val retention = fixture.retention(inner.retainedBytes - 1L)
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(inner))
        assertEquals(inner.retainedBytes, observation.retainedBytes)
    }

    @Test
    fun `an immutable execution shares only its exact admitted child mapping`() {
        val outer = fixture.outer()
        val inner = fixture.immutableInner(outer)
        val retention = fixture.retention(inner.retainedBytes)
        retention.admitOwner(outer).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(inner))
        assertEquals(inner.retainedBytes, observation.retainedBytes)
    }

    @Test
    fun `an immutable execution pays for an equal unowned child mapping`() {
        val outer = fixture.outer()
        val rebuilt = fixture.outer()
        assertEquals(outer, rebuilt)
        assertNotSame(outer, rebuilt)
        val inner = fixture.immutableInner(rebuilt)
        val retention = fixture.retention(inner.retainedBytes)
        retention.admitOwner(outer).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(inner))
        assertEquals(outer.retainedBytes + inner.retainedBytes, observation.retainedBytes)
    }

    @Test
    fun `an immutable execution shares its exact admitted terminal value`() {
        val outer = fixture.outer()
        val inner = fixture.immutableInner(outer)
        val value = (inner.execution as CallbackOwnerExecution.Immutable).flow.uses.single().value
        val retention = fixture.retention(inner.retainedBytes - value.source.retainedBytes)
        retention.admitOwner(outer).refined()
        retention.admitImmutableValue(value).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(inner))
        assertEquals(inner.retainedBytes - value.source.retainedBytes, observation.retainedBytes)
    }

    @Test
    fun `an equal rebuilt terminal value keeps its complete diagnostic estimate`() {
        val outer = fixture.outer()
        val inner = fixture.immutableInner(outer)
        val rebuilt = fixture.immutableInner(outer)
        val value = (inner.execution as CallbackOwnerExecution.Immutable).flow.uses.single().value
        val copy = (rebuilt.execution as CallbackOwnerExecution.Immutable).flow.uses.single().value
        assertEquals(value, copy)
        assertNotSame(value, copy)
        val retention = fixture.retention(inner.retainedBytes - value.source.retainedBytes)
        retention.admitOwner(outer).refined()
        retention.admitImmutableValue(copy).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitOwner(inner))
        assertTrue(observation.retainedBytes > inner.retainedBytes - value.source.retainedBytes)
    }

    @Test
    fun `another reference to an exact admitted value pays only its reference footprint`() {
        val inner = fixture.immutableInner(fixture.outer())
        val value = (inner.execution as CallbackOwnerExecution.Immutable).flow.uses.single().value
        val retention = fixture.retention(value.retainedBytes - value.source.retainedBytes + 64L)
        retention.admitImmutableValue(value).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitImmutableValue(value))
        assertEquals(value.retainedBytes - value.source.retainedBytes + 64L, observation.retainedBytes)
    }

    @Test
    fun `an equal copied value pays its complete footprint on another reference`() {
        val outer = fixture.outer()
        val inner = fixture.immutableInner(outer)
        val rebuilt = fixture.immutableInner(outer)
        val value = (inner.execution as CallbackOwnerExecution.Immutable).flow.uses.single().value
        val copy = (rebuilt.execution as CallbackOwnerExecution.Immutable).flow.uses.single().value
        assertEquals(value, copy)
        assertNotSame(value, copy)
        val retention = fixture.retention(value.retainedBytes - value.source.retainedBytes + 64L)
        retention.admitImmutableValue(value).refined()
        assertEquals(
            Refinement.Refined(Unit),
            retention.admitImmutableValue(copy),
        )
        assertTrue(observation.retainedBytes > value.retainedBytes - value.source.retainedBytes + 64L)
    }
}

private class OwnerStorageFixture(private val observation: CallbackStorageObservation) {
    private val request = RelationReadTest().request(RelationMeaning.Callees)
    private val file = request.subject.file
    private val named =
        RelationCallableBody.Named.fromCompiler(
                CompilerGroundedSymbolEvidence.fromBoundary(
                        file,
                        0,
                        200,
                        "entry",
                        "fixture.entry",
                        CompilerSymbolKind.FUNCTION,
                        CanonicalCompilerSignature.function("fixture.entry", null, emptyList(), emptyList(), 0)
                            .refined(),
                    )
                    .refined()
            )
            .refined()

    fun retention(bytes: Long) =
        CallbackFlowRetention(
            request.budget
                .copy(returnedBytes = RelationByteLimit.parse(bytes).refined())
                .resources
                .bounded()
                .resultLimit,
            observation,
        )

    fun outer() = owner(body(20, 180), named, 10, 190, emptyList())

    fun inner(outer: CallbackBodyBinding) = owner(body(40, 140), outer.body, 30, 150, listOf(outer))

    fun immutableInner(outer: CallbackBodyBinding): CallbackBodyBinding {
        val body = body(40, 140)
        val endpoint = RelationEndpoint.resolve(request.subject.lease, request.subject.scope, named.evidence).refined()
        val source = ValueSite.fromCompiler(endpoint, body.range, ValueRole.ExpressionResult).refined()
        val origin = ImmutableCallbackValueOrigin.Anonymous(body)
        val value = ImmutableCallbackValue.fromCompiler(origin, source, source, emptyList()).refined()
        val use =
            ImmutableCallbackInvocationUse.Direct(
                value,
                CallbackDirectInvocationBinding.fromCompiler(
                        request.subject.lease.identity,
                        RelationOccurrence.fromBoundary(file, 30, 150).refined(),
                        outer.body,
                    )
                    .refined(),
            )
        val flow =
            ImmutableCallbackInvocationFlow.fromCompiler(
                    origin = origin,
                    source = source,
                    uses = listOf(use),
                    obligations = setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    scan = CallbackInvocationScan.EXHAUSTIVE,
                    ownerBindings = listOf(outer),
                )
                .refined()
                .withOwnerBindings(emptyList())
                .refined()
        return CallbackBodyBinding.fromCompiler(
                body,
                CallbackBodySupply.Stored,
                CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                setOf(
                    CallbackInvocationFlowCause.STORED_CALLBACK,
                    CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                ),
            )
            .refined()
            .withExecution(flow, named)
            .refined()
    }

    private fun owner(
        body: RelationCallableBody.Anonymous,
        enclosing: RelationCallableBody,
        start: Int,
        end: Int,
        owners: List<CallbackBodyBinding>,
    ): CallbackBodyBinding {
        val occurrence = RelationOccurrence.fromBoundary(file, start, end).refined()
        val binding =
            CallbackBindingEvidence.Direct(
                CallbackDirectInvocationBinding.fromCompiler(request.subject.lease.identity, occurrence, enclosing)
                    .refined()
            )
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    request.subject.lease.identity,
                    body,
                    binding,
                    emptyList(),
                    if (owners.isEmpty()) emptySet() else setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    CallbackInvocationScan.NOT_APPLICABLE,
                )
                .refined()
                .withOwnerBindings(owners)
                .refined()
        return CallbackBodyBinding.fromCompiler(
                body,
                CallbackBodySupply.DirectInvocation(occurrence),
                binding,
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
            )
            .refined()
            .withExecution(flow)
            .refined()
    }

    private fun body(start: Int, end: Int): RelationCallableBody.Anonymous {
        val range = ExactDeclarationTextRange.parse(start, end).refined()
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(file, range),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(file, range, signature).refined()
    }
}

private fun <V> Refinement<V, *>.refined(): V = (this as Refinement.Refined).value
