@file:Suppress("MagicNumber") // WebSocket frame constants and fixed protocol schema versions.

package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.CoordinatorHostProfile
import io.github.amichne.kast.distribution.contract.HostedServiceStatus
import io.github.amichne.kast.distribution.contract.INSTALLATION_MANIFEST_SCHEMA_VERSION
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

internal const val MAXIMUM_RUNTIME_RECORD_BYTES = 8192L
internal const val LOADED_VERSION_UNAVAILABLE = "loaded_version_unavailable"
internal const val HOST_EVIDENCE_REJECTED = "host_evidence_rejected"
private val observationJson = Json { encodeDefaults = true }

@Serializable
private data class RuntimeStatusRequest(val type: RuntimeRequestType = RuntimeRequestType.STATUS, val version: Int = 1)

@Serializable
private enum class RuntimeRequestType {
    @SerialName("status") STATUS
}

@Serializable
private enum class RuntimeResponseType {
    @SerialName("status") STATUS
}

@Serializable
private data class RuntimeStatusResponse(
    val type: RuntimeResponseType,
    val coordinator: RuntimeCoordinator,
    val projection: RuntimeProjection? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
private data class RuntimeCoordinator(
    val installationId: String,
    val stateEpoch: String,
    val serviceGeneration: String,
    val configurationIdentity: String,
)

@Serializable
internal data class RuntimeProjection(
    val loadedVersion: String?,
    val activeWorkspaces: List<String>,
    val liveConnections: Int?,
    val hostedServices: List<HostedServiceStatus>? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
private data class RuntimeEpoch(val schemaVersion: Int, val installation: String, val epoch: String)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
internal data class RuntimeInstallDocument(val schemaVersion: Int, val installationRoot: String, val codexHome: String)

@Serializable
private enum class ReadinessType {
    @SerialName("ready") READY
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
private data class RuntimeReadiness(
    val state: ReadinessType,
    val schemaVersion: Int,
    val serviceInstanceId: String,
)

@Serializable
internal enum class RuntimeObservationFailure {
    COORDINATOR_NOT_RUNNING,
    COORDINATOR_STARTING,
    COORDINATOR_STARTUP_REJECTED,
    BROKER_NOT_OBSERVED,
    EPOCH_UNAVAILABLE,
    EPOCH_REJECTED,
    MANIFEST_UNAVAILABLE,
    MANIFEST_REJECTED,
    READINESS_UNAVAILABLE,
    READINESS_REJECTED,
    CONFIGURATION_UNAVAILABLE,
    CONFIGURATION_REJECTED,
    SOCKET_UNAVAILABLE,
    EXCHANGE_UNAVAILABLE,
    RESPONSE_REJECTED,
    IDENTITY_MISMATCH,
    PROJECTION_UNAVAILABLE,
    PROJECTION_REJECTED,
}

internal sealed interface PassiveRuntimeObservation {
    val loadedVersion: Observation<String>
    val activeWorkspaces: Observation<List<String>>
    val liveConnections: Observation<Int>
    val hostedServices: Observation<List<HostedServiceStatus>>

    data class Observed(
        val generation: RuntimeServiceGeneration,
        override val loadedVersion: Observation<String>,
        override val activeWorkspaces: Observation<List<String>>,
        override val liveConnections: Observation<Int>,
        override val hostedServices: Observation<List<HostedServiceStatus>>,
    ) : PassiveRuntimeObservation

    data class Rejected(val failure: RuntimeObservationFailure) : PassiveRuntimeObservation {
        override val loadedVersion: Observation<String>
            get() = Observation.unavailable(failure.name)

        override val activeWorkspaces: Observation<List<String>>
            get() = Observation.unavailable(failure.name)

        override val liveConnections: Observation<Int>
            get() = Observation.unavailable(failure.name)

        override val hostedServices: Observation<List<HostedServiceStatus>>
            get() = Observation.unavailable(failure.name)
    }
}

internal fun observeRuntime(
    installation: Path,
    exchange: (Path, String) -> String? = ::boundedExchange,
): PassiveRuntimeObservation =
    observeSemanticCoordinator(installation, exchange) ?: observeBroker(installation, exchange)

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun observeBroker(installation: Path, exchange: (Path, String) -> String?): PassiveRuntimeObservation {
    val layout = InstallationLayout.of(installation)
    val epochRaw =
        readBoundedFile(layout.state.epoch, MAXIMUM_RUNTIME_RECORD_BYTES)
            ?: return PassiveRuntimeObservation.Rejected(missingRuntimeEpoch(installation))
    val epoch =
        try {
            observationJson.decodeFromString<RuntimeEpoch>(epochRaw)
        } catch (_: SerializationException) {
            return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.EPOCH_REJECTED)
        }
    if (
        epoch.schemaVersion != 1 ||
            !epoch.installation.startsWith("sha256:") ||
            !validSha256(epoch.installation.removePrefix("sha256:"))
    )
        return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.EPOCH_REJECTED)
    val manifest =
        when (val read = readRuntimeManifest(installation)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return PassiveRuntimeObservation.Rejected(read.failure)
        }
    val readinessRaw =
        readBoundedFile(
            layout.state.readiness(CoordinatorHostProfile.of(manifest.codexHome)),
            MAXIMUM_RUNTIME_RECORD_BYTES,
        ) ?: return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.READINESS_UNAVAILABLE)
    val readiness =
        try {
            observationJson.decodeFromString<RuntimeReadiness>(readinessRaw)
        } catch (_: SerializationException) {
            return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.READINESS_REJECTED)
        }
    if (readiness.schemaVersion != 3)
        return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.READINESS_REJECTED)
    val generation =
        when (val admission = RuntimeServiceGeneration.admit(readiness.serviceInstanceId)) {
            is RuntimeGenerationAdmission.Admitted -> admission.generation
            RuntimeGenerationAdmission.Rejected ->
                return PassiveRuntimeObservation.Rejected(RuntimeObservationFailure.READINESS_REJECTED)
        }
    val socket =
        when (val selected = runtimeSocket(installation, manifest.codexHome)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return PassiveRuntimeObservation.Rejected(selected.failure)
        }
    val projection =
        when (val read = exchangeStatus(socket, epoch.installation, epoch.epoch, generation, exchange)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return PassiveRuntimeObservation.Rejected(read.failure)
        }
    return PassiveRuntimeObservation.Observed(
        generation,
        projection.observedVersion(),
        Observation.verified(projection.activeWorkspaces),
        projection.liveConnections?.takeIf { it >= 0 }?.let(Observation.Companion::verified)
            ?: Observation.unavailable("connection_count_unavailable"),
        projectHostedServices(projection.hostedServices),
    )
}

internal fun readRuntimeManifest(installation: Path): Refinement<RuntimeInstallDocument, RuntimeObservationFailure> {
    val raw =
        readBoundedFile(InstallationLayout.of(installation).payload.manifest, 67_108_864)
            ?: return Refinement.Rejected(RuntimeObservationFailure.MANIFEST_UNAVAILABLE)
    val manifest =
        try {
            observationJson.decodeFromString<RuntimeInstallDocument>(raw)
        } catch (_: SerializationException) {
            return Refinement.Rejected(RuntimeObservationFailure.MANIFEST_REJECTED)
        }
    return if (
        manifest.schemaVersion != INSTALLATION_MANIFEST_SCHEMA_VERSION ||
            manifest.installationRoot != installation.toString()
    )
        Refinement.Rejected(RuntimeObservationFailure.MANIFEST_REJECTED)
    else Refinement.Refined(manifest)
}

/** One bounded status exchange, admitted only from the exact published owner. */
internal fun exchangeStatus(
    socket: Path,
    installationId: String,
    stateEpoch: String,
    generation: RuntimeServiceGeneration,
    exchange: (Path, String) -> String?,
): Refinement<RuntimeProjection, RuntimeObservationFailure> {
    val response =
        exchange(socket, runtimeStatusRequestDocument())
            ?: return Refinement.Rejected(RuntimeObservationFailure.EXCHANGE_UNAVAILABLE)
    val status =
        try {
            observationJson.decodeFromString<RuntimeStatusResponse>(response)
        } catch (_: SerializationException) {
            return Refinement.Rejected(RuntimeObservationFailure.RESPONSE_REJECTED)
        }
    if (
        status.coordinator.installationId != installationId ||
            status.coordinator.stateEpoch != stateEpoch ||
            status.coordinator.serviceGeneration != generation.value
    )
        return Refinement.Rejected(RuntimeObservationFailure.IDENTITY_MISMATCH)
    val projection = status.projection ?: return Refinement.Rejected(RuntimeObservationFailure.PROJECTION_UNAVAILABLE)
    if (
        projection.activeWorkspaces.size > 256 ||
            projection.activeWorkspaces.any {
                try {
                    !Path.of(it).isAbsolute
                } catch (_: IllegalArgumentException) {
                    true
                }
            }
    )
        return Refinement.Rejected(RuntimeObservationFailure.PROJECTION_REJECTED)
    return Refinement.Refined(projection)
}

internal fun RuntimeProjection.observedVersion(): Observation<String> =
    loadedVersion?.takeIf { Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(it) }?.let(Observation.Companion::verified)
        ?: Observation.unavailable(LOADED_VERSION_UNAVAILABLE)

/** Missing broker receipts describe broker evidence, never project or compiler readiness. */
private fun missingRuntimeEpoch(installation: Path): RuntimeObservationFailure =
    if (
        !Files.notExists(InstallationLayout.of(installation).state.epoch, LinkOption.NOFOLLOW_LINKS) ||
            Files.notExists(InstallationLayout.of(installation).configuration.environment, LinkOption.NOFOLLOW_LINKS)
    )
        RuntimeObservationFailure.EPOCH_UNAVAILABLE
    else
        when (val selected = runtimeEndpointMode(installation)) {
            is Refinement.Rejected -> selected.failure
            is Refinement.Refined ->
                when (selected.value) {
                    io.github.amichne.kast.distribution.contract.configuration.BrokerPublicEndpointMode.PRIVATE ->
                        RuntimeObservationFailure.BROKER_NOT_OBSERVED
                    io.github.amichne.kast.distribution.contract.configuration.BrokerPublicEndpointMode.CODEX_CONTROL ->
                        RuntimeObservationFailure.EPOCH_UNAVAILABLE
                }
        }

internal fun runtimeStatusRequestDocument(): String = observationJson.encodeToString(RuntimeStatusRequest())

/** Recheck the management DTO boundary before exposing live compatibility to the command user. */
internal fun projectHostedServices(hosts: List<HostedServiceStatus>?): Observation<List<HostedServiceStatus>> {
    if (hosts.isNullOrEmpty()) return Observation.unavailable("host_evidence_unavailable")
    if (hosts.size > 256 || hosts.any { !validHostedServiceStatus(it) })
        return Observation.unavailable(HOST_EVIDENCE_REJECTED)
    return Observation.verified(hosts)
}

private fun validObservedPath(raw: String): Boolean =
    try {
        val root = Path.of(raw)
        root.isAbsolute && root.normalize() == root
    } catch (_: IllegalArgumentException) {
        false
    }

private fun validHostedServiceStatus(status: HostedServiceStatus): Boolean =
    when (status) {
        is HostedServiceStatus.Compatible ->
            validObservedPath(status.root) &&
                validHostIdentity(status.host, status.hostPid) &&
                validHostedPluginVersion(status.hostedPluginVersion)
        is HostedServiceStatus.Incompatible ->
            validObservedPath(status.root) &&
                validHostIdentity(status.host, status.hostPid) &&
                validHostedPluginVersion(status.hostedPluginVersion)
        is HostedServiceStatus.Unavailable -> validObservedPath(status.root)
        is HostedServiceStatus.RegistryUnavailable -> validObservedPath(status.registryPath)
    }

private fun validHostedPluginVersion(version: String): Boolean =
    Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9]+-g[0-9a-f]{7,40})?").matches(version)

private fun validHostIdentity(host: String, pid: Long): Boolean =
    pid > 0 &&
        try {
            java.util.UUID.fromString(host).toString() == host
        } catch (_: IllegalArgumentException) {
            false
        }
