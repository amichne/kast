package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReviewedWholeMapInputBaselineTest {
    @Test
    fun `whole map content cannot select a single exact callback component`() {
        val f = ReviewedMapEntryInputFixture()
        val source = f.receiver(path = ValueAccessPath.Whole)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedValueTransfer.callbackInput(source, f.binding, f.formal()),
        )
    }

    @Test
    fun `whole map identity cannot become an exact preserved key identity`() {
        val f = ReviewedMapEntryInputFixture()
        val source = f.receiver(path = ValueAccessPath.Whole)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedValueTransfer.mapPreservedKey(source, f.binding),
        )
    }

    @Test
    fun `independent content oracle separates key and value dependencies from map identity`() {
        val key = Any()
        val value = Any()
        val source = mapOf(key to value)
        val keyResult = source.mapValues { it.key }
        val valueResult = source.mapValues { it.value }
        assertNotSame(source, keyResult)
        assertNotSame(source, valueResult)
        assertSame(key, keyResult.values.single())
        assertSame(value, valueResult.values.single())
        assertSame(key, valueResult.keys.single())
    }
}
