package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class PreferredReadSelectionTest {
    @Test
    fun `legacy read selection serializes only preferred names`() {
        val selected = KastToolSelection.admit("impact_analyze,semantic_query").refined()
        assertEquals("read_relations,traverse_relations", selected.environmentValue)
        assertTrue(selected.admits(CanonicalAgentToolDefinitions.semanticQuery))
        assertTrue(selected.admits(CanonicalAgentToolDefinitions.impactAnalyze))
    }

    @Test
    fun `preferred read selection admits the same canonical definitions`() {
        val selected = KastToolSelection.admit("traverse_relations,read_relations").refined()
        assertEquals("read_relations,traverse_relations", selected.environmentValue)
        assertTrue(selected.admits(CanonicalAgentToolDefinitions.semanticQuery))
        assertTrue(selected.admits(CanonicalAgentToolDefinitions.impactAnalyze))
    }

    @Test
    fun `old and preferred spellings cannot duplicate one selected operation`() {
        assertEquals(
            Refinement.Rejected(KastToolSelectionFailure.DUPLICATE_NAME),
            KastToolSelection.admit("semantic_query,read_relations"),
        )
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> fail("Expected admitted selection: $failure")
        }
}
