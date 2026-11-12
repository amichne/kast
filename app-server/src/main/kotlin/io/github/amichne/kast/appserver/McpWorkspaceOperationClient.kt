package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.appserver.ide.ExistingIdeStage
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.ide.HostedPlanIdentity
import io.github.amichne.kast.appserver.ide.WorkspaceLifecycleClient
import io.github.amichne.kast.appserver.ide.observeExistingIdeStage
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.runtime.ExistingWorkspaceDemand
import io.github.amichne.kast.appserver.runtime.JsonLineWorkspacePreparationObserver
import io.github.amichne.kast.appserver.runtime.PreparedWorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationOutcome
import io.github.amichne.kast.appserver.runtime.WorkspacePreparations
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.InstalledSavedConfigurationIngress
import io.github.amichne.kast.distribution.managed.InstalledWorkspaceConfigurationIngress
import io.github.amichne.kast.distribution.managed.SavedConfigurationIngress
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleResult
import io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/** The caller owns preparation jobs and closes them when its request or session ends. */
class McpWorkspaceOperationClient
internal constructor(
    private val delegate: DaemonOperationClient,
    private val preparations: WorkspacePreparations?,
    private val lifecycle: WorkspaceLifecycleClient = WorkspaceLifecycleClient.Unavailable,
    private val preparationWait: io.github.amichne.kast.kernel.ElapsedTimeAllowance =
        io.github.amichne.kast.kernel.ElapsedTimeAllowance.Bounded(
            io.github.amichne.kast.protocol.contract.WorkspacePreparationBudget.limit
        ),
    private val preparedDemand: PreparedWorkspaceDemand? = null,
) : DaemonOperationClient by delegate, AutoCloseable {
    /** The returned client retains this exact ready incarnation and cannot prepare a replacement. */
    suspend fun prepared(root: CanonicalRoot): Refinement<DaemonOperationClient, DaemonOperationFailure> =
        when (val demand = preparedDemand) {
            null -> Refinement.Rejected(DaemonOperationFailure.Host(ExistingIdeFailure.CONFIGURATION_REJECTED))
            else ->
                when (val ready = demand.prepare(root)) {
                    is Refinement.Rejected -> Refinement.Rejected(ready.failure.daemonFailure())
                    is Refinement.Refined -> Refinement.Refined(mcpDemandClient(ready.value))
                }
        }

    override fun close() {
        val interrupted = Thread.interrupted()
        try {
            runBlocking(NonCancellable) { preparations?.close() }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Explicit session lifecycle effect; semantic reads never call this path. */
    fun lifecycle(request: WorkspaceLifecycleRequest): IdeLifecycleResult = lifecycle.execute(request, "kast-mcp")

    fun preparationAllowance(): Refinement<io.github.amichne.kast.kernel.ElapsedTimeAllowance, DaemonOperationFailure> =
        if (preparations == null)
            Refinement.Rejected(DaemonOperationFailure.Host(ExistingIdeFailure.CONFIGURATION_REJECTED))
        else Refinement.Refined(preparationWait)

    /** Submit the native operation synchronously; the persistent IDE owns it after this client exits. */
    fun beginPreparation(root: CanonicalRoot): Refinement<IdeLifecycleResult, DaemonOperationFailure> {
        if (preparations == null)
            return Refinement.Rejected(DaemonOperationFailure.Host(ExistingIdeFailure.CONFIGURATION_REJECTED))
        val requestId = java.util.UUID.randomUUID().toString()
        val result =
            lifecycle.execute(
                WorkspaceLifecycleRequest.Open(root.path.toString(), requestId, preparationWait.protocolMillis()),
                "kast-hook",
            )
        return if (result is IdeLifecycleResult.Pending && result.requestId != requestId)
            Refinement.Rejected(DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.RESPONSE_REJECTED))
        else Refinement.Refined(result)
    }

    fun start(root: CanonicalRoot): Refinement<Unit, DaemonOperationFailure> {
        val owner =
            preparations
                ?: return Refinement.Rejected(DaemonOperationFailure.Host(ExistingIdeFailure.CONFIGURATION_REJECTED))
        return when (val admission = owner.prepare(root)) {
            is Refinement.Refined -> Refinement.Refined(Unit)
            is Refinement.Rejected -> Refinement.Rejected(DaemonOperationFailure.Preparation(admission.failure))
        }
    }

    /** Health waits for the existing preparation owner before it inspects native readiness. */
    suspend fun awaitPreparation(root: CanonicalRoot): Refinement<Unit, DaemonOperationFailure> {
        val owner =
            preparations
                ?: return Refinement.Rejected(DaemonOperationFailure.Host(ExistingIdeFailure.CONFIGURATION_REJECTED))
        val entry =
            when (val admitted = owner.prepare(root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(DaemonOperationFailure.Preparation(admitted.failure))
            }
        return when (val outcome = entry.state.filterIsInstance<WorkspacePreparationOutcome.Terminal>().first()) {
            is WorkspacePreparationOutcome.Complete -> Refinement.Refined(Unit)
            is WorkspacePreparationOutcome.Rejected ->
                Refinement.Rejected(DaemonOperationFailure.Preparation(outcome.failure))
            is WorkspacePreparationOutcome.Blocked ->
                Refinement.Rejected(
                    DaemonOperationFailure.Workspace(
                        entry.id.value.toString(),
                        WorkspaceDemandCause.Lifecycle(outcome.reason),
                    )
                )
        }
    }
}

/** Session-owned MCP adapter. The existing IDEA lifecycle and compiler host retain semantic authority. */
fun mcpWorkspaceOperationClient(home: Path, environment: Map<String, String>): McpWorkspaceOperationClient =
    configuredMcpWorkspaceOperationClient(home, InstalledSavedConfigurationIngress.load(environment))

/** Explicit workspace waiting includes that physical workspace's saved configuration overlay. */
fun mcpWorkspaceOperationClient(
    home: Path,
    environment: Map<String, String>,
    installation: Path,
    workspace: CanonicalRoot,
): McpWorkspaceOperationClient =
    configuredMcpWorkspaceOperationClient(
        home,
        InstalledWorkspaceConfigurationIngress.load(installation, workspace.path, environment),
    )

@Suppress("LongMethod")
private fun configuredMcpWorkspaceOperationClient(
    home: Path,
    saved: SavedConfigurationIngress,
): McpWorkspaceOperationClient {
    val sources =
        when (saved) {
            is SavedConfigurationIngress.Loaded -> saved.sources
            is SavedConfigurationIngress.Rejected -> return rejectedMcpConfiguration()
        }
    val config =
        when (val resolved = ResolvedKastConfiguration.resolve(sources)) {
            is Refinement.Refined -> resolved.value
            is Refinement.Rejected -> return rejectedMcpConfiguration()
        }
    val lifecycle = installedWorkspaceLifecycleClient(home, config.selectedIdeHome, config.managedHeadlessState)
    val native = ExistingIdeSocketClient(home, config)
    val dispatcher = Dispatchers.IO
    val preparations =
        WorkspacePreparations(
            CoroutineScope(SupervisorJob() + dispatcher),
            { request ->
                runInterruptible(dispatcher) {
                    observeExistingIdeStage(ExistingIdeStage.LIFECYCLE_PREPARATION) {
                        lifecycle.execute(request, "kast-mcp")
                    }
                }
            },
            observer = JsonLineWorkspacePreparationObserver(System.err, config.debug),
            budget = config.workspacePreparationWait.duration,
        )
    val demand =
        PreparedWorkspaceDemand(
            preparations,
            {
                runInterruptible(dispatcher) {
                    observeExistingIdeStage(ExistingIdeStage.LIFECYCLE_INSPECTION) {
                        lifecycle.execute(
                            io.github.amichne.kast.protocol.contract.WorkspaceLifecycleRequest.Inspect,
                            "kast-mcp",
                        )
                    }
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
        val selected =
            when (call.workspaceAdmission()) {
                WorkspaceOperationAdmission.PREPARE -> demand
                WorkspaceOperationAdmission.EXISTING_AUTHORITY -> ExistingWorkspaceDemand(native, dispatcher)
            }
        runBlocking { selected.query(root, operation) }.daemonResult()
    }
    return McpWorkspaceOperationClient(delegate, preparations, lifecycle, config.workspacePreparationWait, demand)
}

private fun mcpDemandClient(demand: WorkspaceDemand): DaemonOperationClient = DaemonOperationClient { root, call ->
    when (val operation = mcpOperation(call)) {
        is Refinement.Rejected ->
            DaemonOperationResult.Rejected(
                DaemonOperationClientRejection.Server(DaemonOperationFailure.Host(operation.failure))
            )
        is Refinement.Refined -> runBlocking { demand.query(root, operation.value) }.daemonResult()
    }
}

private fun WorkspaceDemandResult.daemonResult(): DaemonOperationResult =
    when (this) {
        is WorkspaceDemandResult.Native -> mcpResult(exchange)
        is WorkspaceDemandResult.Rejected ->
            DaemonOperationResult.Rejected(DaemonOperationClientRejection.Server(failure.daemonFailure()))
    }

private fun WorkspaceDemandFailure.daemonFailure(): DaemonOperationFailure =
    when (this) {
        is WorkspaceDemandFailure.Admission -> DaemonOperationFailure.Preparation(failure)
        is WorkspaceDemandFailure.Operation -> DaemonOperationFailure.Workspace(id.toString(), cause)
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
internal fun mcpOperation(call: DaemonOperationCall): Refinement<ExistingIdeOperation, ExistingIdeFailure> {
    val prepared =
        when (call) {
            is DaemonOperationCall.PublicTool ->
                when (val canonical = call.tool.canonical) {
                    is PublicToolCanonical.Query -> mcpPreparers.queryRun.prepare(canonical.request)
                    is PublicToolCanonical.Source -> mcpPreparers.sourceRead.prepare(canonical.request)
                    is PublicToolCanonical.Diagnostics -> mcpPreparers.diagnosticCheck.prepare(canonical.request)
                    is PublicToolCanonical.Change ->
                        return Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED)
                }
            is DaemonOperationCall.Canonical ->
                when (val read = call.read) {
                    is DaemonCanonicalRead.SourceRead -> mcpPreparers.sourceRead.prepare(read.request)
                }
            is DaemonOperationCall.Change ->
                when (val action = call.action) {
                    is DaemonChangeAction.Plan -> mcpPreparers.changePlan.prepare(action.request)
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
                is DaemonChangeAction.Apply ->
                    mcpMutation(
                        request,
                        HostedMutationOperation.CHANGE_APPLY,
                        action.request.planIdentity.value,
                    )
                is DaemonChangeAction.Recover ->
                    mcpMutation(
                        request,
                        HostedMutationOperation.CHANGE_RECOVER,
                        action.request.planIdentity.value,
                    )
            }
        else -> ExistingIdeOperation.Read.admit(request)
    }
}

private fun mcpMutation(
    request: io.github.amichne.kast.protocol.wire.presentation.PreparedOperationRequest,
    kind: HostedMutationOperation,
    identity: String,
): Refinement<ExistingIdeOperation, ExistingIdeFailure> {
    val plan =
        when (val parsed = HostedPlanIdentity.parse(identity)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    return ExistingIdeOperation.Mutation.admit(request, kind, plan)
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
