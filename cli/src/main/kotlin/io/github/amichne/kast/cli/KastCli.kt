package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.cli.projection.ProductInspectionDocuments
import io.github.amichne.kast.cli.broker.BrokerServerRun
import io.github.amichne.kast.cli.broker.BrokerServerRunner
import io.github.amichne.kast.cli.broker.UnavailableBrokerServerRunner
import io.github.amichne.kast.cli.broker.outputReason
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
    private val brokerServerRunner: BrokerServerRunner = UnavailableBrokerServerRunner,
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
     * Establishes a canonical command, exact root, admitted runtime endpoint, typed wire outcome,
     * canonical JSON document, and exhaustive process status. [CliBoundaryExitStatus] is the
     * finite boundary-failure classification. Raw argv and start path are permitted only here.
     */
    fun execute(
        argv: List<String>,
        start: Path,
    ): CliExit {
        return when (val parsed = commandGraphFactory.parse(argv)) {
            is CliCommandParsing.Parsed -> executeAction(parsed.action, start)
            is CliCommandParsing.Help -> CliExit.Complete(parsed.document)
            is CliCommandParsing.Rejected -> usageExit(parsed.failure, parsed.diagnostic)
            is CliCommandParsing.ProjectionRejected -> projectionFailure(parsed.failure)
        }
    }

    private fun executeAction(action: CliAction, start: Path): CliExit = when (action) {
        is CliAction.Local.Metadata -> CliExit.Complete(localMetadata.output(action.command))
        CliAction.Local.ProductInspect -> CliExit.Complete(
            ProductInspectionDocuments.complete(productInspector.inspect(start)),
        )
        CliAction.Local.BrokerServe -> when (val run = brokerServerRunner.serve()) {
            BrokerServerRun.Stopped -> CliExit.Complete(CliBoundaryDocuments.brokerStopped())
            is BrokerServerRun.Rejected -> boundaryExit(
                CliBoundaryExitStatus.RUNTIME,
                run.failure.outputReason(),
            )
        }
        is CliAction.Semantic -> executeSemantic(action.request, start)
        is CliAction.Lifecycle -> executeLifecycle(action, start)
    }

    private fun executeSemantic(
        request: PreparedCliRequest,
        start: Path,
    ): CliExit {
        val boundary = when (
            val resolution = resolvePassiveRuntimeBoundary(start, CliLifecycleCommand.STATUS)
        ) {
            is CliRuntimeBoundaryResolution.Resolved -> resolution
            is CliRuntimeBoundaryResolution.Rejected -> return resolution.exit
        }
        return when (val observation = lifecycle.status(boundary.endpoint)) {
            is RuntimeStatusResult.Observed -> if (
                observation.state == RuntimeLifecycleState.RUNNING
            ) {
                executeRequest(request, boundary)
            } else {
                boundaryExit(CliBoundaryExitStatus.RUNTIME, "runtime-not-running")
            }
            is RuntimeStatusResult.Rejected -> boundaryExit(
                CliBoundaryExitStatus.RUNTIME,
                "runtime-observation-${observation.failure.name.lowercase().replace('_', '-')}",
            )
        }
    }

    private fun executeLifecycle(
        action: CliAction.Lifecycle,
        start: Path,
    ): CliExit = when (action) {
        is CliAction.Lifecycle.Start -> startExit(start, action.startup)
        CliAction.Lifecycle.Status -> when (
            val resolution = resolvePassiveRuntimeBoundary(start, action.command)
        ) {
            is CliRuntimeBoundaryResolution.Resolved -> statusExit(
                resolution.endpoint,
                lifecycle.status(resolution.endpoint),
                resolution.cache,
            )
            is CliRuntimeBoundaryResolution.Rejected -> resolution.exit
        }
        CliAction.Lifecycle.Stop -> when (
            val resolution = resolvePassiveRuntimeBoundary(start, action.command)
        ) {
            is CliRuntimeBoundaryResolution.Resolved -> stopExit(
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
        val root = when (val discovery = rootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> return boundaryExit(
                CliBoundaryExitStatus.ROOT,
                discovery.failure.name.lowercase(),
            )
        }
        val reconciliation = when (val resolved = reconcileStart(root, startup)) {
            is StartReconciliation.Reconciled -> resolved
            is StartReconciliation.Rejected -> return resolved.exit
        }
        val boundary = when (
            val resolution = demandRuntimeBoundary(
                root,
                HostedRuntimeDemand.Lifecycle,
                reconciliation.startup,
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
                reconciliation.removed,
            ),
        )
    }

    private fun reconcileStart(
        root: CanonicalRoot,
        startup: RuntimeStartupRequest,
    ): StartReconciliation {
        val boundary = when (
            val resolution = resolvePassiveRuntimeBoundary(root, CliLifecycleCommand.START)
        ) {
            is CliRuntimeBoundaryResolution.Resolved -> resolution
            is CliRuntimeBoundaryResolution.Rejected -> return StartReconciliation.Rejected(
                resolution.exit,
            )
        }
        val shouldStop = when (val status = lifecycle.status(boundary.endpoint)) {
            is RuntimeStatusResult.Observed ->
                status.state != RuntimeLifecycleState.RUNNING ||
                    startup.cacheIntent == StartupCacheIntent.Rebuild
            is RuntimeStatusResult.Rejected -> return StartReconciliation.Rejected(
                boundaryExit(
                    CliBoundaryExitStatus.RUNTIME,
                    "start-${status.failure.name.lowercase().replace('_', '-')}",
                ),
            )
        }
        val removed = if (shouldStop) {
            when (val stopped = lifecycle.stop(boundary.endpoint)) {
                is RuntimeStopResult.Stopped -> stopped.removed
                is RuntimeStopResult.Rejected -> return StartReconciliation.Rejected(
                    stopExit(CliLifecycleCommand.START, boundary.endpoint, stopped)
                        as CliExit.BoundaryRejected,
                )
            }
        } else {
            emptySet()
        }
        val admittedStartup = when (startup.cacheIntent) {
            StartupCacheIntent.Rebuild -> when (
                val quarantine = cacheLifecycle.quarantine(root.path)
            ) {
                is RootSidecarCacheQuarantine.Quarantined,
                is RootSidecarCacheQuarantine.NoCache,
                    -> RuntimeStartupRequest.Requested(
                        startup.ideHome,
                        StartupCacheIntent.Reuse,
                    )
                is RootSidecarCacheQuarantine.Rejected -> return StartReconciliation.Rejected(
                    cacheLifecycleExit(CliLifecycleCommand.START, quarantine.failure),
                )
            }
            StartupCacheIntent.Reuse,
            is StartupCacheIntent.Seed,
                -> startup
        }
        return StartReconciliation.Reconciled(admittedStartup, removed)
    }

    private fun demandRuntimeBoundary(
        root: CanonicalRoot,
        demand: HostedRuntimeDemand,
        startup: RuntimeStartupRequest,
    ): CliRuntimeBoundaryResolution {
        val endpoint = when (val admission = runtimeDemander.demand(root, demand, startup)) {
            is RuntimeAdmission.Ready -> admission.endpoint
            is RuntimeAdmission.Rejected -> return CliRuntimeBoundaryResolution.Rejected(
                runtimeBoundaryExit(admission.failure),
            )
        }
        if (endpoint.root != root) {
            return CliRuntimeBoundaryResolution.Rejected(
                boundaryExit(CliBoundaryExitStatus.RUNTIME, "root-mismatch"),
            )
        }
        return CliRuntimeBoundaryResolution.Resolved(root, endpoint)
    }

    private fun resolvePassiveRuntimeBoundary(
        start: Path,
        command: CliLifecycleCommand,
    ): CliRuntimeBoundaryResolution {
        val root = when (val discovery = rootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> return CliRuntimeBoundaryResolution.Rejected(
                boundaryExit(CliBoundaryExitStatus.ROOT, discovery.failure.name.lowercase()),
            )
        }
        return resolvePassiveRuntimeBoundary(root, command)
    }

    private fun resolvePassiveRuntimeBoundary(
        root: CanonicalRoot,
        command: CliLifecycleCommand,
    ): CliRuntimeBoundaryResolution {
        val endpoint = when (val resolution = endpointLocator.locate(root)) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> return CliRuntimeBoundaryResolution.Rejected(
                boundaryExit(CliBoundaryExitStatus.RUNTIME, "endpoint-unavailable"),
            )
        }
        val cache = cacheLifecycle.observe(root.path)
        val exactEndpoint = when (cache) {
            is RootSidecarCacheObservation.Identified -> when (
                val resolution = endpoint.forSidecarCache(
                    cache.status.cacheIdentity,
                    cache.status.semanticRuntimeId,
                    cache.status.cacheRoot,
                )
            ) {
                is RuntimeEndpointResolution.Resolved -> resolution.endpoint
                is RuntimeEndpointResolution.Rejected -> return CliRuntimeBoundaryResolution
                    .Rejected(
                        boundaryExit(CliBoundaryExitStatus.RUNTIME, "endpoint-unavailable"),
                    )
            }
            RootSidecarCacheObservation.Absent -> endpoint
            is RootSidecarCacheObservation.Rejected -> return CliRuntimeBoundaryResolution.Rejected(
                cacheLifecycleExit(command, cache.failure),
            )
        }
        if (exactEndpoint.root != root) {
            return CliRuntimeBoundaryResolution.Rejected(
                boundaryExit(CliBoundaryExitStatus.RUNTIME, "root-mismatch"),
            )
        }
        return CliRuntimeBoundaryResolution.Resolved(root, exactEndpoint, cache)
    }

    private fun executeRequest(
        request: PreparedCliRequest,
        boundary: CliRuntimeBoundaryResolution.Resolved,
    ): CliExit {
        val response = when (
            val exchange = wireClient.exchange(boundary.endpoint, request.document)
        ) {
            is WireExchange.Received -> exchange.document
            is WireExchange.Rejected -> return boundaryExit(
                CliBoundaryExitStatus.TRANSPORT,
                exchange.failure.name.lowercase(),
            )
        }
        return when (val completion = request.complete(response)) {
            is CliProjectionCompletion.Completed -> when (val outcome = completion.outcome) {
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
    ): CliExit = when (result) {
        is RuntimeStatusResult.Observed -> when (cache) {
            RootSidecarCacheObservation.Absent -> CliExit.Complete(
                CliBoundaryDocuments.statusCompleteWithoutCache(endpoint, result.state),
            )
            is RootSidecarCacheObservation.Identified -> CliExit.Complete(
                CliBoundaryDocuments.statusComplete(endpoint, result.state, cache),
            )
            is RootSidecarCacheObservation.Rejected -> cacheLifecycleExit(
                CliLifecycleCommand.STATUS,
                cache.failure,
            )
        }
        is RuntimeStatusResult.Rejected -> boundaryExit(
            CliBoundaryExitStatus.RUNTIME,
            "${CliLifecycleCommand.STATUS.command}-${result.failure.name.lowercase().replace('_', '-')}",
        )
    }

    private fun cacheLifecycleExit(
        command: CliLifecycleCommand,
        failure: SidecarCacheLifecycleFailure,
    ): CliExit.BoundaryRejected = boundaryExit(
        CliBoundaryExitStatus.RUNTIME,
        "${command.command}-cache-${failure.name.lowercase().replace('_', '-')}",
    )

    private fun stopExit(
        command: CliLifecycleCommand,
        endpoint: RuntimeEndpoint,
        result: RuntimeStopResult,
    ): CliExit = when (result) {
        is RuntimeStopResult.Stopped -> lifecycleCompletedExit(
            command,
            endpoint,
            result.removed,
        )
        is RuntimeStopResult.Rejected -> boundaryExit(
            CliBoundaryExitStatus.RUNTIME,
            "${command.command}-${result.failure.name.lowercase().replace('_', '-')}",
        )
    }

    private fun lifecycleCompletedExit(
        command: CliLifecycleCommand,
        endpoint: RuntimeEndpoint,
        removed: Set<RuntimeEndpointArtifact>,
    ): CliExit = CliExit.Complete(
        CliBoundaryDocuments.lifecycleComplete(
            command,
            endpoint,
            RuntimeLifecycleState.STOPPED,
            removed,
        ),
    )

    private fun projectionFailure(failure: CliProjectionFailure): CliExit = when (failure) {
        is CliProjectionFailure.RequestEncodingFailed -> boundaryExit(
            CliBoundaryExitStatus.PROTOCOL,
            "request-encoding-rejected",
        )
        is CliProjectionFailure.ResponseDecodingFailed -> boundaryExit(
            CliBoundaryExitStatus.PROTOCOL,
            "response-decoding-rejected",
        )
    }
}

private sealed interface StartReconciliation {
    data class Reconciled(
        val startup: RuntimeStartupRequest,
        val removed: Set<RuntimeEndpointArtifact>,
    ) : StartReconciliation

    data class Rejected(val exit: CliExit.BoundaryRejected) : StartReconciliation
}

private sealed interface CliRuntimeBoundaryResolution {
    data class Resolved(
        val root: CanonicalRoot,
        val endpoint: RuntimeEndpoint,
        val cache: RootSidecarCacheObservation = RootSidecarCacheObservation.Absent,
    ) : CliRuntimeBoundaryResolution

    data class Rejected(
        val exit: CliExit.BoundaryRejected,
    ) : CliRuntimeBoundaryResolution
}

enum class CliBoundaryExitStatus(
    val code: Int,
) {
    USAGE(2),
    ROOT(3),
    RUNTIME(4),
    TRANSPORT(5),
    PROTOCOL(6),
    BOOTSTRAP(9),
}

/** Complete and exhaustive process result; every variant carries one admitted process document. */
sealed interface CliExit {
    val code: Int
    val document: CliProcessOutput

    data class Complete(
        override val document: CliProcessOutput,
    ) : CliExit {
        override val code: Int = 0
    }

    data class Qualified(
        override val document: CliJsonDocument,
    ) : CliExit {
        override val code: Int = 0
    }

    data class OperationRejected(
        override val document: CliJsonDocument,
    ) : CliExit {
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

private fun runtimeBoundaryExit(
    failure: RuntimeAdmissionFailure,
): CliExit.BoundaryRejected = CliExit.BoundaryRejected(
    CliBoundaryExitStatus.RUNTIME,
    CliBoundaryDocuments.runtimeRejected(failure),
)

private fun usageExit(
    failure: CliCommandFailure,
    diagnostic: CliTextDocument,
): CliExit.BoundaryRejected = CliExit.BoundaryRejected(
    CliBoundaryExitStatus.USAGE,
    CliBoundaryDocuments.usageRejected(failure, diagnostic),
)
