package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostMaxHeapTest {
    private fun mebibytes(raw: String): Int = (HostMaxHeap.parseSetting(raw) as Refinement.Refined).value.mebibytes

    @Test
    fun `the setting admits both range boundaries and renders one -Xmx option`() {
        assertEquals(1024, mebibytes("1024"))
        assertEquals(32768, mebibytes("32768"))
        assertEquals("-Xmx8192m", (HostMaxHeap.parseSetting("8192") as Refinement.Refined).value.jvmOption)
        assertEquals("-Xmx1536m", HostMaxHeap.DEFAULT.jvmOption)
    }

    @Test
    fun `values just outside the range and malformed text are rejected with distinct failures`() {
        for (raw in listOf("0", "1023", "32769", "99999")) assertEquals(
            Refinement.Rejected(HostMaxHeapFailure.OUT_OF_RANGE),
            HostMaxHeap.parseSetting(raw),
        )
        for (raw in listOf("", "-2048", "+2048", " 2048", "8g", "8192m", "100000", "99999999999", "1.5")) assertEquals(
            Refinement.Rejected(HostMaxHeapFailure.INVALID_SETTING),
            HostMaxHeap.parseSetting(raw),
        )
    }

    @Test
    fun `resolution keeps the prior 1536 MiB default and rejects an invalid saved or explicit value`() {
        val key = "KAST_HOST_MAX_HEAP_MIB"
        val default = ResolvedKastConfiguration.resolve(ConfigurationSources()) as Refinement.Refined
        assertEquals(1536, default.value.hostMaxHeap.mebibytes)
        val explicit =
            ResolvedKastConfiguration.resolve(
                ConfigurationSources(savedWorkspace = listOf(key to "4096"), commandLine = listOf(key to "8192"))
            ) as Refinement.Refined
        assertEquals(8192, explicit.value.hostMaxHeap.mebibytes)
        val rejected =
            ResolvedKastConfiguration.resolve(ConfigurationSources(environment = mapOf(key to "512")))
                as Refinement.Rejected
        assertEquals(key, rejected.failure.key)
        assertEquals(ConfigurationFailure.INVALID_VALUE, rejected.failure.reason)
    }
}
