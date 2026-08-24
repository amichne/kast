package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.RuntimeEndpoint
import io.github.amichne.kast.cli.RuntimeEndpointArtifact
import io.github.amichne.kast.cli.RuntimeEndpointMarker
import io.github.amichne.kast.cli.RuntimeLifecycleState
import io.github.amichne.kast.cli.RuntimePersistentState
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.command.outputReason
import kotlinx.serialization.Serializable

/** Generated closed documents emitted by lifecycle and boundary orchestration. */
internal object CliBoundaryDocuments {
    fun lifecycleComplete(
        command: CliLifecycleCommand,
        endpoint: RuntimeEndpoint,
        state: RuntimeLifecycleState,
        removed: Set<RuntimeEndpointArtifact>,
    ): CliJsonDocument = lifecycleFactory.create(
        CliLifecycleCompleteDocument(
            command = command.command,
            status = "complete",
            runtime = state.name.lowercase(),
            root = endpoint.root.path.toString(),
            runtimeId = endpoint.runtimeId.value,
            removed = removed.map(RuntimeEndpointArtifact::lifecycleOutputName).sorted(),
        ),
    )

    fun boundaryRejected(
        status: CliBoundaryExitStatus,
        reason: String,
    ): CliJsonDocument = boundaryFactory.create(
        CliBoundaryRejectedDocument(
            status = "rejected",
            boundary = status.name.lowercase(),
            reason = reason,
        ),
    )

    fun usageRejected(
        failure: CliCommandFailure,
        diagnostic: CliTextDocument,
    ): CliJsonDocument = usageFactory.create(
        CliUsageRejectedDocument(
            status = "rejected",
            boundary = "usage",
            reason = failure.outputReason(),
            diagnostic = diagnostic.value,
        ),
    )
}

@Serializable
private data class CliLifecycleCompleteDocument(
    val command: String,
    val status: String,
    val runtime: String,
    val root: String,
    val runtimeId: String,
    val removed: List<String>,
)

@Serializable
private data class CliBoundaryRejectedDocument(
    val status: String,
    val boundary: String,
    val reason: String,
)

@Serializable
private data class CliUsageRejectedDocument(
    val status: String,
    val boundary: String,
    val reason: String,
    val diagnostic: String,
)

private fun RuntimeEndpointArtifact.lifecycleOutputName(): String = when (this) {
    is RuntimeEndpointMarker -> name.lowercase()
    RuntimePersistentState -> "state"
}

private val lifecycleFactory =
    CliJsonDocument.generated(CliLifecycleCompleteDocument.serializer())
private val boundaryFactory =
    CliJsonDocument.generated(CliBoundaryRejectedDocument.serializer())
private val usageFactory =
    CliJsonDocument.generated(CliUsageRejectedDocument.serializer())
