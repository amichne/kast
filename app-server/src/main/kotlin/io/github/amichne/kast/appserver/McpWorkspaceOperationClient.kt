package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.appserver.ide.HostedApprovalAssertion
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.ide.HostedPlanIdentity
import io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.runtime.JsonLineWorkspacePreparationObserver
import io.github.amichne.kast.appserver.runtime.PreparedWorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.appserver.runtime.WorkspacePreparations
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/** One MCP session owns both eager preparation and later semantic demand for the same root. */
class McpWorkspaceOperationClient
internal constructor(
    private val delegate: DaemonOperationClient,
    private val preparations: WorkspacePreparations?,
    private val lifecycle: WorkspaceLifecycleClient = WorkspaceLifecycleClient.Unavailable,
) : DaemonOperationClient by delegate {
    /** Explicit session lifecycle effect; semantic reads never call this path. */
    fun lifecycle(request: WorkspaceLifecycleRequest): IdeLifecycleResult = lifecycle.execute(request, "kast-mcp")

    fun start(root: CanonicalRoot): Refinement<Unit, DaemonOperationFailure> {
        val owner =
            preparations
                ?: return Refinement.Rejected(DaemonOperationFailure.Host(ExistingIdeFailure.CONFIGURATION_REJECTED))
        return when (val admission = owner.prepare(root)) {
            is Refinement.Refined -> Refinement.Refined(Unit)
            is Refinement.Rejected -> Refinement.Rejected(DaemonOperationFailure.Preparation(admission.failure))
        }
    }
}

/** Session-owned MCP adapter. The existing IDEA lifecycle and compiler host retain semantic authority. */
@Suppress("LongMethod")
fun mcpWorkspaceOperationClient(home: Path, environment: Map<String, String>): McpWorkspaceOperationClient {
    val sources =
        when (val saved = InstalledSavedConfigurationIngress.load(environment)) {
            is SavedConfigurationIngress.Loaded -> saved.sources
            is SavedConfigurationIngress.Rejected -> return rejectedMcpConfiguration()
        }
    val config =
        when (val resolved = ResolvedKastConfiguration.resolve(sources)) {
            is Refinement.Refined -> resolved.value
            is Refinement.Rejected -> return rejectedMcpConfiguration()
        }
    val lifecycle = installedWorkspaceLifecycleClient(home, config.selectedIdeHome)
    val native = ExistingIdeSocketClient(home, config.readLimits)
    val dispatcher = Dispatchers.IO
    val preparations =
        WorkspacePreparations(
            CoroutineScope(SupervisorJob() + dispatcher),
            { request -> runInterruptible(dispatcher) { lifecycle.execute(request, "kast-mcp") } },
            observer = JsonLineWorkspacePreparationObserver(System.err),
        )
    val demand =
        PreparedWorkspaceDemand(
            preparations,
            {
                runInterruptible(dispatcher) {
                    lifecycle.execute(
                        io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.Inspect,
                        "kast-mcp",
                    )
                }
            },
            { workspace, operation -> runInterruptible(dispatcher) { native.queryPrepared(workspace, operation) } },
        )
    val delegate = DaemonOperationClient { root, call ->
        val operation =
            when (val admitted = mcpOperation(call)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return@DaemonOperationClient DaemonOperationResult.Rejected(
                        DaemonOperationClientRejection.Server(DaemonOperationFailure.Host(admitted.failure))
                    )
            }
        when (val result = runBlocking { demand.query(root, operation) }) {
            is WorkspaceDemandResult.Native -> mcpResult(result.exchange)
            is WorkspaceDemandResult.Rejected ->
                DaemonOperationResult.Rejected(
                    DaemonOperationClientRejection.Server(
                        when (val failure = result.failure) {
                            is WorkspaceDemandFailure.Admission -> DaemonOperationFailure.Preparation(failure.failure)
                            is WorkspaceDemandFailure.Operation ->
                                DaemonOperationFailure.Workspace(failure.id.toString(), failure.cause)
                        }
                    )
                )
        }
    }
    return McpWorkspaceOperationClient(delegate, preparations, lifecycle)
}

private fun rejectedMcpConfiguration() =
    McpWorkspaceOperationClient(
        DaemonOperationClient { _, _ ->
            DaemonOperationResult.Rejected(
                DaemonOperationClientRejection.Server(
                    DaemonOperationFailure.Host(ExistingIdeFailure.CONFIGURATION_REJECTED)
                )
            )
        },
        null,
        WorkspaceLifecycleClient.Unavailable,
    )

private val mcpPreparers = canonicalCliRequestPreparers()

@Suppress("CognitiveComplexMethod", "LongMethod")
private fun mcpOperation(call: DaemonOperationCall): Refinement<ExistingIdeOperation, ExistingIdeFailure> {
    val prepared =
        when (call) {
            is DaemonOperationCall.PublicTool ->
                when (val canonical = call.tool.canonical) {
                    is PublicToolCanonical.Query -> mcpPreparers.queryRun.prepare(canonical.request)
                    is PublicToolCanonical.Diagnostics -> mcpPreparers.diagnosticCheck.prepare(canonical.request)
                }
            is DaemonOperationCall.Canonical ->
                when (val read = call.read) {
                    is DaemonCanonicalRead.SourceRead -> mcpPreparers.sourceRead.prepare(read.request)
                }
            is DaemonOperationCall.Change ->
                when (val action = call.action) {
                    is DaemonChangeAction.Plan -> mcpPreparers.changePlan.prepare(action.request)
                    is DaemonChangeAction.Prepare -> {
                        val identity = HostedPlanIdentity.parse(action.identity)
                        return when (identity) {
                            is Refinement.Refined ->
                                Refinement.Refined(
                                    ExistingIdeOperation.ApprovalPreparation(action.kind, identity.value)
                                )
                            is Refinement.Rejected -> identity
                        }
                    }
                    is DaemonChangeAction.Apply -> mcpPreparers.changeApply.prepare(action.request)
                    is DaemonChangeAction.Recover -> mcpPreparers.changeRecover.prepare(action.request)
                }
        }
    val request =
        when (prepared) {
            is OperationPreparation.Prepared -> prepared.request
            is OperationPreparation.Rejected -> return Refinement.Rejected(ExistingIdeFailure.INVALID_REQUEST)
        }
    return when (call) {
        is DaemonOperationCall.Change ->
            when (val action = call.action) {
                is DaemonChangeAction.Plan -> ExistingIdeOperation.Plan.admit(request)
                is DaemonChangeAction.Prepare -> error("Preparation returned before request encoding")
                is DaemonChangeAction.Apply ->
                    mcpApproved(
                        request,
                        HostedMutationOperation.CHANGE_APPLY,
                        action.request.planIdentity.value,
                        action.assertion,
                    )
                is DaemonChangeAction.Recover ->
                    mcpApproved(
                        request,
                        HostedMutationOperation.CHANGE_RECOVER,
                        action.request.planIdentity.value,
                        action.assertion,
                    )
            }
        else -> ExistingIdeOperation.Read.admit(request)
    }
}

private fun mcpApproved(
    request: io.github.amichne.kast.protocol.wire.presentation.PreparedOperationRequest,
    kind: HostedMutationOperation,
    identity: String,
    assertion: String,
): Refinement<ExistingIdeOperation, ExistingIdeFailure> {
    val plan =
        when (val parsed = HostedPlanIdentity.parse(identity)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    val grant =
        when (val parsed = HostedApprovalAssertion.parse(assertion)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    return ExistingIdeOperation.ApprovedMutation.admit(request, kind, plan, grant)
}

private fun mcpResult(exchange: ExistingIdeExchange): DaemonOperationResult =
    when (exchange) {
        is ExistingIdeExchange.Received -> DaemonOperationResult.Complete(exchange.document)
        is ExistingIdeExchange.HostRejected -> DaemonOperationResult.OperationRejected(exchange.document)
        is ExistingIdeExchange.Rejected ->
            DaemonOperationResult.Rejected(
                DaemonOperationClientRejection.Server(DaemonOperationFailure.Host(exchange.failure))
            )
        is ExistingIdeExchange.Semantic ->
            when (val outcome = exchange.outcome) {
                is ProjectedOperationOutcome.Complete -> DaemonOperationResult.Complete(outcome.document)
                is ProjectedOperationOutcome.Qualified -> DaemonOperationResult.Qualified(outcome.document)
                is ProjectedOperationOutcome.Rejected -> DaemonOperationResult.OperationRejected(outcome.document)
            }
    }
