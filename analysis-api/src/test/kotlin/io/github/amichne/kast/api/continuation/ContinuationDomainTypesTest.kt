package io.github.amichne.kast.api.continuation

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContinuationDomainTypesTest {
    @Test
    fun `ttl accepts only positive finite nanosecond durations`() {
        ContinuationTtl.of(Duration.ofNanos(1))

        assertThrows(IllegalArgumentException::class.java) {
            ContinuationTtl.of(Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContinuationTtl.of(Duration.ofNanos(-1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContinuationTtl.of(Duration.ofSeconds(Long.MAX_VALUE))
        }
    }

    @Test
    fun `capacity accepts only positive counts`() {
        ContinuationCapacity.of(1)

        assertThrows(IllegalArgumentException::class.java) {
            ContinuationCapacity.of(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContinuationCapacity.of(-1)
        }
    }
}
