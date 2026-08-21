package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/** Pure orchestration of the closed CLI boundaries and their explicit outer effects. */
class KastCli(
    private val projections: CliProjectionTable,
    private val rootDiscovery: CanonicalRootDiscoverer,
    private val endpointLocator: RuntimeEndpointLocator,
    private val runtimeDemander: RuntimeDemander,
    private val wireClient: WireClient,
    private val localMetadata: CliLocalMetadata,
    private val lifecycle: RuntimeLifecycleController = ExactRootRuntimeLifecycle(),
) {
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
        return when (val parsed = CliCommandParser.parse(argv)) {
            is CliCommandParsing.Local -> return CliExit.Complete(
                localMetadata.output(parsed.command),
            )
            is CliCommandParsing.Lifecycle -> executeLifecycle(parsed.command, start)
            is CliCommandParsing.Parsed -> executeSemantic(parsed.invocation, start)
            is CliCommandParsing.Rejected -> boundaryExit(
                CliBoundaryExitStatus.USAGE,
                parsed.failure.outputReason(),
            )
        }
    }

    private fun executeSemantic(
        invocation: CliInvocation,
        start: Path,
    ): CliExit {
        val request = when (val preparation = projections.prepare(invocation)) {
            is CliProjectionPreparation.Prepared -> preparation.request
            is CliProjectionPreparation.Rejected -> return projectionFailure(preparation.failure)
        }
        val boundary = when (val resolution = resolveRuntimeBoundary(start)) {
            is CliRuntimeBoundaryResolution.Resolved -> resolution
            is CliRuntimeBoundaryResolution.Rejected -> return resolution.exit
        }
        return executeRequest(request, boundary)
    }

    private fun executeLifecycle(
        command: CliLifecycleCommand,
        start: Path,
    ): CliExit {
        val boundary = when (val resolution = resolveRuntimeBoundary(start)) {
            is CliRuntimeBoundaryResolution.Resolved -> resolution
            is CliRuntimeBoundaryResolution.Rejected -> return resolution.exit
        }
        return when (command) {
            CliLifecycleCommand.START -> executeWorkspaceInspect(boundary)
            CliLifecycleCommand.STATUS -> statusExit(
                boundary.endpoint,
                lifecycle.status(boundary.endpoint),
            )
            CliLifecycleCommand.STOP -> stopExit(
                command,
                boundary.endpoint,
                lifecycle.stop(boundary.endpoint),
            )
            CliLifecycleCommand.CLEAN -> cleanExit(
                command,
                boundary.endpoint,
                lifecycle.clean(boundary.endpoint),
            )
            CliLifecycleCommand.REINDEX -> {
                val stopped = lifecycle.stop(boundary.endpoint)
                if (stopped is RuntimeStopResult.Rejected) {
                    return stopExit(command, boundary.endpoint, stopped)
                }
                val cleaned = lifecycle.clean(boundary.endpoint)
                if (cleaned is RuntimeCleanResult.Rejected) {
                    return cleanExit(command, boundary.endpoint, cleaned)
                }
                executeWorkspaceInspect(boundary)
            }
        }
    }

    private fun executeWorkspaceInspect(
        boundary: CliRuntimeBoundaryResolution.Resolved,
    ): CliExit {
        val invocation = CliInvocation(
            CanonicalOperation.WORKSPACE_INSPECT,
            CliArguments(emptyList()),
        )
        val request = when (val preparation = projections.prepare(invocation)) {
            is CliProjectionPreparation.Prepared -> preparation.request
            is CliProjectionPreparation.Rejected -> return projectionFailure(preparation.failure)
        }
        return executeRequest(request, boundary)
    }

    private fun resolveRuntimeBoundary(start: Path): CliRuntimeBoundaryResolution {
        val root = when (val discovery = rootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> return CliRuntimeBoundaryResolution.Rejected(
                boundaryExit(CliBoundaryExitStatus.ROOT, discovery.failure.name.lowercase()),
            )
        }
        val endpoint = when (val resolution = endpointLocator.locate(root)) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> return CliRuntimeBoundaryResolution.Rejected(
                boundaryExit(
                    CliBoundaryExitStatus.RUNTIME,
                    resolution.failure.name.lowercase().replace('_', '-'),
                ),
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
        val readyEndpoint = when (
            val admission = runtimeDemander.demand(boundary.root, boundary.endpoint)
        ) {
            is RuntimeAdmission.Ready -> admission.endpoint
            is RuntimeAdmission.Rejected -> return boundaryExit(
                CliBoundaryExitStatus.RUNTIME,
                admission.failure.name.lowercase().replace('_', '-'),
            )
        }
        if (readyEndpoint != boundary.endpoint) {
            return boundaryExit(CliBoundaryExitStatus.RUNTIME, "endpoint-mismatch")
        }
        val response = when (val exchange = wireClient.exchange(readyEndpoint, request.document)) {
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
        CliJsonDocument.from(
            buildJsonObject {
                put("command", command.command)
                put("status", "complete")
                put("runtime", state.name.lowercase())
                put("root", endpoint.root.path.toString())
                put("runtimeId", endpoint.runtimeId.value)
                put(
                    "removed",
                    JsonArray(
                        removed.map { artifact -> artifact.lifecycleOutputName() }
                            .sorted()
                            .map(::JsonPrimitive),
                    ),
                )
            },
        ),
    )

    /** Projects a lifecycle artifact to its stable JSON-boundary name. */
    private fun RuntimeEndpointArtifact.lifecycleOutputName(): String = when (this) {
        is RuntimeEndpointMarker -> name.lowercase()
        RuntimePersistentState -> "state"
    }

    private fun projectionFailure(failure: CliProjectionFailure): CliExit = when (failure) {
        is CliProjectionFailure.ArgumentsRejected -> boundaryExit(
            CliBoundaryExitStatus.PROJECTION,
            "arguments-rejected",
        )
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
    PROJECTION(7),
    BOOTSTRAP(9),
}

/** Complete and exhaustive process result; every variant carries canonical JSON. */
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
        CliJsonDocument.from(
            buildJsonObject {
                put("status", "rejected")
                put("boundary", status.name.lowercase())
                put("reason", reason)
            },
        ),
    )

private fun CliCommandFailure.outputReason(): String = when (this) {
    CliCommandFailure.MissingCommand -> "missing-command"
    CliCommandFailure.UnknownCommand -> "unknown-command"
    CliCommandFailure.TooManyArguments -> "too-many-arguments"
    is CliCommandFailure.InvalidArgument -> "invalid-argument-${failure.name.lowercase()}"
}
