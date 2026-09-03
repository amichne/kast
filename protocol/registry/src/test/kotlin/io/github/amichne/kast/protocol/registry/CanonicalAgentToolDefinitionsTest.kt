package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CanonicalAgentToolDefinitionsTest {
    @Test
    fun `agent tool and input identities refine boundary strings`() {
        assertEquals(
            Refinement.Rejected(AgentToolNameFailure.BLANK),
            AgentToolName.parse(""),
        )
        assertEquals(
            Refinement.Rejected(AgentToolNameFailure.TOO_LONG),
            AgentToolName.parse("a".repeat(65)),
        )
        assertEquals(
            Refinement.Rejected(AgentToolNameFailure.INVALID_FORMAT),
            AgentToolName.parse("Symbol-Resolve"),
        )
        assertEquals(
            Refinement.Rejected(AgentToolInputNameFailure.INVALID_FORMAT),
            AgentToolInputName.parse("exact_selector"),
        )
        assertEquals(
            "exactSelector",
            (AgentToolInputName.parse("exactSelector") as Refinement.Refined).value.value,
        )
    }

    @Test
    fun `agent tools preserve the canonical Kast operation workflows and inputs`() {
        val symbol = CanonicalAgentToolDefinitions.symbolInspect
        val relation = CanonicalAgentToolDefinitions.relationRead

        assertEquals(
            listOf(
                CanonicalOperation.SYMBOL_DISCOVER,
                CanonicalOperation.SYMBOL_INSPECT,
            ),
            symbol.execution.operations.map { it.operation },
        )
        assertEquals(
            listOf(CanonicalOperation.RELATION_READ),
            relation.execution.operations.map { it.operation },
        )
        assertEquals(CanonicalOperation.SYMBOL_INSPECT, symbol.execution.output.operation)
        assertEquals(CanonicalOperation.RELATION_READ, relation.execution.output.operation)
        assertEquals(
            listOf("symbol_inspect", "relation_read"),
            CanonicalAgentToolDefinitions.all.map { it.name.value },
        )

        val symbolInput = assertInstanceOf(
            AgentToolInput.ExactSymbolName::class.java,
            symbol.input,
        )
        val relationInput = assertInstanceOf(
            AgentToolInput.ExactRelation::class.java,
            relation.input,
        )
        assertEquals("query", symbolInput.query.name.value)
        assertEquals("exactSelector", relationInput.exactSelector.name.value)
        assertEquals("relation", relationInput.relation.name.value)
    }
}
