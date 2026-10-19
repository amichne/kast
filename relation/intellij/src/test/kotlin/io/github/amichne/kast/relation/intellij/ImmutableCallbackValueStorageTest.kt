package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ImmutableCallbackValue
import io.github.amichne.kast.relation.contract.ImmutableCallbackValueOrigin
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImmutableCallbackValueStorageTest {
    private val fixture = RelationReadTest()
    private val request = fixture.request(RelationMeaning.Callees)
    private val endpoint = fixture.fact(request).target as RelationEndpoint.Resolved
    private val range = ExactDeclarationTextRange.parse(72, 74).refined()
    private val signature =
        CanonicalCompilerSignature.function(
                RelationCallableBody.Anonymous.sourceIdentity(endpoint.file, range),
                null,
                emptyList(),
                emptyList(),
                0,
            )
            .refined() as CanonicalCompilerSignature.Function
    private val origin =
        ImmutableCallbackValueOrigin.Anonymous(
            RelationCallableBody.Anonymous.fromCompiler(endpoint.file, range, signature).refined()
        )
    private val source = ValueSite.fromCompiler(endpoint, range, ValueRole.ExpressionResult).refined()
    private val bound =
        ValueSite.fromCompiler(endpoint, ExactDeclarationTextRange.parse(75, 76).refined(), ValueRole.LocalBinding)
            .refined()
    private val destination =
        ValueSite.fromCompiler(endpoint, ExactDeclarationTextRange.parse(77, 78).refined(), ValueRole.LocalRead)
            .refined()
    private val prefix = ValueTransfer.fromCompiler(source, bound, ValueTransferKind.LOCAL_BINDING).refined()
    private val arrival = ValueTransfer.fromCompiler(bound, destination, ValueTransferKind.LOCAL_READ).refined()
    private val initial = ImmutableCallbackValue.fromCompiler(origin, source, bound, listOf(prefix)).refined()
    private val extended = extend(prefix)
    private val initialBytes = initial.retainedBytes - source.retainedBytes - bound.retainedBytes
    private val extensionBytes =
        extended.retainedBytes - 2L * source.retainedBytes - 2L * bound.retainedBytes - destination.retainedBytes

    @Test
    fun `one value pays each exact site once and keeps container and origin costs`() {
        val retention = retention(initialBytes)
        assertEquals(Refinement.Refined(Unit), retention.admitImmutableValue(initial))
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admit(1L))
    }

    @Test
    fun `a value extension shares exact sites from an admitted prefix`() {
        val retention = retention(initialBytes + extensionBytes)
        retention.admitImmutableValue(initial).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitImmutableValue(extended))
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admit(1L))
    }

    @Test
    fun `a new transfer between the exact admitted sites shares their component storage`() {
        val copied = ValueTransfer.fromCompiler(prefix.source, prefix.target, prefix.kind, prefix.evidence).refined()
        assertEquals(prefix, copied)
        assertNotSame(prefix, copied)
        val retention = retention(initialBytes + extensionBytes)
        retention.admitImmutableValue(initial).refined()
        assertEquals(Refinement.Refined(Unit), retention.admitImmutableValue(extend(copied)))
        assertEquals(Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED), retention.admit(1L))
    }

    @Test
    fun `equal rebuilt sites receive no prefix storage credit`() {
        val copiedSource = ValueSite.fromCompiler(source.enclosing, source.range, source.role).refined()
        val copiedTarget = ValueSite.fromCompiler(bound.enclosing, bound.range, bound.role).refined()
        assertEquals(source, copiedSource)
        assertNotSame(source, copiedSource)
        assertEquals(bound, copiedTarget)
        assertNotSame(bound, copiedTarget)
        val copied = ValueTransfer.fromCompiler(copiedSource, copiedTarget, prefix.kind, prefix.evidence).refined()
        val retention = retention(initialBytes + extensionBytes)
        retention.admitImmutableValue(initial).refined()
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
            retention.admitImmutableValue(extend(copied)),
        )
    }

    @Test
    fun `another grant cannot lend its exact admitted sites`() {
        retention(initial.retainedBytes).admitImmutableValue(initial).refined()
        val retention = retention(extensionBytes)
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
            retention.admitImmutableValue(extended),
        )
    }

    @Test
    fun `a rejected value cannot lend its sites to a later value`() {
        assertTrue(initialBytes > extensionBytes)
        val retention = retention(extensionBytes)
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
            retention.admitImmutableValue(initial),
        )
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
            retention.admitImmutableValue(extended),
        )
        assertEquals(Refinement.Refined(Unit), retention.admit(extensionBytes))
    }

    private fun extend(first: ValueTransfer) =
        ImmutableCallbackValue.fromCompiler(
                origin = origin,
                source = source,
                destination = destination,
                transfers = listOf(first, arrival),
            )
            .refined()

    private fun retention(bytes: Long) =
        CallbackFlowRetention(request.budget.copy(returnedBytes = RelationByteLimit.parse(bytes).refined()))
}

private fun <V> Refinement<V, *>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
