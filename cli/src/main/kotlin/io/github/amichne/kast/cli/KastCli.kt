package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.BrokerServerRun
import io.github.amichne.kast.appserver.BrokerServerRunner
import io.github.amichne.kast.appserver.UnavailableBrokerServerRunner
import io.github.amichne.kast.appserver.host.CodexClientLaunch
import io.github.amichne.kast.appserver.host.CodexClientLaunchRun
import io.github.amichne.kast.appserver.host.CodexClientLauncher
import io.github.amichne.kast.appserver.host.UnavailableCodexClientLauncher
import io.github.amichne.kast.appserver.outputReason
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.projection.ProductInspectionDocuments
import java.nio.file.Path

/** Pure orchestration of the closed CLI boundaries and their explicit outer effects. */
class KastCli(
    private val commandGraphFactory: CliCommandGraphFactory,
    private val rootDiscovery: CanonicalRootDiscoverer,
    private val endpointLocator: RuntimeEndpointLocator,
    private val runtimeDemander: RootRuntimeDemander,
    private val wireClient: WireClient,
    private val localMetadata: CliLocalMetadata,
    private val lifecycle: RuntimeLifecycleController,
    private val productInspector: ProductInspector,
    private val cacheLifecycle: RootSidecarCacheLifecycle = NoRootSidecarCacheLifecycle,
    private val appServerManager: io.github.amichne.kast.appserver.AppServerManager =
        io.github.amichne.kast.appserver.UnavailableAppServerManager,
    private val brokerServerRunner: BrokerServerRunner = UnavailableBrokerServerRunner,
    private val codexClientLauncher: CodexClientLauncher = UnavailableCodexClientLauncher,
    private val existingIdeClient: io.github.amichne.kast.cli.ide.ExistingIdeClient =
        io.github.amichne.kast.cli.ide.ExistingIdeClient { _, _ ->
            io.github.amichne.kast.cli.ide.ExistingIdeExchange.Rejected(
                io.github.amichne.kast.cli.ide.ExistingIdeFailure.HOST_UNAVAILABLE
            )
        },
) {
    constructor(
        commandGraphFactory: CliCommandGraphFactory,
        rootDiscovery: CanonicalRootDiscoverer,
        endpointLocator: RuntimeEndpointLocator,
        runtimeDemander: RuntimeDemander,
        wireClient: WireClient,
        localMetadata: CliLocalMetadata,
        lifecycle: RuntimeLifecycleController,
        productInspector: ProductInspector,
    ) : this(
        commandGraphFactory,
        rootDiscovery,
        endpointLocator,
        LocatedRuntimeDemander(endpointLocator, runtimeDemander),
        wireClient,
        localMetadata,
        lifecycle,
        productInspector,
    )

    /**
     * Proof transition: `List<String> + Path -> CliExit`.
     *
     * Establishes a canonical command, exact root, admitted runtime endpoint, typed wire outcome, canonical JSON
     * document, and exhaustive process status. [CliBoundaryExitStatus] is the finite boundary-failure classification.
     * Raw argv and start path are permitted only here.
     */
    fun execute(
        argv: List<String>,
        start: Path,
    ): CliExit = execute(argv, start, CliRequestDocumentInput.Absent)

    internal fun execute(
        argv: List<String>,
        start: Path,
        requestInput: CliRequestDocumentInput,
    ): CliExit {
        return when (val parsed = commandGraphFactory.parse(argv, requestInput)) {
            is CliCommandParsing.Parsed -> executeAction(parsed.action, start)
            is CliCommandParsing.Help -> CliExit.Complete(parsed.document)
            is CliCommandParsing.Rejected -> usageExit(parsed.failure, parsed.diagnostic)
            is CliCommandParsing.ProjectionRejected -> projectionFailure(parsed.failure)
        }
    }

    private fun executeAction(action: CliAction, start: Path): CliExit =
        when (action) {
            is CliAction.Local.Metadata -> CliExit.Complete(localMetadata.output(action.command))
            CliAction.Local.Inspect ->
                CliExit.Complete(
                    ProductInspectionDocuments.passive(
                        productInspector.inspect(start),
                        executeLifecycle(CliAction.Lifecycle.Status, start),
                    )
                )
            CliAction.Local.ProductInspect ->
                CliExit.Complete(ProductInspectionDocuments.complete(productInspector.inspect(start)))
            is CliAction.Local.AppServer ->
                when (val result = appServerManager.execute(action.action, start)) {
                    is io.github.amichne.kast.appserver.AppServerManagementResult.Completed ->
                        when (val document = CliTextDocument.admit(result.document.toString())) {
                            is CliTextDocumentAdmission.Admitted -> CliExit.Complete(document.document)
                            is CliTextDocumentAdmission.Rejected ->
                                boundaryExit(CliBoundaryExitStatus.RUNTIME, "app-server-output-rejected")
                        }
                    is io.github.amichne.kast.appserver.AppServerManagementResult.Rejected ->
                        boundaryExit(
                            CliBoundaryExitStatus.RUNTIME,
                            (result.serviceFailure?.name ?: result.failure.name).lowercase().replace('_', '-'),
                        )
                }
            CliAction.Local.BrokerServe ->
                when (val run = brokerServerRunner.serve()) {
                    BrokerServerRun.Stopped -> CliExit.Complete(CliBoundaryDocuments.brokerStopped())
                    is BrokerServerRun.Rejected ->
                        boundaryExit(
                            CliBoundaryExitStatus.RUNTIME,
                            run.failure.outputReason(),
                        )
                }
            CliAction.Local.CodexCli -> launchCodex(CodexClientLaunch.Cli)
            CliAction.Local.CodexDesktop -> launchCodex(CodexClientLaunch.Desktop)
            CliAction.Local.TrustBroker -> boundaryExit(CliBoundaryExitStatus.RUNTIME, "ide-trust-unavailable")
            is CliAction.Local.ExistingIde ->
                io.github.amichne.kast.cli.ide.executeExistingIdeAction(action, start, rootDiscovery, existingIdeClient)
            is CliAction.Semantic -> executeSemantic(action.request, start)
            is CliAction.Lifecycle -> executeLifecycle(action, start)
        }

    private fun launchCodex(client: CodexClientLaunch): CliExit =
        when (val run = codexClientLauncher.launch(client)) {
            is CodexClientLaunchRun.Completed -> CliExit.Delegated(run.exitCode)
            is CodexClientLaunchRun.Rejected ->
                boundaryExit(
                    CliBoundaryExitStatus.RUNTIME,
                    "codex-${run.failure.name.lowercase().replace('_', '-')}",
                )
        }

    private fun executeSemantic(
        request: PreparedCliRequest,
        start: Path,
    ): CliExit {
        val root =
            when (val discovery = rootDiscovery.discover(start)) {
                is CanonicalRootDiscovery.Discovered -> discovery.root
                is CanonicalRootDiscovery.Rejected ->
                    return boundaryExit(
                        CliBoundaryExitStatus.ROOT,
                        discovery.failure.name.lowercase(),
                    )
            }
        val boundary =
            when (
                val resolution =
                    demandRuntimeBoundary(
                        root,
                        request.hostedDemand,
                        RuntimeStartupRequest.Default,
                    )
            ) {
                is CliRuntimeBoundaryResolution.Resolved -> resolution
                is CliRuntimeBoundaryResolution.Rejected -> return resolution.exit
            }
        return executeRequest(request, boundary)
    }

    private fun executeLifecycle(
        action: CliAction.Lifecycle,
        start: Path,
    ): CliExit =
        when (action) {
            is CliAction.Lifecycle.Start -> startExit(start, action.startup)
            CliAction.Lifecycle.Status ->
                when (val resolution = resolvePassiveRuntimeBoundary(start, action.command)) {
                    is CliRuntimeBoundaryResolution.Resolved ->
                        statusExit(
                            resolution.endpoint,
                            lifecycle.status(resolution.endpoint),
                            resolution.cache,
                        )
                    is CliRuntimeBoundaryResolution.Rejected -> resolution.exit
                }
            CliAction.Lifecycle.Stop ->
                when (val resolution = resolvePassiveRuntimeBoundary(start, action.command)) {
                    is CliRuntimeBoundaryResolution.Resolved ->
                        stopExit(
                            action.command,
                            resolution.endpoint,
                            lifecycle.stop(resolution.endpoint),
                        )
                    is CliRuntimeBoundaryResolution.Rejected -> resolution.exit
                }
        }

    private fun startExit(
        start: Path,
        startup: RuntimeStartupRequest,
    ): CliExit {
        val root =
            when (val discovery = rootDiscovery.discover(start)) {
                is CanonicalRootDiscovery.Discovered -> discovery.root
                is CanonicalRootDiscovery.Rejected ->
                    return boundaryExit(
                        CliBoundaryExitStatus.ROOT,
                        discovery.failure.name.lowercase(),
                    )
            }
        val boundary =
            when (
                val resolution =
                    demandRuntimeBoundary(
                        root,
                        HostedRuntimeDemand.Lifecycle,
                        startup,
                    )
            ) {
                is CliRuntimeBoundaryResolution.Resolved -> resolution
                is CliRuntimeBoundaryResolution.Rejected -> return resolution.exit
            }
        return CliExit.Complete(
            CliBoundaryDocuments.lifecycleComplete(
                CliLifecycleCommand.START,
                boundary.endpoint,
                RuntimeLifecycleState.RUNNING,
                boundary.removed,
            )
        )
    }

    private fun demandRuntimeBoundary(
        root: CanonicalRoot,
        demand: HostedRuntimeDemand,
        startup: RuntimeStartupRequest,
    ): CliRuntimeBoundaryResolution {
        val ready =
            when (val admission = runtimeDemander.demand(root, demand, startup)) {
                is RuntimeAdmission.Ready -> admission
                is RuntimeAdmission.Rejected ->
                    return CliRuntimeBoundaryResolution.Rejected(runtimeBoundaryExit(admission.failure))
            }
        val endpoint = ready.endpoint
        if (endpoint.root != root) {
            return CliRuntimeBoundaryResolution.Rejected(boundaryExit(CliBoundaryExitStatus.RUNTIME, "root-mismatch"))
        }
        return CliRuntimeBoundaryResolution.Resolved(
            root,
            endpoint,
            removed = ready.removed,
            workerBinding = ready.workerBinding,
            deadline = ready.deadline,
            wireAuthority = ready.wireAuthority,
        )
    }

    private fun resolvePassiveRuntimeBoundary(
        start: Path,
        command: CliLifecycleCommand,
    ): CliRuntimeBoundaryResolution {
        val root =
            when (val discovery = rootDiscovery.discover(start)) {
                is CanonicalRootDiscovery.Discovered -> discovery.root
                is CanonicalRootDiscovery.Rejected ->
                    return CliRuntimeBoundaryResolution.Rejected(
                        boundaryExit(CliBoundaryExitStatus.ROOT, discovery.failure.name.lowercase())
                    )
            }
        return resolvePassiveRuntimeBoundary(root, command)
    }

    private fun resolvePassiveRuntimeBoundary(
        root: CanonicalRoot,
        command: CliLifecycleCommand,
    ): CliRuntimeBoundaryResolution {
        val endpoint =
            when (val resolution = endpointLocator.locate(root)) {
                is RuntimeEndpointResolution.Resolved -> resolution.endpoint
                is RuntimeEndpointResolution.Rejected ->
                    return CliRuntimeBoundaryResolution.Rejected(
                        boundaryExit(CliBoundaryExitStatus.RUNTIME, "endpoint-unavailable")
                    )
            }
        val cache = cacheLifecycle.observe(root.path)
        val exactEndpoint =
            when (cache) {
                is RootSidecarCacheObservation.Identified ->
                    when (
                        val resolution =
                            endpoint.forSidecarCache(
                                cache.status.cacheIdentity,
                                cache.status.semanticRuntimeId,
                                cache.status.cacheRoot,
                            )
                    ) {
                        is RuntimeEndpointResolution.Resolved -> resolution.endpoint
                        is RuntimeEndpointResolution.Rejected ->
                            return CliRuntimeBoundaryResolution.Rejected(
                                boundaryExit(CliBoundaryExitStatus.RUNTIME, "endpoint-unavailable")
                            )
                    }
                RootSidecarCacheObservation.Absent -> endpoint
                is RootSidecarCacheObservation.Rejected ->
                    return CliRuntimeBoundaryResolution.Rejected(cacheLifecycleExit(command, cache.failure))
            }
        if (exactEndpoint.root != root) {
            return CliRuntimeBoundaryResolution.Rejected(boundaryExit(CliBoundaryExitStatus.RUNTIME, "root-mismatch"))
        }
        return CliRuntimeBoundaryResolution.Resolved(root, exactEndpoint, cache)
    }

    private fun executeRequest(
        request: PreparedCliRequest,
        boundary: CliRuntimeBoundaryResolution.Resolved,
    ): CliExit {
        val response =
            when (
                val exchange =
                    when (val authority = boundary.wireAuthority) {
                        RuntimeWireAuthority.EffectBoundary ->
                            if (boundary.deadline == RuntimeInvocationDeadline.EffectBoundary)
                                wireClient.exchange(boundary.endpoint, request.document)
                            else WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER)
                        is RuntimeWireAuthority.Qualified ->
                            when (val deadline = boundary.deadline) {
                                RuntimeInvocationDeadline.EffectBoundary ->
                                    WireExchange.Rejected(WireTransportFailure.UNQUALIFIED_PEER)
                                is RuntimeInvocationDeadline.Running ->
                                    when (val remaining = deadline.remaining()) {
                                        is io.github.amichne.kast.kernel.Refinement.Refined ->
                                            wireClient.exchange(
                                                boundary.endpoint,
                                                request.document,
                                                authority.identity,
                                                remaining.value,
                                            )
                                        is io.github.amichne.kast.kernel.Refinement.Rejected ->
                                            WireExchange.Rejected(remaining.failure)
                                    }
                            }
                    }
            ) {
                is WireExchange.Received -> exchange.document
                is WireExchange.Rejected ->
                    return boundaryExit(
                        CliBoundaryExitStatus.TRANSPORT,
                        exchange.failure.name.lowercase(),
                    )
            }
        return when (val completion = request.complete(response)) {
            is CliProjectionCompletion.Completed ->
                when (val outcome = completion.outcome) {
                    is ProjectedCliOutcome.Complete -> CliExit.Complete(outcome.document)
                    is ProjectedCliOutcome.Qualified -> CliExit.Qualified(outcome.document)
                    is ProjectedCliOutcome.Rejected -> CliExit.OperationRejected(outcome.document)
                }
            is CliProjectionCompletion.Rejected -> projectionFailure(completion.failure)
        }
    }

    private fun statusExit(
        endpoint: RuntimeEndpoint,
        result: RuntimeStatusResult,
        cache: RootSidecarCacheObservation,
    ): CliExit =
        when (result) {
            is RuntimeStatusResult.Observed ->
                when (cache) {
                    RootSidecarCacheObservation.Absent ->
                        CliExit.Complete(CliBoundaryDocuments.statusCompleteWithoutCache(endpoint, result.state))
                    is RootSidecarCacheObservation.Identified ->
                        CliExit.Complete(CliBoundaryDocuments.statusComplete(endpoint, result.state, cache))
                    is RootSidecarCacheObservation.Rejected ->
                        cacheLifecycleExit(
                            CliLifecycleCommand.STATUS,
                            cache.failure,
                        )
                }
            is RuntimeStatusResult.Rejected ->
                boundaryExit(
                    CliBoundaryExitStatus.RUNTIME,
                    "${CliLifecycleCommand.STATUS.command}-${result.failure.name.lowercase().replace('_', '-')}",
                )
        }

    private fun cacheLifecycleExit(
        command: CliLifecycleCommand,
        failure: SidecarCacheLifecycleFailure,
    ): CliExit.BoundaryRejected =
        boundaryExit(
            CliBoundaryExitStatus.RUNTIME,
            "${command.command}-cache-${failure.name.lowercase().replace('_', '-')}",
        )

    private fun stopExit(
        command: CliLifecycleCommand,
        endpoint: RuntimeEndpoint,
        result: RuntimeStopResult,
    ): CliExit =
        when (result) {
            is RuntimeStopResult.Stopped ->
                lifecycleCompletedExit(
                    command,
                    endpoint,
                    result.removed,
                )
            is RuntimeStopResult.Rejected ->
                boundaryExit(
                    CliBoundaryExitStatus.RUNTIME,
                    "${command.command}-${result.failure.name.lowercase().replace('_', '-')}",
                )
        }

    private fun lifecycleCompletedExit(
        command: CliLifecycleCommand,
        endpoint: RuntimeEndpoint,
        removed: Set<RuntimeEndpointArtifact>,
    ): CliExit =
        CliExit.Complete(
            CliBoundaryDocuments.lifecycleComplete(
                command,
                endpoint,
                RuntimeLifecycleState.STOPPED,
                removed,
            )
        )

    private fun projectionFailure(failure: CliProjectionFailure): CliExit =
        when (failure) {
            is CliProjectionFailure.RequestEncodingFailed ->
                boundaryExit(
                    CliBoundaryExitStatus.PROTOCOL,
                    "request-encoding-rejected",
                )
            is CliProjectionFailure.ResponseDecodingFailed ->
                boundaryExit(
                    CliBoundaryExitStatus.PROTOCOL,
                    "response-decoding-rejected",
                )
        }
}

private sealed interface CliRuntimeBoundaryResolution {
    data class Resolved(
        val root: CanonicalRoot,
        val endpoint: RuntimeEndpoint,
        val cache: RootSidecarCacheObservation = RootSidecarCacheObservation.Absent,
        val removed: Set<RuntimeEndpointArtifact> = emptySet(),
        val workerBinding: io.github.amichne.kast.appserver.WorkerRouteBinding =
            io.github.amichne.kast.appserver.WorkerRouteBinding.EffectBoundary,
        val deadline: RuntimeInvocationDeadline = RuntimeInvocationDeadline.EffectBoundary,
        val wireAuthority: RuntimeWireAuthority = RuntimeWireAuthority.EffectBoundary,
    ) : CliRuntimeBoundaryResolution

    data class Rejected(val exit: CliExit.BoundaryRejected) : CliRuntimeBoundaryResolution
}

enum class CliBoundaryExitStatus(val code: Int) {
    USAGE(2),
    ROOT(3),
    RUNTIME(4),
    TRANSPORT(5),
    PROTOCOL(6),
    BOOTSTRAP(9),
}

/** Complete and exhaustive process result; every variant carries its explicit output policy. */
sealed interface CliExit {
    val code: Int
    val document: CliProcessOutput

    data class Delegated(override val code: Int) : CliExit {
        override val document: CliProcessOutput = CliDelegatedProcessOutput
    }

    data class Complete(override val document: CliProcessOutput) : CliExit {
        override val code: Int = 0
    }

    data class Qualified(override val document: CliJsonDocument) : CliExit {
        override val code: Int = 0
    }

    data class OperationRejected(override val document: CliJsonDocument) : CliExit {
        override val code: Int = 0
    }

    data class BoundaryRejected(
        val status: CliBoundaryExitStatus,
        override val document: CliJsonDocument,
    ) : CliExit {
        override val code: Int = status.code
    }
}

internal fun boundaryExit(
    status: CliBoundaryExitStatus,
    reason: String,
): CliExit.BoundaryRejected =
    CliExit.BoundaryRejected(
        status,
        CliBoundaryDocuments.boundaryRejected(status, reason),
    )

private fun runtimeBoundaryExit(failure: RuntimeAdmissionFailure): CliExit.BoundaryRejected =
    CliExit.BoundaryRejected(
        CliBoundaryExitStatus.RUNTIME,
        CliBoundaryDocuments.runtimeRejected(failure),
    )

private fun usageExit(
    failure: CliCommandFailure,
    diagnostic: CliTextDocument,
): CliExit.BoundaryRejected =
    CliExit.BoundaryRejected(
        CliBoundaryExitStatus.USAGE,
        CliBoundaryDocuments.usageRejected(failure, diagnostic),
    )
