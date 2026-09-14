package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PreferredReadCatalogTest {
    @Test
    fun `catalog advertises preferred relation names with unchanged canonical identities`() {
        val expected =
            mapOf(
                CanonicalOperation.RELATION_READ to "read_relations",
                CanonicalOperation.TRAVERSAL_RUN to "traverse_relations",
            )
        for ((operation, preferred) in expected) {
            assertEquals(
                preferred,
                CanonicalAgentToolDefinitions.all.single { it.operation.operation == operation }.name.value,
            )
        }
        assertFalse(
            CanonicalAgentToolDefinitions.all.any { it.name.value in setOf("semantic_query", "impact_analyze") }
        )
        assertEquals(13, CanonicalAgentToolDefinitions.all.size)
        assertEquals(11, CanonicalAgentToolDefinitions.defaultAppServerTools.size)
    }
}
