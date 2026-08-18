package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.CliLocalMetadata
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
        val invocation = when (val parsed = CliCommandParser.parse(argv)) {
            is CliCommandParsing.Local -> return CliExit.Complete(
                localMetadata.output(parsed.command),
            )
            is CliCommandParsing.Parsed -> parsed.invocation
            is CliCommandParsing.Rejected -> return boundaryExit(
                CliBoundaryExitStatus.USAGE,
                parsed.failure.outputReason(),
            )
        }
        val request = when (val preparation = projections.prepare(invocation)) {
            is CliProjectionPreparation.Prepared -> preparation.request
            is CliProjectionPreparation.Rejected -> return projectionFailure(preparation.failure)
        }
        val root = when (val discovery = rootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> return boundaryExit(
                CliBoundaryExitStatus.ROOT,
                discovery.failure.name.lowercase(),
            )
        }
        val endpoint = when (val resolution = endpointLocator.locate(root)) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> return boundaryExit(
                CliBoundaryExitStatus.RUNTIME,
                resolution.failure.name.lowercase().replace('_', '-'),
            )
        }
        if (endpoint.root != root) {
            return boundaryExit(CliBoundaryExitStatus.RUNTIME, "root-mismatch")
        }
        val readyEndpoint = when (val admission = runtimeDemander.demand(root, endpoint)) {
            is RuntimeAdmission.Ready -> admission.endpoint
            is RuntimeAdmission.Rejected -> return boundaryExit(
                CliBoundaryExitStatus.RUNTIME,
                admission.failure.name.lowercase().replace('_', '-'),
            )
        }
        if (readyEndpoint != endpoint) {
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
        override val code: Int = 8
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
