package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.cli.projection.ProductInspectionDocuments
import java.nio.file.Path

/** Pure orchestration of the closed CLI boundaries and their explicit outer effects. */
class KastCli(
    private val commandGraphFactory: CliCommandGraphFactory,
    private val rootDiscovery: CanonicalRootDiscoverer,
    private val runtimeDemander: RootRuntimeDemander,
    private val wireClient: WireClient,
    private val localMetadata: CliLocalMetadata,
    private val lifecycle: RuntimeLifecycleController,
    private val productInspector: ProductInspector,
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
        is CliAction.Semantic -> executeSemantic(action.request, start)
        is CliAction.Lifecycle -> executeLifecycle(action, start)
    }

    private fun executeSemantic(
        request: PreparedCliRequest,
        start: Path,
    ): CliExit {
        val boundary = when (val resolution = resolveRuntimeBoundary(start, request.hostedDemand)) {
            is CliRuntimeBoundaryResolution.Resolved -> resolution
            is CliRuntimeBoundaryResolution.Rejected -> return resolution.exit
        }
        return executeRequest(request, boundary)
    }

    private fun executeLifecycle(
        action: CliAction.Lifecycle,
        start: Path,
    ): CliExit {
        val demand = when (action) {
            is CliAction.Lifecycle.Start -> action.request.hostedDemand
            is CliAction.Lifecycle.Reindex -> action.request.hostedDemand
            CliAction.Lifecycle.Clean,
            CliAction.Lifecycle.Status,
            CliAction.Lifecycle.Stop,
                -> HostedRuntimeDemand.Lifecycle
        }
        val boundary = when (val resolution = resolveRuntimeBoundary(start, demand)) {
            is CliRuntimeBoundaryResolution.Resolved -> resolution
            is CliRuntimeBoundaryResolution.Rejected -> return resolution.exit
        }
        return when (action) {
            is CliAction.Lifecycle.Start -> executeRequest(action.request, boundary)
            CliAction.Lifecycle.Status -> statusExit(
                boundary.endpoint,
                lifecycle.status(boundary.endpoint),
            )
            CliAction.Lifecycle.Stop -> stopExit(
                action.command,
                boundary.endpoint,
                lifecycle.stop(boundary.endpoint),
            )
            CliAction.Lifecycle.Clean -> cleanExit(
                action.command,
                boundary.endpoint,
                lifecycle.clean(boundary.endpoint),
            )
            is CliAction.Lifecycle.Reindex -> {
                val stopped = lifecycle.stop(boundary.endpoint)
                if (stopped is RuntimeStopResult.Rejected) {
                    return stopExit(action.command, boundary.endpoint, stopped)
                }
                val cleaned = lifecycle.clean(boundary.endpoint)
                if (cleaned is RuntimeCleanResult.Rejected) {
                    return cleanExit(action.command, boundary.endpoint, cleaned)
                }
                executeRequest(action.request, boundary)
            }
        }
    }

    private fun resolveRuntimeBoundary(
        start: Path,
        demand: HostedRuntimeDemand,
    ): CliRuntimeBoundaryResolution {
        val root = when (val discovery = rootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> return CliRuntimeBoundaryResolution.Rejected(
                boundaryExit(CliBoundaryExitStatus.ROOT, discovery.failure.name.lowercase()),
            )
        }
        val endpoint = when (val admission = runtimeDemander.demand(root, demand)) {
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
    ): CliExit = when (result) {
        is RuntimeStatusResult.Observed -> lifecycleCompletedExit(
            CliLifecycleCommand.STATUS,
            endpoint,
            result.state,
            emptySet(),
        )
        is RuntimeStatusResult.Rejected -> boundaryExit(
            CliBoundaryExitStatus.RUNTIME,
            "${CliLifecycleCommand.STATUS.command}-${result.failure.name.lowercase().replace('_', '-')}",
        )
    }

    private fun stopExit(
        command: CliLifecycleCommand,
        endpoint: RuntimeEndpoint,
        result: RuntimeStopResult,
    ): CliExit = when (result) {
        is RuntimeStopResult.Stopped -> lifecycleCompletedExit(
            command,
            endpoint,
            RuntimeLifecycleState.STOPPED,
            result.removed,
        )
        is RuntimeStopResult.Rejected -> boundaryExit(
            CliBoundaryExitStatus.RUNTIME,
            "${command.command}-${result.failure.name.lowercase().replace('_', '-')}",
        )
    }

    private fun cleanExit(
        command: CliLifecycleCommand,
        endpoint: RuntimeEndpoint,
        result: RuntimeCleanResult,
    ): CliExit = when (result) {
        is RuntimeCleanResult.Cleaned -> lifecycleCompletedExit(
            command,
            endpoint,
            RuntimeLifecycleState.STOPPED,
            result.removed,
        )
        is RuntimeCleanResult.Rejected -> boundaryExit(
            CliBoundaryExitStatus.RUNTIME,
            "${command.command}-${result.failure.name.lowercase().replace('_', '-')}",
        )
    }

    private fun lifecycleCompletedExit(
        command: CliLifecycleCommand,
        endpoint: RuntimeEndpoint,
        state: RuntimeLifecycleState,
        removed: Set<RuntimeEndpointArtifact>,
    ): CliExit = CliExit.Complete(
        CliBoundaryDocuments.lifecycleComplete(command, endpoint, state, removed),
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

private sealed interface CliRuntimeBoundaryResolution {
    data class Resolved(
        val root: CanonicalRoot,
        val endpoint: RuntimeEndpoint,
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
