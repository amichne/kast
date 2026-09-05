package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.RuntimeBootstrapObservation
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase
import io.github.amichne.kast.distribution.contract.bootstrap.correctiveAction
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionObservation
import io.github.amichne.kast.cli.RuntimeEndpoint
import io.github.amichne.kast.cli.RuntimeEndpointArtifact
import io.github.amichne.kast.cli.RuntimeEndpointMarker
import io.github.amichne.kast.cli.RuntimeLifecycleState
import io.github.amichne.kast.cli.RuntimePersistentState
import io.github.amichne.kast.cli.RuntimeAdmissionFailure
import io.github.amichne.kast.cli.RootSidecarCacheObservation
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.command.outputReason
import io.github.amichne.kast.cli.outputReason
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonClassDiscriminator

/** Generated closed documents emitted by lifecycle and boundary orchestration. */
internal object CliBoundaryDocuments {
    fun brokerStopped(): CliJsonDocument = brokerStoppedFactory.create(
        CliBrokerStoppedDocument(
            command = "broker serve",
            status = "complete",
            broker = "stopped",
        ),
    )

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

    fun statusCompleteWithoutCache(
        endpoint: RuntimeEndpoint,
        state: RuntimeLifecycleState,
    ): CliJsonDocument = statusWithoutCacheFactory.create(
        CliStatusWithoutCacheDocument(
            command = CliLifecycleCommand.STATUS.command,
            status = "complete",
            runtime = state.name.lowercase(),
            root = endpoint.root.path.toString(),
            runtimeId = endpoint.runtimeId.value,
            removed = emptyList(),
            cache = CliAbsentCacheDocument("absent"),
        ),
    )

    fun statusComplete(
        endpoint: RuntimeEndpoint,
        state: RuntimeLifecycleState,
        cache: RootSidecarCacheObservation.Identified,
    ): CliJsonDocument = statusWithCacheFactory.create(
        CliStatusWithCacheDocument(
            command = CliLifecycleCommand.STATUS.command,
            status = "complete",
            runtime = state.name.lowercase(),
            root = endpoint.root.path.toString(),
            runtimeId = endpoint.runtimeId.value,
            removed = emptyList(),
            bootstrap = cache.status.bootstrap.document(),
            cache = CliObservedCacheDocument(
                state = cache.status.state.wireName,
                identity = cache.status.cacheIdentity,
                ideaHome = cache.status.ideaHome.toString(),
                ideaBuild = cache.status.ideaBuild,
                kotlinPluginBuild = cache.status.kotlinPluginBuild,
                jbrIdentity = cache.status.jbrIdentity,
                kastPayloadDigest = cache.status.kastPayloadDigest,
            ),
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

    fun runtimeRejected(failure: RuntimeAdmissionFailure): CliJsonDocument = when (failure) {
        is RuntimeAdmissionFailure.IntellijBootstrap -> bootstrapRejectionFactory.create(
            CliBootstrapRejectedDocument(
                status = "rejected",
                boundary = "runtime",
                reason = failure.outputReason(),
                bootstrap = failure.state.document(),
            ),
        )
        else -> boundaryRejected(CliBoundaryExitStatus.RUNTIME, failure.outputReason())
    }

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
private data class CliBrokerStoppedDocument(
    val command: String,
    val status: String,
    val broker: String,
)

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
private data class CliStatusWithoutCacheDocument(
    val command: String,
    val status: String,
    val runtime: String,
    val root: String,
    val runtimeId: String,
    val removed: List<String>,
    val cache: CliAbsentCacheDocument,
)

@Serializable
private data class CliStatusWithCacheDocument(
    val command: String,
    val status: String,
    val runtime: String,
    val root: String,
    val runtimeId: String,
    val removed: List<String>,
    val cache: CliObservedCacheDocument,
    val bootstrap: CliBootstrapDocument,
)

@Serializable
private data class CliAbsentCacheDocument(
    val state: String,
)

@Serializable
private data class CliObservedCacheDocument(
    val state: String,
    val identity: String,
    val ideaHome: String,
    val ideaBuild: String,
    val kotlinPluginBuild: String,
    val jbrIdentity: String,
    val kastPayloadDigest: String,
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
private val brokerStoppedFactory =
    CliJsonDocument.generated(CliBrokerStoppedDocument.serializer())
private val statusWithoutCacheFactory =
    CliJsonDocument.generated(CliStatusWithoutCacheDocument.serializer())
private val statusWithCacheFactory =
    CliJsonDocument.generated(CliStatusWithCacheDocument.serializer())
private val boundaryFactory =
    CliJsonDocument.generated(CliBoundaryRejectedDocument.serializer())
private val usageFactory =
    CliJsonDocument.generated(CliUsageRejectedDocument.serializer())

@Serializable
@JsonClassDiscriminator("state")
private sealed interface CliBootstrapDocument {
    @Serializable @SerialName("unavailable") data object Unavailable : CliBootstrapDocument
    @Serializable @SerialName("invalid") data object Invalid : CliBootstrapDocument
    @Serializable @SerialName("starting") data class Starting(
        val attemptId: String,
        val phase: String,
        val completedPhases: Int,
        val totalPhases: Int,
        val gradleJvm: GradleJvmSelectionObservation,
    ) : CliBootstrapDocument
    @Serializable @SerialName("ready") data class Ready(
        val attemptId: String,
        val gradleJvm: GradleJvmSelectionObservation,
        val phase: String = "ready",
        val completedPhases: Int = SemanticRuntimeBootstrapPhase.entries.size,
        val totalPhases: Int = SemanticRuntimeBootstrapPhase.entries.size,
    ) : CliBootstrapDocument
    @Serializable @SerialName("rejected") data class Rejected(
        val attemptId: String,
        val phase: String,
        val completedPhases: Int,
        val totalPhases: Int,
        val cause: String,
        val correctiveAction: String,
        val gradleJvm: GradleJvmSelectionObservation,
    ) : CliBootstrapDocument
}

@Serializable
private data class CliBootstrapRejectedDocument(
    val status: String,
    val boundary: String,
    val reason: String,
    val bootstrap: CliBootstrapDocument,
)

private fun RuntimeBootstrapObservation.document(): CliBootstrapDocument = when (this) {
    RuntimeBootstrapObservation.Unavailable -> CliBootstrapDocument.Unavailable
    RuntimeBootstrapObservation.Invalid -> CliBootstrapDocument.Invalid
    is RuntimeBootstrapObservation.Observed -> state.document()
}

private fun SemanticRuntimeBootstrapState.document(): CliBootstrapDocument = when (this) {
    is SemanticRuntimeBootstrapState.Starting -> CliBootstrapDocument.Starting(
        attemptId.value, phase.wireName, phase.completedPhases, phase.totalPhases, gradleJvm,
    )
    is SemanticRuntimeBootstrapState.Ready -> CliBootstrapDocument.Ready(attemptId.value, gradleJvm)
    is SemanticRuntimeBootstrapState.Rejected -> CliBootstrapDocument.Rejected(
        attemptId.value, phase.wireName, phase.completedPhases, phase.totalPhases,
        failure.wireName, correctiveAction().instruction, gradleJvm,
    )
}

private val bootstrapRejectionFactory = CliJsonDocument.generated(CliBootstrapRejectedDocument.serializer())
