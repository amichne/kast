package support.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

internal class Kvp038ImplementationBaselineTest {
    @Test
    fun `renewed predecessor head cannot replace the graph ready frontier`() {
        val renewedPredecessorHead = DeliveryGeneration("f".repeat(40))

        assertNotEquals(renewedPredecessorHead, kvp038ImplementationBaseline())
        assertEquals(
            defaultIsolatedRuntimeRetirementBatch().readyFrontier,
            kvp038ImplementationBaseline(),
        )
    }
}
