package support.delivery

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import support.plugin.IdeHostReportCapability

@Serializable
private data class Kvp013EndpointDescriptorDocument(
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
    val capabilities: List<String>,
)

internal enum class Kvp013EndpointSchema(val value: String) {
    V2("kast.ide.endpoint.v2"),
}

internal enum class Kvp013EndpointHostKind(val value: String) {
    IDE_PROJECT("IDE_PROJECT"),
}

internal enum class Kvp013EndpointFraming(val value: String) {
    LENGTH_PREFIXED_JSON_V1("length-prefixed-json-v1"),
}

internal enum class Kvp013EndpointDescriptorFailure {
    MALFORMED_DOCUMENT,
    NON_CANONICAL_DOCUMENT,
    SCHEMA_MISMATCH,
    CANONICAL_ROOT_INVALID,
    HOST_KIND_MISMATCH,
    PROCESS_ID_INVALID,
    IDE_BUILD_MISMATCH,
    KOTLIN_PLUGIN_BUILD_MISMATCH,
    KAST_PLUGIN_VERSION_MISMATCH,
    RUNTIME_PROTOCOL_IDENTITY_MISMATCH,
    OPERATION_REGISTRY_DIGEST_MISMATCH,
    WIRE_SCHEMA_DIGEST_MISMATCH,
    SOCKET_PATH_INVALID,
    FRAMING_MISMATCH,
    RUNTIME_EPOCH_INVALID,
    CAPABILITY_SET_MISMATCH,
}

private sealed interface AbsolutePathRefinement {
    data class Complete(val value: String) : AbsolutePathRefinement
    data object Rejected : AbsolutePathRefinement
}

@JvmInline
internal value class Kvp013CanonicalRoot private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: raw root `String -> Kvp013CanonicalRoot.Refinement`.
         * Establishes a non-empty absolute syntactically normalized path. Expected malformed
         * input remains closed [Refinement.Rejected]; raw text leaves only in receipt observations.
         */
        fun refine(raw: String): Refinement = when (
            val path = refineAbsolutePath(raw, MAX_CANONICAL_ROOT_BYTES)
        ) {
            is AbsolutePathRefinement.Complete -> Refinement.Complete(Kvp013CanonicalRoot(path.value))
            AbsolutePathRefinement.Rejected -> Refinement.Rejected
        }
    }

    sealed interface Refinement {
        data class Complete(val root: Kvp013CanonicalRoot) : Refinement
        data object Rejected : Refinement
    }
}

@JvmInline
internal value class Kvp013SocketPath private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: raw socket path `String -> Kvp013SocketPath.Refinement`.
         * Establishes a non-empty absolute syntactically normalized path. Expected malformed
         * input remains closed [Refinement.Rejected]; raw text leaves only in receipt observations.
         */
        fun refine(raw: String): Refinement = when (
            val path = refineAbsolutePath(raw, MAX_UNIX_SOCKET_PATH_BYTES)
        ) {
            is AbsolutePathRefinement.Complete -> Refinement.Complete(Kvp013SocketPath(path.value))
            AbsolutePathRefinement.Rejected -> Refinement.Rejected
        }
    }

    sealed interface Refinement {
        data class Complete(val path: Kvp013SocketPath) : Refinement
        data object Rejected : Refinement
    }
}

@JvmInline
internal value class Kvp013ProcessId private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: raw `Long -> Kvp013ProcessId.Refinement`.
         * Establishes a positive process identity. Expected non-positive input remains closed
         * [Refinement.Rejected]; the number leaves only in receipt observations.
         */
        fun refine(raw: Long): Refinement = if (raw >= 1L) {
            Refinement.Complete(Kvp013ProcessId(raw))
        } else {
            Refinement.Rejected
        }
    }

    sealed interface Refinement {
        data class Complete(val processId: Kvp013ProcessId) : Refinement
        data object Rejected : Refinement
    }
}

@JvmInline
internal value class Kvp013RuntimeEpoch private constructor(val value: Long) {
    companion object {
        /**
         * Proof transition: raw `Long -> Kvp013RuntimeEpoch.Refinement`.
         * Establishes a non-negative IDE-visible runtime epoch. Expected negative input remains
         * closed [Refinement.Rejected]; the number leaves only in receipt observations.
         */
        fun refine(raw: Long): Refinement = if (raw >= 0L) {
            Refinement.Complete(Kvp013RuntimeEpoch(raw))
        } else {
            Refinement.Rejected
        }
    }

    sealed interface Refinement {
        data class Complete(val epoch: Kvp013RuntimeEpoch) : Refinement
        data object Rejected : Refinement
    }
}

internal class Kvp013CompatibilityIdentity private constructor(
    val ideBuild: String,
    val kotlinPluginBuild: String,
    val kastPluginVersion: String,
    val runtimeProtocolIdentity: String,
    val operationRegistryDigest: String,
    val wireSchemaDigest: String,
) {
    companion object {
        /**
         * Proof transition: six raw compatibility fields -> `Refinement`.
         * Establishes the exact syntax required for IDE, Kotlin, Kast, runtime protocol, and both
         * SHA-256 identities. Expected field failures remain finite
         * [Kvp013EndpointDescriptorFailure]; raw values leave only in receipt observations.
         */
        fun refine(
            ideBuild: String,
            kotlinPluginBuild: String,
            kastPluginVersion: String,
            runtimeProtocolIdentity: String,
            operationRegistryDigest: String,
            wireSchemaDigest: String,
        ): Refinement {
            val failure = when {
                !IDE_BUILD_FORMAT.matches(ideBuild) ->
                    Kvp013EndpointDescriptorFailure.IDE_BUILD_MISMATCH
                !KOTLIN_PLUGIN_BUILD_FORMAT.matches(kotlinPluginBuild) ->
                    Kvp013EndpointDescriptorFailure.KOTLIN_PLUGIN_BUILD_MISMATCH
                !KAST_PLUGIN_VERSION_FORMAT.matches(kastPluginVersion) ->
                    Kvp013EndpointDescriptorFailure.KAST_PLUGIN_VERSION_MISMATCH
                !RUNTIME_PROTOCOL_FORMAT.matches(runtimeProtocolIdentity) ->
                    Kvp013EndpointDescriptorFailure.RUNTIME_PROTOCOL_IDENTITY_MISMATCH
                !SHA256_FORMAT.matches(operationRegistryDigest) ->
                    Kvp013EndpointDescriptorFailure.OPERATION_REGISTRY_DIGEST_MISMATCH
                !SHA256_FORMAT.matches(wireSchemaDigest) ->
                    Kvp013EndpointDescriptorFailure.WIRE_SCHEMA_DIGEST_MISMATCH
                else -> return Refinement.Complete(
                    Kvp013CompatibilityIdentity(
                        ideBuild,
                        kotlinPluginBuild,
                        kastPluginVersion,
                        runtimeProtocolIdentity,
                        operationRegistryDigest,
                        wireSchemaDigest,
                    ),
                )
            }
            return Refinement.Rejected(failure)
        }
    }

    sealed interface Refinement {
        data class Complete(val identity: Kvp013CompatibilityIdentity) : Refinement
        data class Rejected(val failure: Kvp013EndpointDescriptorFailure) : Refinement
    }
}

internal class AdmittedKvp013EndpointDescriptor private constructor(
    val canonicalRoot: Kvp013CanonicalRoot,
    val processId: Kvp013ProcessId,
    val socketPath: Kvp013SocketPath,
    val runtimeEpoch: Kvp013RuntimeEpoch,
    val compatibility: Kvp013CompatibilityIdentity,
) {
    companion object {
        /**
         * Proof transition: descriptor JSON `String -> Kvp013EndpointDescriptorAdmission`.
         *
         * Establishes the exact closed v2 descriptor constants, normalized paths, finite numeric
         * identities, compatibility syntax, and canonical capability order. Expected
         * document failures remain finite [Kvp013EndpointDescriptorFailure]; raw values may be
         * extracted only by the Gradle receipt expectation boundary.
         */
        fun admit(raw: String): Kvp013EndpointDescriptorAdmission {
            val document = try {
                descriptorJson.decodeFromString(Kvp013EndpointDescriptorDocument.serializer(), raw)
            } catch (_: SerializationException) {
                return rejected(Kvp013EndpointDescriptorFailure.MALFORMED_DOCUMENT)
            } catch (_: IllegalArgumentException) {
                return rejected(Kvp013EndpointDescriptorFailure.MALFORMED_DOCUMENT)
            }
            if (descriptorJson.encodeToString(
                    Kvp013EndpointDescriptorDocument.serializer(),
                    document,
                ) != raw
            ) {
                return rejected(Kvp013EndpointDescriptorFailure.NON_CANONICAL_DOCUMENT)
            }
            if (document.schema != Kvp013EndpointSchema.V2.value) {
                return rejected(Kvp013EndpointDescriptorFailure.SCHEMA_MISMATCH)
            }
            val root = when (val result = Kvp013CanonicalRoot.refine(document.canonicalRoot)) {
                is Kvp013CanonicalRoot.Refinement.Complete -> result.root
                Kvp013CanonicalRoot.Refinement.Rejected -> {
                    return rejected(Kvp013EndpointDescriptorFailure.CANONICAL_ROOT_INVALID)
                }
            }
            if (document.hostKind != Kvp013EndpointHostKind.IDE_PROJECT.value) {
                return rejected(Kvp013EndpointDescriptorFailure.HOST_KIND_MISMATCH)
            }
            val processId = when (val result = Kvp013ProcessId.refine(document.processId)) {
                is Kvp013ProcessId.Refinement.Complete -> result.processId
                Kvp013ProcessId.Refinement.Rejected -> {
                    return rejected(Kvp013EndpointDescriptorFailure.PROCESS_ID_INVALID)
                }
            }
            val compatibility = when (val result = Kvp013CompatibilityIdentity.refine(
                document.ideBuild,
                document.kotlinPluginBuild,
                document.kastPluginVersion,
                document.runtimeProtocolIdentity,
                document.operationRegistryDigest,
                document.wireSchemaDigest,
            )) {
                is Kvp013CompatibilityIdentity.Refinement.Complete -> result.identity
                is Kvp013CompatibilityIdentity.Refinement.Rejected -> {
                    return rejected(result.failure)
                }
            }
            val socketPath = when (val result = Kvp013SocketPath.refine(document.socketPath)) {
                is Kvp013SocketPath.Refinement.Complete -> result.path
                Kvp013SocketPath.Refinement.Rejected -> {
                    return rejected(Kvp013EndpointDescriptorFailure.SOCKET_PATH_INVALID)
                }
            }
            if (document.framing != Kvp013EndpointFraming.LENGTH_PREFIXED_JSON_V1.value) {
                return rejected(Kvp013EndpointDescriptorFailure.FRAMING_MISMATCH)
            }
            val epoch = when (val result = Kvp013RuntimeEpoch.refine(document.runtimeEpoch)) {
                is Kvp013RuntimeEpoch.Refinement.Complete -> result.epoch
                Kvp013RuntimeEpoch.Refinement.Rejected -> {
                    return rejected(Kvp013EndpointDescriptorFailure.RUNTIME_EPOCH_INVALID)
                }
            }
            if (document.capabilities != expectedCapabilities) {
                return rejected(Kvp013EndpointDescriptorFailure.CAPABILITY_SET_MISMATCH)
            }
            return Kvp013EndpointDescriptorAdmission.Complete(
                AdmittedKvp013EndpointDescriptor(root, processId, socketPath, epoch, compatibility),
            )
        }
    }
}

internal sealed interface Kvp013EndpointDescriptorAdmission {
    data class Complete(val descriptor: AdmittedKvp013EndpointDescriptor) :
        Kvp013EndpointDescriptorAdmission
    data class Rejected(val failure: Kvp013EndpointDescriptorFailure) :
        Kvp013EndpointDescriptorAdmission
}

private const val MAX_CANONICAL_ROOT_BYTES = 4_096
private const val MAX_UNIX_SOCKET_PATH_BYTES = 103
private val descriptorJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}
private val expectedCapabilities = IdeHostReportCapability.entries.map { it.operationId }
private val IDE_BUILD_FORMAT = Regex("[0-9]{3}\\.[0-9]+\\.[0-9]+")
private val KOTLIN_PLUGIN_BUILD_FORMAT = Regex("[0-9]{3}\\.[0-9]+\\.[0-9]+-IJ")
private val KAST_PLUGIN_VERSION_FORMAT =
    Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9]+-g[0-9a-f]{7,40})?")
private val RUNTIME_PROTOCOL_FORMAT = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\\.v[1-9][0-9]*")
private val SHA256_FORMAT = Regex("sha256:[0-9a-f]{64}")

/**
 * Proof transition: raw path `String -> AbsolutePathRefinement`.
 * Establishes a bounded, NUL-free, absolute, syntactically normalized path. Expected malformed
 * input stays closed [AbsolutePathRefinement.Rejected]; raw text returns only as a refined
 * boundary value.
 */
private fun refineAbsolutePath(raw: String, maxBytes: Int): AbsolutePathRefinement {
    if (
        raw.isBlank() ||
        raw.contains('\u0000') ||
        raw.toByteArray(StandardCharsets.UTF_8).size > maxBytes
    ) {
        return AbsolutePathRefinement.Rejected
    }
    val path = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        return AbsolutePathRefinement.Rejected
    }
    val normalized = path.normalize()
    return if (path.isAbsolute && normalized.toString() == raw) {
        AbsolutePathRefinement.Complete(raw)
    } else {
        AbsolutePathRefinement.Rejected
    }
}

private fun rejected(failure: Kvp013EndpointDescriptorFailure) =
    Kvp013EndpointDescriptorAdmission.Rejected(failure)
