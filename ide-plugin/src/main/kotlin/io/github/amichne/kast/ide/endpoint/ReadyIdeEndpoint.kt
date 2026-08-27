package io.github.amichne.kast.ide.endpoint

import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointCanonicalRoot
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointDescriptorV2
import io.github.amichne.kast.protocol.wire.metadata.IdeEndpointLocation

internal enum class EndpointArtifactRetirement {
    REMOVED,
    ALREADY_ABSENT,
    IDENTITY_MISMATCH,
    IDENTITY_UNAVAILABLE,
    DELETE_FAILED,
}

internal data class ReadyEndpointRetirementEvidence(
    val descriptor: EndpointArtifactRetirement,
    val socket: EndpointArtifactRetirement,
    val directory: EndpointArtifactRetirement,
)

internal sealed interface ReadyEndpointOwnershipRetirement {
    data class Retired(
        val evidence: ReadyEndpointRetirementEvidence,
    ) : ReadyEndpointOwnershipRetirement

    data class Rejected(
        val failure: IdeEndpointRetirementFailure,
    ) : ReadyEndpointOwnershipRetirement
}

/** READY artifact ownership consumed exactly once by the lifecycle retirement transition. */
internal class ReadyEndpointOwnership(
    val directory: OwnedEndpointDirectory,
    val socket: OwnedEndpointPath,
    val descriptor: OwnedPublishedDescriptor,
) {
    /**
     * Proof transition: `ReadyEndpointOwnership -> ReadyEndpointOwnershipRetirement`.
     *
     * Closes the listener, deletes only descriptor/socket/directory paths whose retained physical
     * identities still match, and returns closed [IdeEndpointRetirementFailure] data otherwise.
     * Raw paths and file identities remain inside this filesystem boundary.
     */
    fun retire(): ReadyEndpointOwnershipRetirement {
        socket.close()
        val descriptorRetirement = descriptor.deleteFromOwner()
        val socketRetirement = socket.deleteFromOwner()
        val directoryRetirement = directory.retireIfStillExclusive()
        val evidence = ReadyEndpointRetirementEvidence(
            descriptorRetirement,
            socketRetirement,
            directoryRetirement,
        )
        return when {
            descriptorRetirement == EndpointArtifactRetirement.IDENTITY_MISMATCH -> rejected(
                IdeEndpointRetirementFailure.DESCRIPTOR_IDENTITY_MISMATCH,
            )
            socketRetirement == EndpointArtifactRetirement.IDENTITY_MISMATCH -> rejected(
                IdeEndpointRetirementFailure.SOCKET_IDENTITY_MISMATCH,
            )
            directoryRetirement == EndpointArtifactRetirement.IDENTITY_MISMATCH -> rejected(
                IdeEndpointRetirementFailure.DIRECTORY_IDENTITY_MISMATCH,
            )
            evidence.hasUnavailableIdentity() -> rejected(
                IdeEndpointRetirementFailure.ARTIFACT_IDENTITY_UNAVAILABLE,
            )
            evidence.hasDeleteFailure() -> rejected(
                IdeEndpointRetirementFailure.ARTIFACT_DELETE_FAILED,
            )
            else -> ReadyEndpointOwnershipRetirement.Retired(evidence)
        }
    }

    private fun rejected(
        failure: IdeEndpointRetirementFailure,
    ) = ReadyEndpointOwnershipRetirement.Rejected(failure)
}

private fun ReadyEndpointRetirementEvidence.hasDeleteFailure(): Boolean =
    descriptor == EndpointArtifactRetirement.DELETE_FAILED ||
        socket == EndpointArtifactRetirement.DELETE_FAILED ||
        directory == EndpointArtifactRetirement.DELETE_FAILED

private fun ReadyEndpointRetirementEvidence.hasUnavailableIdentity(): Boolean =
    descriptor == EndpointArtifactRetirement.IDENTITY_UNAVAILABLE ||
        socket == EndpointArtifactRetirement.IDENTITY_UNAVAILABLE ||
        directory == EndpointArtifactRetirement.IDENTITY_UNAVAILABLE

/** Closed lifecycle cause consumed by the sole READY-to-retired transition. */
enum class IdeEndpointRetirementCause {
    PROJECT_OR_PLUGIN_DISPOSAL,
    SERVICE_CANCELLATION,
    SERVING_TERMINATED,
    STALE_PUBLICATION,
    TEST_CLEANUP,
}

/** Closed expected failure to retire only physically retained endpoint artifacts. */
enum class IdeEndpointRetirementFailure {
    DESCRIPTOR_IDENTITY_MISMATCH,
    SOCKET_IDENTITY_MISMATCH,
    DIRECTORY_IDENTITY_MISMATCH,
    ARTIFACT_IDENTITY_UNAVAILABLE,
    ARTIFACT_DELETE_FAILED,
}

/** Sole proof that one formerly READY endpoint has relinquished all retained artifacts. */
class RetiredIdeEndpoint internal constructor(
    val canonicalRoot: IdeEndpointCanonicalRoot,
    val location: IdeEndpointLocation,
    val cause: IdeEndpointRetirementCause,
    internal val evidence: ReadyEndpointRetirementEvidence,
)

/** Closed result of consuming a READY endpoint's retained ownership. */
sealed interface IdeEndpointRetirement {
    data class Retired(
        val endpoint: RetiredIdeEndpoint,
    ) : IdeEndpointRetirement

    data class Rejected(
        val failure: IdeEndpointRetirementFailure,
    ) : IdeEndpointRetirement
}

private sealed interface ReadyIdeEndpointState {
    data object Ready : ReadyIdeEndpointState
    data class RetirementPending(
        val cause: IdeEndpointRetirementCause,
    ) : ReadyIdeEndpointState
    data class RetirementObserved(
        val result: IdeEndpointRetirement,
    ) : ReadyIdeEndpointState
}

/** Sole capability proving a bound exact-root UDS and an admitted published descriptor. */
class ReadyIdeEndpoint internal constructor(
    val canonicalRoot: IdeEndpointCanonicalRoot,
    val descriptor: IdeEndpointDescriptorV2,
    val location: IdeEndpointLocation,
    private val transport: IdeEndpointTransport,
    private val ownership: ReadyEndpointOwnership,
) {
    private var state: ReadyIdeEndpointState = ReadyIdeEndpointState.Ready

    /**
     * Proof transition: `one accepted UDS session -> IdeEndpointConnectionHandling` through the
     * transport created from the retained complete runtime before readiness.
     */
    internal suspend fun serveNext(): IdeEndpointConnectionHandling = transport.serveNext()

    /** Serves sequential sessions until the owned listening socket is no longer available. */
    internal suspend fun serveUntilClosed() {
        while (true) {
            when (val result = serveNext()) {
                IdeEndpointConnectionHandling.Served -> Unit
                is IdeEndpointConnectionHandling.Rejected -> when (result.failure) {
                    IdeEndpointConnectionFailure.ACCEPT_FAILED -> return
                    IdeEndpointConnectionFailure.INVALID_REQUEST_FRAME,
                    IdeEndpointConnectionFailure.DISPATCH_REJECTED,
                    IdeEndpointConnectionFailure.RESPONSE_WRITE_FAILED,
                    IdeEndpointConnectionFailure.SESSION_CLOSE_FAILED,
                    -> Unit
                }
            }
        }
    }

    /**
     * Proof transition: `ReadyIdeEndpoint -> IdeEndpointRetirement`.
     *
     * Consumes the sole READY ownership, closes listener and accepted-client effects, and issues
     * [RetiredIdeEndpoint] only after every still-owned physical artifact is removed. Identity
     * mismatch or deletion failure remains finite [IdeEndpointRetirementFailure]. Repeated calls
     * retry only transient deletion/observation failures; terminal results never touch the
     * filesystem again.
     */
    @Synchronized
    internal fun retire(cause: IdeEndpointRetirementCause): IdeEndpointRetirement = when (
        val current = state
    ) {
        is ReadyIdeEndpointState.RetirementObserved -> current.result
        is ReadyIdeEndpointState.RetirementPending -> retireOwnedArtifacts(current.cause)
        ReadyIdeEndpointState.Ready -> {
            transport.close()
            retireOwnedArtifacts(cause)
        }
    }

    private fun retireOwnedArtifacts(cause: IdeEndpointRetirementCause): IdeEndpointRetirement {
        val result = when (val retirement = ownership.retire()) {
            is ReadyEndpointOwnershipRetirement.Retired -> IdeEndpointRetirement.Retired(
                RetiredIdeEndpoint(canonicalRoot, location, cause, retirement.evidence),
            )
            is ReadyEndpointOwnershipRetirement.Rejected -> IdeEndpointRetirement.Rejected(
                retirement.failure,
            )
        }
        state = if (
            result is IdeEndpointRetirement.Rejected &&
            result.failure.isRetryable()
        ) {
            ReadyIdeEndpointState.RetirementPending(cause)
        } else {
            ReadyIdeEndpointState.RetirementObserved(result)
        }
        return result
    }

    private fun IdeEndpointRetirementFailure.isRetryable(): Boolean =
        this == IdeEndpointRetirementFailure.ARTIFACT_DELETE_FAILED ||
            this == IdeEndpointRetirementFailure.ARTIFACT_IDENTITY_UNAVAILABLE

    /** Test boundary used to inject listening-socket replacement before retirement. */
    @JvmSynthetic
    internal fun closeListeningSocketForTest() = ownership.socket.close()
}
