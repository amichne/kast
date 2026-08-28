package io.github.amichne.kast.protocol.wire.metadata

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import java.nio.charset.StandardCharsets

private const val MAX_CANONICAL_ROOT_BYTES = 4_096
private const val MAX_UNIX_SOCKET_PATH_BYTES = 103

enum class IdeEndpointSchema(val identity: String) {
    V2("kast.ide.endpoint.v2"),
}

enum class IdeEndpointHostKind(val identity: String) {
    IDE_PROJECT("IDE_PROJECT"),
}

enum class IdeEndpointFraming(val identity: String) {
    LENGTH_PREFIXED_JSON_V1("length-prefixed-json-v1"),
}

enum class IdeEndpointPathFailure {
    BLANK,
    TOO_LONG,
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    CONTAINS_NUL,
}

enum class IdeProcessIdFailure {
    NOT_POSITIVE,
}

enum class IdeRuntimeEpochFailure {
    NEGATIVE,
}

@JvmInline
value class IdeEndpointCanonicalRoot private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<IdeEndpointCanonicalRoot,
         * IdeEndpointPathFailure>`.
         *
         * Establishes a bounded, absolute, normalized POSIX root with no NUL or dot segment.
         * [IdeEndpointPathFailure] is the closed expected failure. Raw text may leave only at the
         * endpoint descriptor serialization boundary.
         */
        fun parse(
            raw: String,
        ): Refinement<IdeEndpointCanonicalRoot, IdeEndpointPathFailure> = refineEndpointPath(
            raw,
            MAX_CANONICAL_ROOT_BYTES,
            ::IdeEndpointCanonicalRoot,
        )
    }
}

@JvmInline
value class IdeUnixSocketPath private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<IdeUnixSocketPath, IdeEndpointPathFailure>`.
         *
         * Establishes a macOS UDS-safe, absolute, normalized POSIX path with no NUL or dot
         * segment. [IdeEndpointPathFailure] is the closed expected failure. Raw text may leave
         * only at the endpoint descriptor or Unix-domain-socket boundary.
         */
        fun parse(raw: String): Refinement<IdeUnixSocketPath, IdeEndpointPathFailure> =
            refineEndpointPath(raw, MAX_UNIX_SOCKET_PATH_BYTES, ::IdeUnixSocketPath)
    }
}

@JvmInline
value class IdeProcessId private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<IdeProcessId, IdeProcessIdFailure>`.
         *
         * Establishes a positive IDE process identity. [IdeProcessIdFailure] is the closed
         * expected failure. The raw number may leave only at process observation or endpoint
         * serialization boundaries.
         */
        fun parse(raw: Long): Refinement<IdeProcessId, IdeProcessIdFailure> = if (raw > 0) {
            Refinement.Refined(IdeProcessId(raw))
        } else {
            Refinement.Rejected(IdeProcessIdFailure.NOT_POSITIVE)
        }
    }
}

@JvmInline
value class IdeRuntimeEpoch private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: `Long -> Refinement<IdeRuntimeEpoch, IdeRuntimeEpochFailure>`.
         *
         * Establishes a non-negative, monotonically comparable IDE runtime epoch.
         * [IdeRuntimeEpochFailure] is the closed expected failure. The raw number may leave only
         * at IDE epoch observation or endpoint serialization boundaries.
         */
        fun parse(raw: Long): Refinement<IdeRuntimeEpoch, IdeRuntimeEpochFailure> = if (raw >= 0) {
            Refinement.Refined(IdeRuntimeEpoch(raw))
        } else {
            Refinement.Rejected(IdeRuntimeEpochFailure.NEGATIVE)
        }
    }
}

data class IdeEndpointDescriptorCandidate(
    val schema: String,
    val canonicalRoot: String,
    val hostKind: String,
    val processId: Long,
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginVersion: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
    val socketPath: String,
    val framing: String,
    val runtimeEpoch: Long,
    val capabilities: List<HostedCapabilityCandidate>,
)

sealed interface IdeEndpointDescriptorFailure {
    data object MalformedDocument : IdeEndpointDescriptorFailure
    data object NonCanonicalDocument : IdeEndpointDescriptorFailure
    data object UnsupportedSchema : IdeEndpointDescriptorFailure
    data object UnsupportedHostKind : IdeEndpointDescriptorFailure
    data object UnsupportedFraming : IdeEndpointDescriptorFailure

    data class InvalidCanonicalRoot(
        val failure: IdeEndpointPathFailure,
    ) : IdeEndpointDescriptorFailure

    data class InvalidSocketPath(
        val failure: IdeEndpointPathFailure,
    ) : IdeEndpointDescriptorFailure

    data class InvalidProcessId(
        val failure: IdeProcessIdFailure,
    ) : IdeEndpointDescriptorFailure

    data class InvalidRuntimeEpoch(
        val failure: IdeRuntimeEpochFailure,
    ) : IdeEndpointDescriptorFailure

    data class CompatibilityRejected(
        val failure: IdeHostCompatibilityFailure,
    ) : IdeEndpointDescriptorFailure

    data class HostedCapabilitiesRejected(
        val failure: HostedCapabilitySetFailure,
    ) : IdeEndpointDescriptorFailure
}

sealed interface IdeEndpointDescriptorAdmission {
    data class Admitted(
        val descriptor: IdeEndpointDescriptorV2,
    ) : IdeEndpointDescriptorAdmission

    data class Rejected(
        val failure: IdeEndpointDescriptorFailure,
    ) : IdeEndpointDescriptorAdmission
}

class IdeEndpointDescriptorV2 private constructor(
    val canonicalRoot: IdeEndpointCanonicalRoot,
    val processId: IdeProcessId,
    val compatibility: AdmittedIdeHostCompatibility,
    val socketPath: IdeUnixSocketPath,
    val runtimeEpoch: IdeRuntimeEpoch,
    val capabilities: HostedCapabilitySet,
) {
    val schema: IdeEndpointSchema = IdeEndpointSchema.V2
    val hostKind: IdeEndpointHostKind = IdeEndpointHostKind.IDE_PROJECT
    val framing: IdeEndpointFraming = IdeEndpointFraming.LENGTH_PREFIXED_JSON_V1

    /**
     * Proof transition: `IdeEndpointDescriptorV2 -> EncodedIdeEndpointDescriptor`.
     *
     * Preserves every admitted endpoint invariant in one canonical generated-serializer document.
     * Raw JSON is exposed only here, at endpoint publication and transport boundaries.
     */
    fun encode(): EncodedIdeEndpointDescriptor = EncodedIdeEndpointDescriptor.from(this)

    companion object {
        /**
         * Proof transition: `String + IdeHostCompatibilityPolicy ->
         * IdeEndpointDescriptorAdmission`.
         *
         * Establishes the exact canonical v2 schema, root, IDE host, process, compatibility tuple,
         * UDS path, framing, epoch, and generated hosted capability set. Malformed, noncanonical,
         * stale, invalid, and incompatible inputs remain [IdeEndpointDescriptorFailure] data. Raw
         * JSON and primitive fields are extracted only inside this wire boundary.
         */
        fun admit(
            document: String,
            policy: IdeHostCompatibilityPolicy,
        ): IdeEndpointDescriptorAdmission = IdeEndpointDescriptorCodec.admit(document, policy)

        /**
         * Proof transition: `IdeEndpointDescriptorCandidate + IdeHostCompatibilityPolicy ->
         * IdeEndpointDescriptorAdmission`.
         *
         * Refines all raw endpoint publication fields into one [IdeEndpointDescriptorV2]. Every
         * expected syntax, identity, path, process, epoch, and capability failure remains closed
         * [IdeEndpointDescriptorFailure] data. Raw values may enter only at an endpoint publisher
         * or descriptor decoder boundary.
         */
        fun create(
            candidate: IdeEndpointDescriptorCandidate,
            policy: IdeHostCompatibilityPolicy,
        ): IdeEndpointDescriptorAdmission {
            if (candidate.schema != IdeEndpointSchema.V2.identity) {
                return rejected(IdeEndpointDescriptorFailure.UnsupportedSchema)
            }
            val root = when (val refined = IdeEndpointCanonicalRoot.parse(candidate.canonicalRoot)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return rejected(
                    IdeEndpointDescriptorFailure.InvalidCanonicalRoot(refined.failure),
                )
            }
            if (candidate.hostKind != IdeEndpointHostKind.IDE_PROJECT.identity) {
                return rejected(IdeEndpointDescriptorFailure.UnsupportedHostKind)
            }
            val processId = when (val refined = IdeProcessId.parse(candidate.processId)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return rejected(
                    IdeEndpointDescriptorFailure.InvalidProcessId(refined.failure),
                )
            }
            val hostedCapabilities = when (val admitted = HostedCapabilitySet.admit(
                candidate.capabilities,
            )) {
                is HostedCapabilitySetAdmission.Admitted -> admitted.capabilities
                is HostedCapabilitySetAdmission.Rejected -> return rejected(
                    IdeEndpointDescriptorFailure.HostedCapabilitiesRejected(admitted.failure),
                )
            }
            val compatibility = when (val admitted = policy.admit(candidate.compatibility())) {
                is IdeHostCompatibilityAdmission.Admitted -> admitted.compatibility
                is IdeHostCompatibilityAdmission.Rejected -> return rejected(
                    IdeEndpointDescriptorFailure.CompatibilityRejected(admitted.failure),
                )
            }
            val socket = when (val refined = IdeUnixSocketPath.parse(candidate.socketPath)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return rejected(
                    IdeEndpointDescriptorFailure.InvalidSocketPath(refined.failure),
                )
            }
            if (candidate.framing != IdeEndpointFraming.LENGTH_PREFIXED_JSON_V1.identity) {
                return rejected(IdeEndpointDescriptorFailure.UnsupportedFraming)
            }
            val epoch = when (val refined = IdeRuntimeEpoch.parse(candidate.runtimeEpoch)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return rejected(
                    IdeEndpointDescriptorFailure.InvalidRuntimeEpoch(refined.failure),
                )
            }
            return IdeEndpointDescriptorAdmission.Admitted(
                IdeEndpointDescriptorV2(
                    root,
                    processId,
                    compatibility,
                    socket,
                    epoch,
                    hostedCapabilities,
                ),
            )
        }
    }
}

private fun IdeEndpointDescriptorCandidate.compatibility() = IdeHostCompatibilityCandidate(
    ideBuild,
    kotlinPluginBuild,
    kastPluginVersion,
    runtimeProtocolIdentity,
    operationRegistryDigest,
    wireSchemaDigest,
    capabilities.map(HostedCapabilityCandidate::operationId),
)

private fun rejected(
    failure: IdeEndpointDescriptorFailure,
) = IdeEndpointDescriptorAdmission.Rejected(failure)

/**
 * Proof transition: `String + Int + ((String) -> Strong) ->
 * Refinement<Strong, IdeEndpointPathFailure>`.
 *
 * Establishes that the raw path is bounded by its UTF-8 byte length, NUL-free, absolute, and
 * lexically normalized before the supplied private constructor creates [Strong].
 * [IdeEndpointPathFailure] is the closed expected failure. Raw text may leave only through the
 * endpoint descriptor or Unix-domain-socket boundary owned by the caller.
 */
private inline fun <Strong> refineEndpointPath(
    raw: String,
    maximumBytes: Int,
    construct: (String) -> Strong,
): Refinement<Strong, IdeEndpointPathFailure> = when {
    raw.isBlank() -> Refinement.Rejected(IdeEndpointPathFailure.BLANK)
    raw.toByteArray(StandardCharsets.UTF_8).size > maximumBytes ->
        Refinement.Rejected(IdeEndpointPathFailure.TOO_LONG)
    '\u0000' in raw -> Refinement.Rejected(IdeEndpointPathFailure.CONTAINS_NUL)
    !raw.startsWith('/') -> Refinement.Rejected(IdeEndpointPathFailure.NOT_ABSOLUTE)
    raw != "/" && (
        raw.endsWith('/') ||
            raw.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." }
    ) -> Refinement.Rejected(IdeEndpointPathFailure.NOT_NORMALIZED)
    else -> Refinement.Refined(construct(raw))
}
