package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreferredReadCatalogTest {
    @Test
    fun `catalog has one public compositional semantic read`() {
        assertEquals(5, CanonicalAgentToolDefinitions.all.size)
        assertEquals(
            Refinement.Rejected(AgentToolInputFailure.UNKNOWN),
            CanonicalAgentToolDefinitions.resolveInput("traverse_relations"),
        )
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
                "traverse_relations",
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
