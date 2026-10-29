package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackReceiverDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedReceiverScopeProjectionTest {
    @Test
    fun `scope receiver rejects an enclosing dispatch substitution`() {
        val input =
            ImpactReviewedReceiverScopeFixture(ReceiverScopeFamily.RECEIVER_RUN)
                .input()
                .impactDocument()
                .componentValue()
        assertTrue(input.admitsEvidence())
        assertFalse(
            input
                .copy(
                    target =
                        input.target.copy(
                            role =
                                ImpactValueRoleDocument.Formal(
                                    ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.DISPATCH)
                                )
                        )
                )
                .admitsEvidence()
        )
        val receiver = input.source.role as ImpactValueRoleDocument.Receiver
        assertFalse(
            input
                .copy(source = input.source.copy(role = receiver.copy(kind = ImpactReceiverKindDocument.DISPATCH)))
                .admitsEvidence()
        )
    }

    @Test
    fun `receiver input preserves exact declared ownership and nested suffixes`() {
        for (family in listOf(ReceiverScopeFamily.RECEIVER_RUN, ReceiverScopeFamily.WITH, ReceiverScopeFamily.APPLY)) {
            val input = ImpactReviewedReceiverScopeFixture(family).input().impactDocument().componentValue()
            assertTrue(input.admitsEvidence())
            val receiver =
                (input.binding.relation as ImpactReviewedValueRelationDocument.CallbackReceiverInput).receiver
            assertEquals(input.source.valuePath, input.target.valuePath)
            assertEquals(ImpactExecutionOwnerDocument.DeclaredCallback(receiver.declared), input.target.executionOwner)
            assertEquals(receiver.binding.body.range, input.target.range)
            assertEquals("kotlin/String", receiver.compilerType.value)
        }
    }

    @Test
    fun `top level and receiver return correspondence rejects a direct expression shortcut`() {
        for (family in
            listOf(ReceiverScopeFamily.TOP_RUN, ReceiverScopeFamily.RECEIVER_RUN, ReceiverScopeFamily.WITH)) {
            val result = ImpactReviewedReceiverScopeFixture(family).returned().impactDocument().componentValue()
            assertTrue(result.admitsEvidence())
            assertFalse(
                result
                    .copy(source = result.source.copy(role = ImpactValueRoleDocument.ExpressionResult))
                    .admitsEvidence()
            )
            assertEquals(result.source.valuePath, result.target.valuePath)
            if (family == ReceiverScopeFamily.TOP_RUN)
                assertEquals(
                    ImpactDeclaredCallbackReceiverDocument.Absent,
                    (result.binding.authority as ImpactReviewedValueBindingDocument).selected.receiver,
                )
        }
    }

    @Test
    fun `apply keeps its normal callback return premise`() {
        val result =
            ImpactReviewedReceiverScopeFixture(ReceiverScopeFamily.APPLY)
                .applyResult()
                .impactDocument()
                .componentValue()
        assertTrue(result.admitsEvidence())
        assertTrue(
            result.target.conditions.values.single() is ImpactValueConditionDocument.ReviewedCallbackNormalReturn
        )
        assertTrue(result.source.valuePath.values.isEmpty())
        assertTrue(result.target.valuePath.values.isEmpty())
    }
}
