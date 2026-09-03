package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CompilerEvidenceAdmissionTest {
    @Test
    fun `bounded protocol evidence cannot mutate after admission`() {
        val admitted = BoundedProtocolList.create(listOf("context", "parameter")).refined()

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (admitted.values as MutableList<String>).clear()
        }
        assertEquals(listOf("context", "parameter"), admitted.values)
    }

    @Test
    fun `compatibility capability proof cannot mutate after admission`() {
        val admitted = IdeHostCapabilitySet.parse(
            listOf(
                CanonicalOperation.SYMBOL_INSPECT.id.value,
                CanonicalOperation.RELATION_READ.id.value,
            ),
        ).refined()

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (admitted.capabilities as MutableList<IdeHostCapability>).clear()
        }
        assertEquals(
            listOf(CanonicalOperation.SYMBOL_INSPECT, CanonicalOperation.RELATION_READ),
            admitted.capabilities.map(IdeHostCapability::operation),
        )
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
