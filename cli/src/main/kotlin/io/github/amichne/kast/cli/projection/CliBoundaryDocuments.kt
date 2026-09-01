package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliTextDocument
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
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityMismatch
import io.github.amichne.kast.protocol.wire.metadata.HostedCapabilitySetFailure
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorFailure
import kotlinx.serialization.SerialName
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
        is RuntimeAdmissionFailure.IdeDescriptorRejected -> runtimeBoundaryFactory.create(
            CliRuntimeBoundaryRejectedDocument(
                status = "rejected",
                boundary = "runtime",
                reason = failure.outputReason(),
                details = failure.failure.outputDetails(),
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
private data class CliRuntimeBoundaryRejectedDocument(
    val status: String,
    val boundary: String,
    val reason: String,
    val details: CliIdeDescriptorFailureDocument,
)

@Serializable
internal sealed interface CliIdeDescriptorFailureDocument {
    @Serializable
    @SerialName("malformed-document")
    data object MalformedDocument : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("non-canonical-document")
    data object NonCanonicalDocument : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("unsupported-schema")
    data object UnsupportedSchema : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("unsupported-host-kind")
    data object UnsupportedHostKind : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("unsupported-framing")
    data object UnsupportedFraming : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("invalid-canonical-root")
    data class InvalidCanonicalRoot(val failure: String) : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("invalid-socket-path")
    data class InvalidSocketPath(val failure: String) : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("invalid-process-id")
    data class InvalidProcessId(val failure: String) : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("invalid-runtime-epoch")
    data class InvalidRuntimeEpoch(val failure: String) : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("compatibility-rejected")
    data class CompatibilityRejected(
        val failure: CliCompatibilityFailureDocument,
    ) : CliIdeDescriptorFailureDocument

    @Serializable
    @SerialName("hosted-capabilities-rejected")
    data class HostedCapabilitiesRejected(
        val failure: CliHostedCapabilitiesFailureDocument,
    ) : CliIdeDescriptorFailureDocument
}

@Serializable
internal sealed interface CliCompatibilityFailureDocument {
    @Serializable
    @SerialName("malformed")
    data class Malformed(
        val field: String,
        val syntax: String,
    ) : CliCompatibilityFailureDocument

    @Serializable
    @SerialName("mismatch")
    data class IdentityMismatch(
        val field: String,
        val expected: String,
        val observed: String,
    ) : CliCompatibilityFailureDocument

    @Serializable
    @SerialName("capability-set-mismatch")
    data class CapabilitySetMismatch(
        val field: String,
        val expected: List<String>,
        val observed: List<String>,
    ) : CliCompatibilityFailureDocument

    @Serializable
    @SerialName("unknown-capability")
    data class UnknownCapability(val operationId: String) : CliCompatibilityFailureDocument

    @Serializable
    @SerialName("unsupported-capability")
    data class UnsupportedCapability(val operationId: String) : CliCompatibilityFailureDocument

    @Serializable
    @SerialName("duplicate-capability")
    data class DuplicateCapability(val operationId: String) : CliCompatibilityFailureDocument
}

@Serializable
internal sealed interface CliHostedCapabilitiesFailureDocument {
    @Serializable
    @SerialName("malformed-operation-id")
    data class MalformedOperationId(val failure: String) : CliHostedCapabilitiesFailureDocument

    @Serializable
    @SerialName("unknown-operation")
    data class UnknownOperation(val operationId: String) : CliHostedCapabilitiesFailureDocument

    @Serializable
    @SerialName("unsupported-intent")
    data class UnsupportedIntent(val operationId: String) : CliHostedCapabilitiesFailureDocument

    @Serializable
    @SerialName("duplicate-operation")
    data class DuplicateOperation(val operationId: String) : CliHostedCapabilitiesFailureDocument

    @Serializable
    @SerialName("duplicate-intent")
    data class DuplicateIntent(
        val operationId: String,
        val intent: String,
    ) : CliHostedCapabilitiesFailureDocument

    @Serializable
    @SerialName("canonical-projection-mismatch")
    data object CanonicalProjectionMismatch : CliHostedCapabilitiesFailureDocument
}

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
private val statusWithoutCacheFactory =
    CliJsonDocument.generated(CliStatusWithoutCacheDocument.serializer())
private val statusWithCacheFactory =
    CliJsonDocument.generated(CliStatusWithCacheDocument.serializer())
private val boundaryFactory =
    CliJsonDocument.generated(CliBoundaryRejectedDocument.serializer())
private val runtimeBoundaryFactory =
    CliJsonDocument.generated(CliRuntimeBoundaryRejectedDocument.serializer())
private val usageFactory =
    CliJsonDocument.generated(CliUsageRejectedDocument.serializer())

internal fun IdeEndpointDescriptorFailure.outputDetails(): CliIdeDescriptorFailureDocument =
    when (this) {
        IdeEndpointDescriptorFailure.MalformedDocument ->
            CliIdeDescriptorFailureDocument.MalformedDocument
        IdeEndpointDescriptorFailure.NonCanonicalDocument ->
            CliIdeDescriptorFailureDocument.NonCanonicalDocument
        IdeEndpointDescriptorFailure.UnsupportedSchema ->
            CliIdeDescriptorFailureDocument.UnsupportedSchema
        IdeEndpointDescriptorFailure.UnsupportedHostKind ->
            CliIdeDescriptorFailureDocument.UnsupportedHostKind
        IdeEndpointDescriptorFailure.UnsupportedFraming ->
            CliIdeDescriptorFailureDocument.UnsupportedFraming
        is IdeEndpointDescriptorFailure.InvalidCanonicalRoot ->
            CliIdeDescriptorFailureDocument.InvalidCanonicalRoot(failure.outputName())
        is IdeEndpointDescriptorFailure.InvalidSocketPath ->
            CliIdeDescriptorFailureDocument.InvalidSocketPath(failure.outputName())
        is IdeEndpointDescriptorFailure.InvalidProcessId ->
            CliIdeDescriptorFailureDocument.InvalidProcessId(failure.outputName())
        is IdeEndpointDescriptorFailure.InvalidRuntimeEpoch ->
            CliIdeDescriptorFailureDocument.InvalidRuntimeEpoch(failure.outputName())
        is IdeEndpointDescriptorFailure.CompatibilityRejected ->
            CliIdeDescriptorFailureDocument.CompatibilityRejected(failure.outputDetails())
        is IdeEndpointDescriptorFailure.HostedCapabilitiesRejected ->
            CliIdeDescriptorFailureDocument.HostedCapabilitiesRejected(failure.outputDetails())
    }

private fun IdeHostCompatibilityFailure.outputDetails(): CliCompatibilityFailureDocument =
    when (this) {
        is IdeHostCompatibilityFailure.Malformed -> CliCompatibilityFailureDocument.Malformed(
            field.outputName(),
            syntax.outputName(),
        )
        is IdeHostCompatibilityFailure.Mismatch -> mismatch.outputDetails()
        is IdeHostCompatibilityFailure.UnknownCapability ->
            CliCompatibilityFailureDocument.UnknownCapability(operationId.value)
        is IdeHostCompatibilityFailure.UnsupportedCapability ->
            CliCompatibilityFailureDocument.UnsupportedCapability(operation.id.value)
        is IdeHostCompatibilityFailure.DuplicateCapability ->
            CliCompatibilityFailureDocument.DuplicateCapability(capability.operation.id.value)
    }

private fun IdeHostCompatibilityMismatch.outputDetails(): CliCompatibilityFailureDocument =
    when (this) {
        is IdeHostCompatibilityMismatch.IdeBuild -> identityMismatch(
            field.outputName(),
            expected.value,
            observed.value,
        )
        is IdeHostCompatibilityMismatch.KotlinPluginBuild -> identityMismatch(
            field.outputName(),
            expected.value,
            observed.value,
        )
        is IdeHostCompatibilityMismatch.KastPluginVersion -> identityMismatch(
            field.outputName(),
            expected.value,
            observed.value,
        )
        is IdeHostCompatibilityMismatch.RuntimeProtocol -> identityMismatch(
            field.outputName(),
            expected.value,
            observed.value,
        )
        is IdeHostCompatibilityMismatch.OperationRegistry -> identityMismatch(
            field.outputName(),
            expected.value,
            observed.value,
        )
        is IdeHostCompatibilityMismatch.WireSchema -> identityMismatch(
            field.outputName(),
            expected.value,
            observed.value,
        )
        is IdeHostCompatibilityMismatch.Capabilities ->
            CliCompatibilityFailureDocument.CapabilitySetMismatch(
                field = field.outputName(),
                expected = expected.capabilities.map { it.operation.id.value },
                observed = observed.capabilities.map { it.operation.id.value },
            )
    }

private fun HostedCapabilitySetFailure.outputDetails(): CliHostedCapabilitiesFailureDocument =
    when (this) {
        is HostedCapabilitySetFailure.MalformedOperationId ->
            CliHostedCapabilitiesFailureDocument.MalformedOperationId(failure.outputName())
        is HostedCapabilitySetFailure.UnknownOperation ->
            CliHostedCapabilitiesFailureDocument.UnknownOperation(operationId.value)
        is HostedCapabilitySetFailure.UnsupportedIntent ->
            CliHostedCapabilitiesFailureDocument.UnsupportedIntent(operation.id.value)
        is HostedCapabilitySetFailure.DuplicateOperation ->
            CliHostedCapabilitiesFailureDocument.DuplicateOperation(operation.id.value)
        is HostedCapabilitySetFailure.DuplicateIntent ->
            CliHostedCapabilitiesFailureDocument.DuplicateIntent(
                operation.id.value,
                intent.identity,
            )
        HostedCapabilitySetFailure.CanonicalProjectionMismatch ->
            CliHostedCapabilitiesFailureDocument.CanonicalProjectionMismatch
    }

private fun identityMismatch(
    field: String,
    expected: String,
    observed: String,
): CliCompatibilityFailureDocument = CliCompatibilityFailureDocument.IdentityMismatch(
    field,
    expected,
    observed,
)

private fun Enum<*>.outputName(): String = name.lowercase().replace('_', '-')
