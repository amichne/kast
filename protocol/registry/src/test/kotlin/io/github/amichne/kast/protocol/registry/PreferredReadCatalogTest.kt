package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PreferredReadCatalogTest {
    @Test
    fun `catalog advertises the remaining traversal name with canonical identity`() {
        val expected = mapOf(CanonicalOperation.TRAVERSAL_RUN to "traverse_relations")
        for ((operation, preferred) in expected) {
            assertEquals(
                preferred,
                CanonicalAgentToolDefinitions.all.single { it.operation.operation == operation }.name.value,
            )
        }
        assertFalse(
            CanonicalAgentToolDefinitions.all.any { it.name.value in setOf("semantic_query", "impact_analyze") }
        )
        assertEquals(8, CanonicalAgentToolDefinitions.all.size)
    }

    @Test
    fun `every accepted input has one canonical authority and removed aliases stay closed`() {
        val definitions = CanonicalAgentToolDefinitions.all
        val names = definitions.map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (definition in definitions) {
            for (name in listOf(definition.name)) {
                assertEquals(Refinement.Refined(definition), CanonicalAgentToolDefinitions.resolveInput(name.value))
            }
        }
        for (removed in
            listOf(
                "read_relations",
                "semantic_query",
                "impact_analyze",
                "search_classes",
                "search_functions",
                "search_declarations",
            )) {
            assertEquals(
                Refinement.Rejected(AgentToolInputFailure.UNKNOWN),
                CanonicalAgentToolDefinitions.resolveInput(removed),
            )
        }
        assertEquals(
            Refinement.Rejected(AgentToolInputFailure.UNKNOWN),
            CanonicalAgentToolDefinitions.resolveInput("unknown_read"),
        )
    }
}
