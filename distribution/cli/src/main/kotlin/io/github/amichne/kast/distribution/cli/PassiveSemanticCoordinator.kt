package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.CoordinatorHostProfile
import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

private const val READINESS_SCHEMA_VERSION = 3
private val readinessJson = Json { ignoreUnknownKeys = true }

/** The status facts of the service readiness record that the coordinator publishes beside its private socket. */
@Serializable
@JsonClassDiscriminator("state")
@OptIn(ExperimentalSerializationApi::class)
private sealed interface CoordinatorReadiness {
    val schemaVersion: Int
    val serviceIdentity: String

    @Serializable
    @SerialName("starting")
    data class Starting(
        override val schemaVersion: Int,
        override val serviceIdentity: String,
        val startupProcess: RecordedProcess? = null,
    ) : CoordinatorReadiness

    @Serializable
    @SerialName("ready")
    data class Ready(
        override val schemaVersion: Int,
        override val serviceIdentity: String,
        val serviceInstanceId: String,
        val semanticPeer: RecordedPeer? = null,
    ) : CoordinatorReadiness

    @Serializable
    @SerialName("rejected")
    data class Rejected(override val schemaVersion: Int, override val serviceIdentity: String) : CoordinatorReadiness
}

@Serializable private data class RecordedProcess(val pid: Long, val startedAt: String)

@Serializable private data class RecordedPeer(val pid: Long, val startedAt: String, val installationId: String)

/**
 * Observes the coordinator that serves semantic requests through its own readiness record and status exchange. Null
 * leaves the observation to the Codex broker, whose records these are not.
 */
internal fun observeSemanticCoordinator(
    installation: Path,
    exchange: (Path, String) -> String?,
): PassiveRuntimeObservation? {
    val manifest =
        when (val read = readRuntimeManifest(installation)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return null
        }
    val state = InstallationLayout.of(installation).state
    val path = state.readiness(CoordinatorHostProfile.of(manifest.codexHome))
    if (Files.notExists(path, NOFOLLOW_LINKS)) return unpublishedCoordinator(state)
    val raw =
        readBoundedFile(path, MAXIMUM_RUNTIME_RECORD_BYTES)
            ?: return rejected(RuntimeObservationFailure.READINESS_UNAVAILABLE)
    val record =
        try {
            readinessJson.decodeFromString<CoordinatorReadiness>(raw)
        } catch (_: SerializationException) {
            return rejected(RuntimeObservationFailure.READINESS_REJECTED)
        }
    if (record.serviceIdentity != "sha256:${sha256Text(installation.toString())}") return null
    if (record.schemaVersion != READINESS_SCHEMA_VERSION) return rejected(RuntimeObservationFailure.READINESS_REJECTED)
    return when (record) {
        is CoordinatorReadiness.Rejected -> rejected(RuntimeObservationFailure.COORDINATOR_STARTUP_REJECTED)
        is CoordinatorReadiness.Starting ->
            if (record.startupProcess?.let { processExited(it.pid, it.startedAt) } == true)
                rejected(RuntimeObservationFailure.COORDINATOR_NOT_RUNNING)
            else rejected(RuntimeObservationFailure.COORDINATOR_STARTING)
        is CoordinatorReadiness.Ready -> observePublishedCoordinator(installation, record, exchange)
    }
}

/** A broker epoch names an earlier broker, and a socket without its record names no owner. */
private fun unpublishedCoordinator(state: InstallationLayout.State): PassiveRuntimeObservation? =
    when {
        !Files.notExists(state.epoch, NOFOLLOW_LINKS) -> null
        Files.notExists(state.run.coordinatorSocket, NOFOLLOW_LINKS) ->
            rejected(RuntimeObservationFailure.COORDINATOR_NOT_RUNNING)
        else -> rejected(RuntimeObservationFailure.READINESS_UNAVAILABLE)
    }

private fun observePublishedCoordinator(
    installation: Path,
    record: CoordinatorReadiness.Ready,
    exchange: (Path, String) -> String?,
): PassiveRuntimeObservation {
    val peer = record.semanticPeer ?: return rejected(RuntimeObservationFailure.READINESS_REJECTED)
    val generation =
        when (val admission = RuntimeServiceGeneration.admit(record.serviceInstanceId)) {
            is RuntimeGenerationAdmission.Admitted -> admission.generation
            RuntimeGenerationAdmission.Rejected -> return rejected(RuntimeObservationFailure.READINESS_REJECTED)
        }
    if (processExited(peer.pid, peer.startedAt)) return rejected(RuntimeObservationFailure.COORDINATOR_NOT_RUNNING)
    val socket =
        when (val selected = privateRuntimeSocket(installation)) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return rejected(selected.failure)
        }
    // The semantic service has no durable epoch; its generation stands in for one.
    val projection =
        when (val read = exchangeStatus(socket, peer.installationId, generation.value, generation, exchange)) {
            is Refinement.Refined -> read.value
            is Refinement.Rejected -> return rejected(read.failure)
        }
    val noBroker = RuntimeObservationFailure.BROKER_NOT_OBSERVED.name
    return PassiveRuntimeObservation.Observed(
        generation,
        projection.observedVersion(),
        // Both facts count Codex work only, so this coordinator's empty values say nothing about semantic workspaces.
        activeWorkspaces = Observation.unavailable(noBroker),
        liveConnections = Observation.unavailable(noBroker),
        projectHostedServices(projection.hostedServices),
    )
}

private fun rejected(failure: RuntimeObservationFailure) = PassiveRuntimeObservation.Rejected(failure)

/** True only when the recorded incarnation provably ended; an unreadable process leaves the exchange to decide. */
private fun processExited(pid: Long, startedAt: String): Boolean {
    if (pid <= 0) return false
    val started =
        try {
            Instant.parse(startedAt).toEpochMilli()
        } catch (_: DateTimeParseException) {
            return false
        }
    return when (val observed = NativeLifecycleProcesses.observe(pid)) {
        LifecycleProcessObservation.Absent -> true
        LifecycleProcessObservation.Unavailable -> false
        is LifecycleProcessObservation.IdentityOnly -> observed.startEpochMillis != started
        is LifecycleProcessObservation.Present -> observed.snapshot.startEpochMillis != started
    }
}
