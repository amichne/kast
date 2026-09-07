package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText

private const val MAX_AGENT_TOOL_NAME_LENGTH = 64
private val AGENT_TOOL_NAME_PATTERN = Regex("[a-z][a-z0-9_]*")
private val AGENT_TOOL_INPUT_NAME_PATTERN = Regex("[a-z][A-Za-z0-9]*")

enum class AgentToolNameFailure { BLANK, TOO_LONG, INVALID_FORMAT }

/** One lowercase snake-case identity exposed by a hosted-agent projection. */
@JvmInline
value class AgentToolName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<AgentToolName, AgentToolNameFailure> = when {
            raw.isBlank() -> Refinement.Rejected(AgentToolNameFailure.BLANK)
            raw.length > MAX_AGENT_TOOL_NAME_LENGTH ->
                Refinement.Rejected(AgentToolNameFailure.TOO_LONG)
            !AGENT_TOOL_NAME_PATTERN.matches(raw) ->
                Refinement.Rejected(AgentToolNameFailure.INVALID_FORMAT)
            else -> Refinement.Refined(AgentToolName(raw))
        }
    }
}

enum class AgentToolInputNameFailure { BLANK, TOO_LONG, INVALID_FORMAT }

/** One lower-camel-case property identity in a modeled hosted-tool input. */
@JvmInline
value class AgentToolInputName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<AgentToolInputName, AgentToolInputNameFailure> = when {
            raw.isBlank() -> Refinement.Rejected(AgentToolInputNameFailure.BLANK)
            raw.length > MAX_AGENT_TOOL_NAME_LENGTH ->
                Refinement.Rejected(AgentToolInputNameFailure.TOO_LONG)
            !AGENT_TOOL_INPUT_NAME_PATTERN.matches(raw) ->
                Refinement.Rejected(AgentToolInputNameFailure.INVALID_FORMAT)
            else -> Refinement.Refined(AgentToolInputName(raw))
        }
    }
}

/** Closed approval requirement retained by every provider projection. */
enum class HostedApprovalPolicy { NONE, EXPLICIT }

/** Canonical semantic metadata for one hosted tool, independent of invocation transport. */
data class AgentToolDefinition(
    val operation: OperationDefinition<*, *, *, *, *>,
    val name: AgentToolName,
    val description: ProtocolText,
    val approval: HostedApprovalPolicy,
)

enum class AgentToolPolicyFailure { BLANK, TOO_LONG }

/** Concise canonical Kast selection policy installed only with a qualified tool catalog. */
@JvmInline
value class AgentToolPolicy private constructor(val text: String) {
    companion object {
        fun parse(raw: String): Refinement<AgentToolPolicy, AgentToolPolicyFailure> = when {
            raw.isBlank() -> Refinement.Rejected(AgentToolPolicyFailure.BLANK)
            raw.length > MAXIMUM_AGENT_TOOL_POLICY_LENGTH ->
                Refinement.Rejected(AgentToolPolicyFailure.TOO_LONG)
            else -> Refinement.Refined(AgentToolPolicy(raw))
        }

        private const val MAXIMUM_AGENT_TOOL_POLICY_LENGTH = 2_048
    }
}

/** Sole canonical hosted-agent metadata and policy authority. */
object CanonicalAgentToolDefinitions {
    val symbolLookup = tool(
        CanonicalOperationDefinitions.symbolDiscover,
        "symbol_lookup",
        "Find bounded Kotlin declaration candidates in the current workspace. Use when an exact " +
            "selector is not already available. Returned candidate selectors are proof-carrying " +
            "identities and must be preserved verbatim.",
    )
    val symbolInspect = tool(
        CanonicalOperationDefinitions.symbolInspect,
        "symbol_inspect",
        "Refine a candidate into exact compiler-grounded symbol identity or inspect an existing " +
            "exact selector. Prefer the returned exact selector over reconstructing symbol " +
            "identity from source text.",
    )
    val sourceRead = tool(
        CanonicalOperationDefinitions.sourceRead,
        "source_read",
        "Read bounded source and structural context around a candidate, exact symbol, or source " +
            "selector. Prefer this over unrestricted filesystem reads when the required Kotlin " +
            "context is representable through Kast.",
    )
    val semanticQuery = tool(
        CanonicalOperationDefinitions.relationRead,
        "semantic_query",
        "Read one bounded compiler-grounded semantic relation from an exact selector. Use " +
            "discovery and inspection first when exact identity is not established.",
    )
    val impactAnalyze = tool(
        CanonicalOperationDefinitions.traversalRun,
        "impact_analyze",
        "Perform bounded transitive semantic traversal from an exact selector. Required topology " +
            "is acquired automatically for the current generation; the caller does not prepare " +
            "it separately.",
    )
    val diagnosticCheck = tool(
        CanonicalOperationDefinitions.diagnosticCheck,
        "diagnostic_check",
        "Check bounded compiler diagnostics in the current workspace or an explicit source " +
            "scope. Prefer this when compiler-grounded diagnostic identity matters.",
    )
    val changePlan = tool(
        CanonicalOperationDefinitions.changePlan,
        "change_plan",
        "Derive a bounded change plan from an exact target without writing source. Preserve the " +
            "returned plan identity for apply or recovery.",
        HostedApprovalPolicy.EXPLICIT,
    )
    val changeApply = tool(
        CanonicalOperationDefinitions.changeApply,
        "change_apply",
        "Apply one previously derived Kast change plan and return its verified receipt. Requires " +
            "explicit approval and the exact returned plan identity.",
        HostedApprovalPolicy.EXPLICIT,
    )
    val changeRecover = tool(
        CanonicalOperationDefinitions.changeRecover,
        "change_recover",
        "Recover one applied Kast change plan to a known workspace state. Requires explicit " +
            "approval and the exact returned plan identity.",
        HostedApprovalPolicy.EXPLICIT,
    )

    val all: List<AgentToolDefinition> = listOf(
        symbolLookup,
        symbolInspect,
        sourceRead,
        semanticQuery,
        impactAnalyze,
        diagnosticCheck,
        changePlan,
        changeApply,
        changeRecover,
    )

    val policy: AgentToolPolicy = refined(
        AgentToolPolicy.parse(
            """
            Kast provides compiler-grounded Kotlin source intelligence for the current repository.

            Prefer Kast over direct filesystem search or read when the question requires symbol
            identity, bounded source context, semantic relations, traversal, diagnostics, or change
            planning. Use discovery to obtain candidates and inspection to establish exact symbol
            identity. Preserve returned selectors rather than reconstructing identities from source
            text.

            Kast operations establish their own runtime, workspace, and required derived evidence.
            Do not invoke lifecycle, index synchronization, or topology preparation as prerequisites.
            """.trimIndent(),
        ),
    )

    private fun tool(
        operation: OperationDefinition<*, *, *, *, *>,
        name: String,
        description: String,
        approval: HostedApprovalPolicy = HostedApprovalPolicy.NONE,
    ): AgentToolDefinition = AgentToolDefinition(
        operation,
        refined(AgentToolName.parse(name)),
        refined(ProtocolText.parse(description)),
        approval,
    )

    private fun <Value, Failure> refined(value: Refinement<Value, Failure>): Value = when (value) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> error("Invalid canonical hosted-agent metadata")
    }
}
