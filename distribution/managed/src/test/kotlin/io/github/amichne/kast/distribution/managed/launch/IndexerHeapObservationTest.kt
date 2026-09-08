package io.github.amichne.kast.distribution.managed.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class IndexerHeapObservationTest {
    @Test
    fun `running JVM evidence retains admitted and actually observed maxima`() {
        val observation = (IndexerHeapObservation.observe(arrayOf("--max-heap-mib=8192"), 8589934592L) as IndexerHeapObservationResult.Observed).observation
        assertEquals("""{"requestedMaxHeapMiB":8192,"observedMaxHeapBytes":8589934592}""", observation.boundaryDocument())
    }
    @Test
    fun `missing duplicate invalid and absent observation fail closed`() {
        for (args in listOf(emptyArray(), arrayOf("--max-heap-mib=8192", "--max-heap-mib=8192"), arrayOf("--max-heap-mib=08"), arrayOf("--max-heap-mib=8g"))) {
            assertInstanceOf(IndexerHeapObservationResult.Rejected::class.java, IndexerHeapObservation.observe(args, 1L))
        }
        assertEquals(IndexerHeapObservationResult.Rejected(IndexerHeapObservationFailure.INVALID_OBSERVATION), IndexerHeapObservation.observe(arrayOf("--max-heap-mib=1536"), 0L))
    }
}
