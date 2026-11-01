package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedNestedMapValuesTest {
    @Test
    fun `nested callback rejects another anonymous supplier and stale parent proof`() {
        val f = ReviewedNestedMapValuesFixture()
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
            f.nested(supplier = f.base.body(401, 459)),
        )
        val stale = f.parent(source = ReviewedValueFixture(generation = 2))
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.BASIS_MISMATCH),
            f.nested(supplier = stale.body, parent = stale),
        )
    }

    @Test
    fun `nested callback rejects child occurrence outside its exact parent`() {
        val f = ReviewedNestedMapValuesFixture()
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
            f.nested(occurrence = f.base.occurrence(399, 459)),
        )
        val sibling = f.parent(body = f.base.body(400, 430))
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.OWNER_MISMATCH),
            f.nested(supplier = sibling.body, parent = sibling),
        )
    }

    @Test
    fun `nested mapValues output belongs to exact declared parent without invocation promotion`() {
        val f = ReviewedNestedMapValuesFixture()
        val nested = f.nested().flowRefined()
        val source = f.placement.returned(nested)
        val transfer = ReviewedValueTransfer.callbackResult(source, nested).flowRefined()
        assertEquals(ValueExecutionOwner.declaredCallback(f.parent), transfer.target.executionOwner)
        assertEquals(ReviewedCallbackParent.DeclaredCallback(f.parent), nested.parent)
        assertEquals(ReviewedCallbackInvocationEvidence.Unestablished, nested.invocationEvidence)
        assertEquals(source.callStack, transfer.target.callStack)
        assertEquals(source.valuePath.segments, transfer.target.valuePath.segments.drop(1))
        val premise = transfer.target.conditions.facts.single() as ValueCondition.ReviewedMappedKeySurvives
        assertSame(nested, premise.entry.binding)
        assertTrue(premise.origin.matches(source))
        assertEquals(nested.occurrence.range, transfer.target.range)
    }

    @Test
    fun `nested parent changes source authority identity and proof size`() {
        val f = ReviewedNestedMapValuesFixture()
        val first = f.nested().flowRefined()
        val repeated = f.nested().flowRefined()
        val other = f.nested(parent = f.parent(kind = CallbackDependencyInvocationKind.AT_MOST_ONCE)).flowRefined()
        assertEquals(first, repeated)
        assertEquals(first.hashCode(), repeated.hashCode())
        assertNotEquals(first, other)
        assertNotEquals(first, f.named)
        assertEquals(f.named.retainedBytes + f.parent.retainedBytes, first.retainedBytes)
        assertNotEquals(
            f.placement.returned(first).identity,
            f.placement.returned(other).identity,
        )
        assertEquals(ReviewedCallbackParent.Declaration, f.named.parent)
        assertEquals(ValueExecutionOwner.declaration(f.base.enclosing), f.named.parentExecutionOwner)
    }

    @Test
    fun `named callback return cannot acquire the nested binding`() {
        val f = ReviewedNestedMapValuesFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(f.placement.returned(f.named), f.nested().flowRefined()),
        )
    }

    @Test
    fun `declaration owned invocation evidence cannot replace the anonymous parent`() {
        val f = ReviewedNestedMapValuesFixture()
        val nested = f.nested().flowRefined()
        val contract =
            CallbackDependencyContract.fromCompiler(
                    basis = f.base.enclosing.lease.identity,
                    occurrence = nested.occurrence,
                    owner = f.base.supplier,
                    target = nested.target,
                    position = nested.position,
                    classDigest = DependencyClassDigest.parse(nested.artifact.facadeSha256).flowRefined(),
                    provenance = CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                    invocationKind = CallbackDependencyInvocationKind.EXACTLY_ONCE,
                )
                .flowRefined()
        val misplaced =
            DeclaredCallbackValueBinding.admit(
                    enclosing = f.base.enclosing,
                    body = nested.body,
                    contract = contract,
                    resultEffect = DeclaredCallbackResultEffect.UNSPECIFIED,
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            nested.composeDeclared(misplaced),
        )
    }
}
