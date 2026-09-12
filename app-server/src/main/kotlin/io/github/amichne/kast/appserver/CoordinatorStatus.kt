package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal sealed interface CoordinatorStatusRead {
    class Observed(val snapshot: CoordinatorStatusSnapshot) : CoordinatorStatusRead

    data class Rejected(val failure: WorkerControlFailure) : CoordinatorStatusRead
}

@Serializable
internal enum class CoordinatorServiceState {
    READY
}

@Serializable
internal enum class CoordinatorHostAttachment {
    UNOBSERVED,
    PENDING,
    PREPARED,
    REJECTED,
    CLOSED,
}

/** Wire values remain confined to this bounded observation document, never worker admission. */
@Serializable
private data class CoordinatorStatusDocument(
    val status: CoordinatorServiceState,
    val installationId: String,
    val stateEpoch: String,
    val serviceGeneration: String,
    val configurationIdentity: String,
    val reservedMiB: Long,
    val starting: Int,
    val workers: List<CoordinatorWorkerDocument>,
    val hostAttachment: CoordinatorHostAttachment = CoordinatorHostAttachment.UNOBSERVED,
)

@Serializable
internal enum class CoordinatorHeapObservation {
    UNOBSERVED
}

@Serializable
internal data class CoordinatorWorkerDocument(
    val workspaceId: String,
    val reservationId: String,
    val phase: String,
    val reservedMiB: Long,
    val requestedHeapMiB: Int? = null,
    val configurationIdentity: String? = null,
    val heapObservation: CoordinatorHeapObservation = CoordinatorHeapObservation.UNOBSERVED,
)

/** Fully validated finite status shape, already correlated with the selected published owner. */
internal class CoordinatorStatusSnapshot private constructor(private val observed: CoordinatorStatusDocument) {
    val hostAttachment: CoordinatorHostAttachment
        get() = observed.hostAttachment

    val configurationIdentity: String
        get() = observed.configurationIdentity

    val workers: List<CoordinatorWorkerDocument>
        get() = observed.workers

    val serviceGeneration: String
        get() = observed.serviceGeneration

    fun document(): JsonObject = Json.encodeToJsonElement(observed).jsonObject

    fun belongsTo(
        owner: io.github.amichne.kast.appserver.protocol.ThreadBindingOwner.Installation,
        service: BrokerServiceStateDocument.Ready,
    ): Boolean =
        observed.installationId == owner.installationId.value &&
            observed.stateEpoch == owner.stateEpoch.value.toString() &&
            observed.serviceGeneration == service.serviceInstanceId

    companion object {
        fun decode(raw: String): Refinement<CoordinatorStatusSnapshot, WorkerControlFailure> =
            try {
                admit(Json.parseToJsonElement(raw).jsonObject)
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(WorkerControlFailure.INVALID_REQUEST)
            }

        fun admit(document: JsonObject): Refinement<CoordinatorStatusSnapshot, WorkerControlFailure> =
            try {
                val value = Json.decodeFromJsonElement<CoordinatorStatusDocument>(document)
                val workers = value.workers
                val valid =
                    value.installationId.matches(Regex("sha256:[0-9a-f]{64}")) &&
                        canonicalUuid(value.stateEpoch) &&
                        canonicalUuid(value.serviceGeneration) &&
                        value.configurationIdentity.matches(Regex("[0-9a-f]{64}")) &&
                        value.reservedMiB == 0L &&
                        value.starting == 0 &&
                        workers.isEmpty()

                if (valid) Refinement.Refined(CoordinatorStatusSnapshot(value))
                else Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
            } catch (_: Exception) {
                Refinement.Rejected(WorkerControlFailure.INVALID_REQUEST)
            }

        private fun canonicalUuid(raw: String): Boolean =
            try {
                UUID.fromString(raw).toString() == raw
            } catch (_: IllegalArgumentException) {
                false
            }
    }
}

/**
 * Maximum serialized widths of this generated closed status shape, including every admitted worker. Requests retain
 * their independent small command bound. No environment override widens either bound.
 */
object CoordinatorStatusProtocol {
    const val maximumWorkers: Int = 0
    const val maximumCommandBytes: Int = 16_384
    const val maximumMessageBytes: Int = 16_384

    internal fun encode(
        installationId: String,
        stateEpoch: String,
        serviceGeneration: String,
        configurationIdentity: String,
        hostAttachment: CoordinatorHostAttachment,
    ): String = Json {
        encodeDefaults = true
    }
        .encodeToString(
            CoordinatorStatusDocument.serializer(),
            CoordinatorStatusDocument(
                CoordinatorServiceState.READY,
                installationId,
                stateEpoch,
                serviceGeneration,
                configurationIdentity,
                0,
                0,
                emptyList(),
                hostAttachment,
            ),
        )
}
