package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerReceiver
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedReceiverScopePositiveTest {
    @Test
    fun `run and with actual callback returns compose invocation with correspondence`() {
        val value = Any()
        assertSame(value, run { value })
        assertSame(value, Any().run { value })
        assertSame(value, with(Any()) { value })
        for (case in
            listOf(
                ReviewedEagerAdmissionCase.RUN_TOP_LEVEL,
                ReviewedEagerAdmissionCase.RUN_RECEIVER,
                ReviewedEagerAdmissionCase.WITH,
            )) {
            val f = ReviewedReceiverScopeFixture(case)
            val source = f.returned()
            val transfer = ReviewedValueTransfer.callbackResult(source, f.binding).flowRefined()
            assertEquals(source.valuePath, transfer.target.valuePath)
            assertEquals(f.binding.occurrence.range, transfer.target.range)
            assertEquals(source.conditions, transfer.target.conditions)
            assertEquals(DeclaredCallbackResultEffect.UNSPECIFIED, f.declared.resultEffect)
            assertEquals(
                ReviewedCallbackInvocationEvidence.Declared(f.declared),
                (transfer.binding as ReviewedCallbackValueBinding).invocationEvidence,
            )
            assertEquals(
                ReviewedValueRelation.CallbackCorrespondence(
                    ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT
                ),
                transfer.relation,
            )
        }
    }

    @Test
    fun `actual extension receiver reaches declared anonymous receiver while preserving nested paths`() {
        for (case in
            listOf(
                ReviewedEagerAdmissionCase.RUN_RECEIVER,
                ReviewedEagerAdmissionCase.WITH,
                ReviewedEagerAdmissionCase.APPLY,
            )) {
            val f = ReviewedReceiverScopeFixture(case)
            val source = f.actual()
            val proof = f.receiverProof()
            val transfer = ReviewedValueTransfer.callbackReceiverInput(source, proof).flowRefined()
            assertEquals(source.valuePath, transfer.target.valuePath)
            assertEquals(source.conditions, transfer.target.conditions)
            assertEquals(source.callStack, transfer.target.callStack)
            assertEquals(f.body.range, transfer.target.range)
            assertEquals(
                ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                transfer.target.role,
            )
            assertEquals(ValueExecutionOwner.declaredCallback(f.declared), transfer.target.executionOwner)
            assertEquals(ReviewedValueRelation.CallbackReceiverInput(proof), transfer.relation)
            assertTrue(
                transfer.retainedBytes >= source.retainedBytes + transfer.target.retainedBytes + proof.retainedBytes
            )
            assertEquals(proof, f.receiverProof())
            assertEquals(proof.hashCode(), f.receiverProof().hashCode())
        }
    }

    @Test
    fun `apply returns only whole receiver with explicit normal callback premise`() {
        val receiver = Any()
        assertSame(receiver, receiver.apply {})
        val f = ReviewedReceiverScopeFixture(ReviewedEagerAdmissionCase.APPLY)
        val source = f.actual(ValueAccessPath.Whole)
        val result = ReviewedValueTransfer.receiverResult(source, f.binding, f.declared).flowRefined()
        assertEquals(source.valuePath, result.target.valuePath)
        assertEquals(source.executionOwner, result.target.executionOwner)
        assertEquals(source.callStack, result.target.callStack)
        val condition = result.target.conditions.facts.single() as ValueCondition.ReviewedCallbackNormalReturn
        assertTrue(condition.origin.matches(source))
        assertEquals(result.binding, condition.binding)
    }

    @Test
    fun `receiver absence remains distinct from with argument and receiver run`() {
        val top = ReviewedReceiverScopeFixture(ReviewedEagerAdmissionCase.RUN_TOP_LEVEL)
        val with = ReviewedReceiverScopeFixture(ReviewedEagerAdmissionCase.WITH)
        val receiver = ReviewedReceiverScopeFixture()
        assertEquals(
            CanonicalCompilerReceiver.Absent,
            (top.binding.target.signature as CanonicalCompilerSignature.Function).receiver,
        )
        assertEquals(
            CanonicalCompilerReceiver.Absent,
            (with.binding.target.signature as CanonicalCompilerSignature.Function).receiver,
        )
        assertTrue(
            (receiver.binding.target.signature as CanonicalCompilerSignature.Function).receiver
                is CanonicalCompilerReceiver.Present
        )
        assertEquals(1, with.binding.position.value)
        assertEquals(ValueRole.Argument(with.invocation, with.base.position(0)), with.actual().role)
    }
}
