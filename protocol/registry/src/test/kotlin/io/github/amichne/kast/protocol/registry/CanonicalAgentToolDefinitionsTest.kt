package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `agent tools preserve canonical hosted semantics and lifecycle free policy`() {
        assertEquals(
            listOf(
                CanonicalOperation.SYMBOL_DISCOVER,
                CanonicalOperation.SYMBOL_INSPECT,
                CanonicalOperation.SOURCE_READ,
                CanonicalOperation.RELATION_READ,
                CanonicalOperation.TRAVERSAL_RUN,
                CanonicalOperation.DIAGNOSTIC_CHECK,
                CanonicalOperation.CHANGE_PLAN,
                CanonicalOperation.CHANGE_APPLY,
                CanonicalOperation.CHANGE_RECOVER,
            ),
            CanonicalAgentToolDefinitions.all.map { it.operation.operation },
        )
        assertEquals(
            listOf(
                "symbol_lookup",
                "symbol_inspect",
                "source_read",
                "semantic_query",
                "impact_analyze",
                "diagnostic_check",
                "change_plan",
                "change_apply",
                "change_recover",
            ),
            CanonicalAgentToolDefinitions.all.map { it.name.value },
        )
        assertEquals(HostedApprovalPolicy.NONE, CanonicalAgentToolDefinitions.symbolLookup.approval)
        assertEquals(HostedApprovalPolicy.EXPLICIT, CanonicalAgentToolDefinitions.changeApply.approval)
        assertTrue("exact selector" in CanonicalAgentToolDefinitions.semanticQuery.description.value)
        assertTrue("automatically" in CanonicalAgentToolDefinitions.impactAnalyze.description.value)
        val policy = CanonicalAgentToolDefinitions.policy.text
        assertTrue("compiler-grounded Kotlin source intelligence" in policy)
        assertTrue("Preserve returned selectors" in policy)
        listOf("kast start", "index sync --", "topology build --", "broker serve").forEach { command ->
            assertTrue(command !in policy)
        }
    }
}
