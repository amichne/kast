package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReviewedMapEntryInputNegativeTest {
    @Test
    fun `entry input rejects dispatch receiver and another invocation`() {
        val f = ReviewedMapEntryInputFixture()
        for (source in
            listOf(
                f.receiver(kind = ValueReceiverKind.DISPATCH),
                f.receiver(callRange = flowRange(423, 459)),
            )) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                ReviewedValueTransfer.callbackInput(source, f.binding, f.formal()),
            )
        }
    }

    @Test
    fun `plain field contents cannot acquire an entry component`() {
        val f = ReviewedMapEntryInputFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedValueTransfer.callbackInput(f.receiver(path = f.base.fieldPath), f.binding, f.formal()),
        )
    }

    @Test
    fun `entry input cannot bypass an ignored formal and constant return`() {
        val f = ReviewedMapEntryInputFixture()
        val selected = Any()
        val constant = Any()
        val oracle = mapOf("key" to selected).mapValues { constant }
        assertFalse(selected in oracle.values)
        when (val input = ReviewedValueTransfer.callbackInput(f.receiver(), f.binding, f.formal())) {
            is Refinement.Rejected -> assertEquals(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, input.failure)
            is Refinement.Refined ->
                assertEquals(
                    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
                    ReviewedValueTransfer.callbackResult(input.value.target, f.binding),
                )
        }
    }
}
