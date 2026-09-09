package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigurationContractTest {
    @Test
    fun `broker child retains explicitly admitted heap across empty launchd environment`() {
        val admitted = BrokerChildEnvironment.admit(mapOf("KAST_INDEXER_MAX_HEAP" to "8g"))
        assertTrue(admitted is Refinement.Refined)
        val child = (admitted as Refinement.Refined).value
        assertEquals(listOf("KAST_INDEXER_MAX_HEAP=8192m"), child.assignments)
    }

    @Test
    fun `invalid explicit heap cannot become the child default`() {
        listOf("", "0g", "8G", "999999999999999999999999g").forEach { raw ->
            assertTrue(BrokerChildEnvironment.admit(mapOf("KAST_INDEXER_MAX_HEAP" to raw)) is Refinement.Rejected)
        }
    }
}
