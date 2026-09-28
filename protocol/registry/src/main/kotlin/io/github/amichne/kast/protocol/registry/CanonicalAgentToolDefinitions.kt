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
            SupportToolIdentity.WORKSPACE_LIFECYCLE.toolName,
            SupportToolIdentity.WORKSPACE_LIFECYCLE.description,
            approval = HostedApprovalPolicy.EXACT_PROJECT_CLOSE,
        )
    private val publicTools = PublicToolIdentity.entries.associateWith(::facade)
    val query = publicTools.getValue(PublicToolIdentity.QUERY_SYMBOLS)
    val sourceRead = publicTools.getValue(PublicToolIdentity.READ_SOURCE)
    val diagnosticCheck = publicTools.getValue(PublicToolIdentity.CHECK_DIAGNOSTICS)
    val addDeclaration = publicTools.getValue(PublicToolIdentity.ADD_DECLARATION)
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
        listOf(workspaceLifecycle) + PublicToolIdentity.entries.map(publicTools::getValue)

    /** Only published tool names are accepted. */
    fun resolveInput(raw: String): Refinement<AgentToolDefinition, AgentToolInputFailure> {
        val match = all.singleOrNull { it.name.value == raw }
        return if (match == null) Refinement.Rejected(AgentToolInputFailure.UNKNOWN) else Refinement.Refined(match)
    }

    val policy: AgentToolPolicy =
        refined(
            AgentToolPolicy.parse(
                """
                Use kast.query_symbols for declaration-name search, enumeration, returned exact
                references, and ordered pipelines. Restrict declaration kinds and scope before
                expensive work; exact matching is default and fuzzy requires explicit opt-in.
                Use read_source for bounded source context and
                query_symbols with occurrence output for relation facts and its walk step for
                multi-step reachability. Use kast.check_diagnostics
                for compiler diagnostics.
                Preserve returned symbol references verbatim, including compact host handles.
                Do not reconstruct handles. Reads may reacquire retained exact handles;
                keep reference_acquisitions. If unavailable, use a scoped search.
                Continuations and source snapshots remain strict. Keep qualified partial facts
                with their limitations; follow recovery guidance and executionBudget.

                Reads require the repository's saved, indexed IntelliJ state. The installed daemon
                prepares the exact workspace automatically and waits for native readiness. Do not
                orchestrate opening, polling, enablement or repair commands for semantic requests.
                Preparation blockers retain their cause and operation identity; continue independent
                work while a blocker needs user action. Installation authorization does not permit
                cache invalidation, forced index synchronization or restarting unrelated IDE sessions.
                A missing semantic response does not authorize replaying a mutation.

                Pass a returned exact reference unchanged to add_declaration. Hosted changes support
                AddDeclaration in one existing authored Kotlin file. The single call plans, applies
                and verifies the change. Keep the returned receipt or finite failure and recovery
                evidence. A missing response does not prove no write occurred; never replay a plan.
                """
                    .trimIndent()
            )
        )

    private fun facade(identity: PublicToolIdentity): AgentToolDefinition {
        val operation =
            when (identity) {
                PublicToolIdentity.QUERY_SYMBOLS -> CanonicalOperationDefinitions.queryRun
                PublicToolIdentity.READ_SOURCE -> CanonicalOperationDefinitions.sourceRead
                PublicToolIdentity.CHECK_DIAGNOSTICS -> CanonicalOperationDefinitions.diagnosticCheck
                PublicToolIdentity.ADD_DECLARATION -> CanonicalOperationDefinitions.change
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
    ): AgentToolDefinition =
        AgentToolDefinition(
            operation,
            refined(AgentToolName.parse(name)),
            refined(ProtocolText.parse(description)),
            approval,
            loading,
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
    UNKNOWN
}
