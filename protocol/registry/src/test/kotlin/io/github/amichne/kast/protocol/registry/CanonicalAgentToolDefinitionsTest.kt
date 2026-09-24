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
                CanonicalOperation.QUERY_RUN,
                CanonicalOperation.QUERY_RUN,
                CanonicalOperation.QUERY_RUN,
                CanonicalOperation.SYMBOL_DISCOVER,
                CanonicalOperation.SYMBOL_INSPECT,
                CanonicalOperation.SOURCE_READ,
                CanonicalOperation.RELATION_READ,
                CanonicalOperation.TRAVERSAL_RUN,
                CanonicalOperation.DIAGNOSTIC_CHECK,
                CanonicalOperation.CHANGE,
            ),
            CanonicalAgentToolDefinitions.all.map { it.operation.operation },
        )
        assertEquals(
            listOf(
                "workspace_lifecycle",
                "search_classes",
                "search_functions",
                "search_declarations",
                "query_symbols",
                "symbol_lookup",
                "symbol_inspect",
                "source_read",
                "read_relations",
                "traverse_relations",
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
        assertEquals(HostedToolLoading.DEFERRED, CanonicalAgentToolDefinitions.query.loading)
        assertEquals(
            listOf("search_classes", "search_functions", "search_declarations", "check_diagnostics"),
            CanonicalAgentToolDefinitions.all.filter { it.loading == HostedToolLoading.EAGER }.map { it.name.value },
        )
        assertEquals(HostedApprovalPolicy.NONE, CanonicalAgentToolDefinitions.change.approval)
        assertTrue("exact selector" in CanonicalAgentToolDefinitions.semanticQuery.description.value)
        assertTrue("reachability is qualified" in CanonicalAgentToolDefinitions.impactAnalyze.description.value)
        assertTrue("does not guarantee breakage" in CanonicalAgentToolDefinitions.impactAnalyze.description.value)
        val policy = CanonicalAgentToolDefinitions.policy.text
        assertTrue("compiler-grounded Kotlin source intelligence" in policy)
        assertTrue("Preserve returned symbol references" in policy)
        listOf("kast start", "index sync --", "topology build --", "broker serve").forEach { command ->
            assertTrue(command !in policy)
        }
    }
}
