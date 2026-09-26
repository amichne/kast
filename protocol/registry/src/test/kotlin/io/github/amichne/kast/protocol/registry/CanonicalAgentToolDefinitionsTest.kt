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
    fun `agent tools preserve canonical effects and exact project close policy`() {
        assertEquals(
            listOf(
                CanonicalOperation.WORKSPACE_LIFECYCLE,
                CanonicalOperation.QUERY_RUN,
                CanonicalOperation.SYMBOL_DISCOVER,
                CanonicalOperation.SYMBOL_INSPECT,
                CanonicalOperation.SOURCE_READ,
                CanonicalOperation.DIAGNOSTIC_CHECK,
                CanonicalOperation.CHANGE,
            ),
            CanonicalAgentToolDefinitions.all.map { it.operation.operation },
        )
        assertEquals(
            listOf(
                "workspace_lifecycle",
                "query_symbols",
                "symbol_lookup",
                "symbol_inspect",
                "source_read",
                "check_diagnostics",
                "change",
            ),
            CanonicalAgentToolDefinitions.all.map { it.name.value },
        )
    }

    @Test
    fun `complete tools retain lifecycle authorization`() {
        assertEquals(
            HostedApprovalPolicy.EXACT_PROJECT_CLOSE,
            CanonicalAgentToolDefinitions.workspaceLifecycle.approval,
        )
        assertEquals(HostedApprovalPolicy.NONE, CanonicalAgentToolDefinitions.symbolLookup.approval)
        assertEquals(HostedToolLoading.EAGER, CanonicalAgentToolDefinitions.query.loading)
        assertEquals(
            listOf("query_symbols", "check_diagnostics"),
            CanonicalAgentToolDefinitions.all.filter { it.loading == HostedToolLoading.EAGER }.map { it.name.value },
        )
        assertEquals(HostedApprovalPolicy.NONE, CanonicalAgentToolDefinitions.change.approval)
        assertTrue("walk" in CanonicalAgentToolDefinitions.query.description.value)
        val policy = CanonicalAgentToolDefinitions.policy.text
        assertTrue("Use kast.query_symbols for declaration-name search" in policy)
        assertTrue("Preserve returned symbol references" in policy)
        listOf("kast start", "index sync --", "topology build --", "broker serve").forEach { command ->
            assertTrue(command !in policy)
        }
    }
}
