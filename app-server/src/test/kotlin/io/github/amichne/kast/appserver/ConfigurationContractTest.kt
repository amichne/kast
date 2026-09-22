package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigurationContractTest {
    @Test
    fun `broker child retains explicitly admitted read limit across empty launchd environment`() {
        val admitted = BrokerChildEnvironment.admit(mapOf("KAST_READ_HOST_REFERENCE_ENTRIES" to "8192"))
        assertTrue(admitted is Refinement.Refined)
        val child = (admitted as Refinement.Refined).value
        assertEquals(listOf("KAST_READ_HOST_REFERENCE_ENTRIES=8192"), child.assignments)
    }

    @Test
    fun `invalid explicit read limit cannot become the child default`() {
        listOf("", "0g", "8G", "999999999999999999999999g").forEach { raw ->
            assertTrue(
                BrokerChildEnvironment.admit(mapOf("KAST_READ_HOST_REFERENCE_ENTRIES" to raw)) is Refinement.Rejected
            )
        }
    }
}
