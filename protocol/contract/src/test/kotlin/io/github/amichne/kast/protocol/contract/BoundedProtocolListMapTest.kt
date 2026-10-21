package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BoundedProtocolListMapTest {
    @Test
    fun `map preserves order cardinality and immutable storage`() {
        val source = bounded(listOf(3, 1, 3))
        val mapped = source.map { it.toString() }
        assertEquals(listOf("3", "1", "3"), mapped.values)
        assertThrows(UnsupportedOperationException::class.java) { Collections.swap(mapped.values, 0, 1) }
        assertEquals(listOf(3, 1, 3), source.values)
        assertEquals(emptyList<String>(), bounded(emptyList<Int>()).map { it.toString() }.values)
    }

    private fun <T> bounded(values: List<T>): BoundedProtocolList<T> =
        when (val result = BoundedProtocolList.create(values)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error(result.failure.toString())
        }
}
