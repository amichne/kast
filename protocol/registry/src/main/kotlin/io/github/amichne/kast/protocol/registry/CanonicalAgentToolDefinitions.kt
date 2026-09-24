package io.github.amichne.kast.protocol.registry

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText

private const val MAX_AGENT_TOOL_NAME_LENGTH = 64
private val AGENT_TOOL_NAME_PATTERN = Regex("[a-z][a-z0-9_]*")
private val AGENT_TOOL_INPUT_NAME_PATTERN = Regex("[a-z][A-Za-z0-9]*")

enum class AgentToolNameFailure {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

/** One lowercase snake-case identity exposed by a hosted-agent projection. */
@JvmInline
value class AgentToolName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<AgentToolName, AgentToolNameFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(AgentToolNameFailure.BLANK)
                raw.length > MAX_AGENT_TOOL_NAME_LENGTH -> Refinement.Rejected(AgentToolNameFailure.TOO_LONG)
                !AGENT_TOOL_NAME_PATTERN.matches(raw) -> Refinement.Rejected(AgentToolNameFailure.INVALID_FORMAT)
                else -> Refinement.Refined(AgentToolName(raw))
            }
    }
}

enum class AgentToolInputNameFailure {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

/** One lower-camel-case property identity in a modeled hosted-tool input. */
@JvmInline
value class AgentToolInputName private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<AgentToolInputName, AgentToolInputNameFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(AgentToolInputNameFailure.BLANK)
                raw.length > MAX_AGENT_TOOL_NAME_LENGTH -> Refinement.Rejected(AgentToolInputNameFailure.TOO_LONG)
                !AGENT_TOOL_INPUT_NAME_PATTERN.matches(raw) ->
                    Refinement.Rejected(AgentToolInputNameFailure.INVALID_FORMAT)
                else -> Refinement.Refined(AgentToolInputName(raw))
            }
    }
}

/** Closed approval requirement retained by every provider projection. */
enum class HostedApprovalPolicy {
    NONE,
    EXPLICIT,
    EXACT_PROJECT_CLOSE,
}

/** Canonical initial-availability policy retained by every hosted projection. */
enum class HostedToolLoading {
    EAGER,
    DEFERRED,
}

/** Canonical semantic metadata for one hosted tool, independent of invocation transport. */
data class AgentToolDefinition(
    val operation: OperationDefinition<*, *, *, *, *>,
    val name: AgentToolName,
    val description: ProtocolText,
    val approval: HostedApprovalPolicy,
    val loading: HostedToolLoading,
    val inputBinding: AgentToolInputBinding = AgentToolInputBinding.Canonical,
    val inputAliases: Set<AgentToolName> = emptySet(),
)

enum class AgentToolPolicyFailure {
    BLANK,
    TOO_LONG,
}

/** Concise canonical Kast selection policy installed only with a qualified tool catalog. */
@JvmInline
value class AgentToolPolicy private constructor(val text: String) {
    companion object {
        fun parse(raw: String): Refinement<AgentToolPolicy, AgentToolPolicyFailure> =
            when {
                raw.isBlank() -> Refinement.Rejected(AgentToolPolicyFailure.BLANK)
                raw.length > MAXIMUM_AGENT_TOOL_POLICY_LENGTH -> Refinement.Rejected(AgentToolPolicyFailure.TOO_LONG)
                else -> Refinement.Refined(AgentToolPolicy(raw))
            }

        private const val MAXIMUM_AGENT_TOOL_POLICY_LENGTH = 2_048
    }
}

/** Sole canonical hosted-agent metadata and policy authority. */
object CanonicalAgentToolDefinitions {
    val workspaceLifecycle =
        tool(
            CanonicalOperationDefinitions.workspaceLifecycle,
            "workspace_lifecycle",
            "Inspect or explicitly control one selected IDEA workspace. Ordinary semantic calls prepare the exact " +
                "project and wait for native readiness automatically; " +
                "do not use this tool as a routine read preflight. " +
                "For a requested control operation, open, present, sync, " +
                "configure an exact task-success refresh rule, " +
                "release or close an exact project. Opening is background best effort. Preserve returned host and " +
                "project identities and reuse request IDs only for the same operation. Pending work requires " +
                "status, not repeated open or sync. Unsaved documents and trust require user " +
                "resolution. Release never closes a project. Borrowed or presented projects are protected from agent " +
                "cleanup; request_user_close requests exact-target controller approval.",
            approval = HostedApprovalPolicy.EXACT_PROJECT_CLOSE,
        )
    val query = facade(PublicToolIdentity.QUERY_SYMBOLS)
    val searchClasses = facade(PublicToolIdentity.SEARCH_CLASSES)
    val searchFunctions = facade(PublicToolIdentity.SEARCH_FUNCTIONS)
    val searchDeclarations = facade(PublicToolIdentity.SEARCH_DECLARATIONS)
    val symbolLookup =
        tool(
            CanonicalOperationDefinitions.symbolDiscover,
            "symbol_lookup",
            "Find bounded Kotlin declaration candidates in the current workspace. Use when an exact " +
                "selector is not already available. Returned candidate selectors are proof-carrying " +
                "identities and must be preserved verbatim.",
        )
    val symbolInspect =
        tool(
            CanonicalOperationDefinitions.symbolInspect,
            "symbol_inspect",
            "Refine a candidate into exact compiler-grounded symbol identity or inspect an existing " +
                "exact selector. Prefer the returned exact selector over reconstructing symbol " +
                "identity from source text.",
        )
    val sourceRead =
        tool(
            CanonicalOperationDefinitions.sourceRead,
            "source_read",
            "Read bounded source and structural context around a candidate, exact symbol, or source " +
                "selector. Prefer this over unrestricted filesystem reads when the required Kotlin " +
                "context is representable through Kast.",
        )
    val semanticQuery =
        tool(
            CanonicalOperationDefinitions.relationRead,
            "read_relations",
            "Read one bounded compiler-grounded semantic relation from an exact selector. Use " +
                "a Kast search tool first when exact identity is not established. Related declarations " +
                "may be in other files or packages; inspect qualifications and resume with the returned continuation.",
            inputAliases = setOf("semantic_query"),
        )
    val impactAnalyze =
        tool(
            CanonicalOperationDefinitions.traversalRun,
            "traverse_relations",
            "Traverse bounded compiler-grounded semantic relations from an exact selector. Returned " +
                "reachability is qualified by depth, scope, relation evidence and execution budgets; " +
                "it does not guarantee breakage analysis or test selection.",
            inputAliases = setOf("impact_analyze"),
        )
    val diagnosticCheck = facade(PublicToolIdentity.CHECK_DIAGNOSTICS)
    val changePlan =
        tool(
            CanonicalOperationDefinitions.changePlan,
            "change_plan",
            "Plan AddDeclaration in one existing authored Kotlin source file without writing source. " +
                "Pass the returned search reference unchanged and preserve the plan identity for apply or recovery.",
        )
    val changeApply =
        tool(
            operation = CanonicalOperationDefinitions.changeApply,
            name = "change_apply",
            description =
                "Apply one stored Kast plan with explicit approval of its exact identity. Verification is " +
                    "part of apply; preserve its verified receipt or qualified unverified/recovery state.",
            approval = HostedApprovalPolicy.EXPLICIT,
        )
    val changeRecover =
        tool(
            CanonicalOperationDefinitions.changeRecover,
            "change_recover",
            "Recover one applied Kast change plan to a known workspace state. Requires explicit " +
                "approval and the exact returned plan identity.",
            HostedApprovalPolicy.EXPLICIT,
        )

    val all: List<AgentToolDefinition> =
        listOf(
            workspaceLifecycle,
            searchClasses,
            searchFunctions,
            searchDeclarations,
            query,
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

    /** Legacy input names remain accepted throughout 0.40.x; removal is no earlier than 0.41.0. */
    fun resolveInput(raw: String): Refinement<AgentToolDefinition, AgentToolInputFailure> {
        val matches = all.filter { definition ->
            definition.name.value == raw || definition.inputAliases.any { it.value == raw }
        }
        return when (matches.size) {
            0 -> Refinement.Rejected(AgentToolInputFailure.UNKNOWN)
            1 -> Refinement.Refined(matches.single())
            else -> Refinement.Rejected(AgentToolInputFailure.AMBIGUOUS)
        }
    }

    val policy: AgentToolPolicy =
        refined(
            AgentToolPolicy.parse(
                """
                Kast provides compiler-grounded Kotlin source intelligence for the current repository.

                Use kast.search_classes for class-like names, kast.search_functions for functions,
                and kast.search_declarations for mixed kinds, properties or type aliases. Exact
                matching is default; request fuzzy explicitly. Apply known directory, package,
                source-set, declaration-kind and exact-name constraints before expensive work.
                Use kast.check_diagnostics for compiler diagnostics, deferred kast.query_symbols
                for enumeration and ordered pipelines, and read_relations for occurrence facts.
                Preserve returned symbol references verbatim, including compact host handles.
                Do not reconstruct handles. Reads may reacquire retained exact handles;
                keep reference_acquisitions. If unavailable, use a scoped search.
                Continuations and source snapshots remain strict. Keep qualified partial facts
                with their limitations; follow recovery guidance and execution_budget.

                Reads require the repository's saved, indexed IntelliJ state. The installed daemon
                prepares the exact workspace automatically and waits for native readiness. Do not
                orchestrate opening, polling, enablement or repair commands for semantic requests.
                Preparation blockers retain their cause and operation identity; continue independent
                work while a blocker needs user action. Installation authorization does not permit
                cache invalidation, forced index synchronization or restarting unrelated IDE sessions.
                A missing semantic response does not authorize replaying a mutation.

                Pass a returned exact reference unchanged to change_plan. Hosted changes support
                AddDeclaration in one existing authored Kotlin file. Planning writes no source.
                Review the stored preview; change_apply and change_recover each require separate
                explicit approval of the exact plan. Apply includes semantic verification.
                Preserve qualified unverified and recovery-required outcomes. A missing response
                does not prove no write occurred; use durable recovery without replaying a plan.
                """
                    .trimIndent()
            )
        )

    private fun facade(identity: PublicToolIdentity): AgentToolDefinition {
        val operation =
            when (identity) {
                PublicToolIdentity.SEARCH_CLASSES,
                PublicToolIdentity.SEARCH_FUNCTIONS,
                PublicToolIdentity.SEARCH_DECLARATIONS,
                PublicToolIdentity.QUERY_SYMBOLS -> CanonicalOperationDefinitions.queryRun
                PublicToolIdentity.CHECK_DIAGNOSTICS -> CanonicalOperationDefinitions.diagnosticCheck
            }
        return AgentToolDefinition(
            operation,
            refined(AgentToolName.parse(identity.toolName)),
            refined(ProtocolText.parse(identity.description)),
            HostedApprovalPolicy.NONE,
            identity.loading,
            AgentToolInputBinding.Facade(identity),
        )
    }

    private fun tool(
        operation: OperationDefinition<*, *, *, *, *>,
        name: String,
        description: String,
        approval: HostedApprovalPolicy = HostedApprovalPolicy.NONE,
        loading: HostedToolLoading = HostedToolLoading.DEFERRED,
        inputAliases: Set<String> = emptySet(),
    ): AgentToolDefinition =
        AgentToolDefinition(
            operation,
            refined(AgentToolName.parse(name)),
            refined(ProtocolText.parse(description)),
            approval,
            loading,
            inputAliases = inputAliases.mapTo(linkedSetOf()) { refined(AgentToolName.parse(it)) },
        )

    private fun <Value, Failure> refined(value: Refinement<Value, Failure>): Value =
        when (value) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> error("Invalid canonical hosted-agent metadata")
        }
}

/** The admitted public presentation remains bound even when several tools use one operation. */
sealed interface AgentToolInputBinding {
    data object Canonical : AgentToolInputBinding

    data class Facade(val identity: PublicToolIdentity) : AgentToolInputBinding
}

/** Closed failure at the canonical hosted-tool input-name boundary. */
enum class AgentToolInputFailure {
    UNKNOWN,
    AMBIGUOUS,
}
