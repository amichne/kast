package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.runtime.WorkerReservationPhase
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

@Serializable internal enum class CoordinatorServiceState { READY }
@Serializable internal enum class CoordinatorHostAttachment { UNOBSERVED, PENDING, PREPARED, REJECTED, CLOSED }

/** Wire values remain confined to this bounded observation document, never worker admission. */
@Serializable private data class CoordinatorStatusDocument(
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
@Serializable internal enum class CoordinatorHeapObservation { UNOBSERVED }
@Serializable internal data class CoordinatorWorkerDocument(
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
    val hostAttachment: CoordinatorHostAttachment get() = observed.hostAttachment
    val configurationIdentity: String get() = observed.configurationIdentity
    val workers: List<CoordinatorWorkerDocument> get() = observed.workers
    val serviceGeneration: String get() = observed.serviceGeneration
    fun document(): JsonObject = Json.encodeToJsonElement(observed).jsonObject

    companion object {
        fun admit(document: JsonObject): Refinement<CoordinatorStatusSnapshot, WorkerControlFailure> = try {
            val value = Json.decodeFromJsonElement<CoordinatorStatusDocument>(document)
            val workers = value.workers
            var total = 0L
            val valid = value.installationId.matches(Regex("sha256:[0-9a-f]{64}")) &&
                canonicalUuid(value.stateEpoch) && canonicalUuid(value.serviceGeneration) &&
                value.configurationIdentity.matches(Regex("[0-9a-f]{64}")) &&
                value.reservedMiB >= 0 && workers.size <= CoordinatorStatusProtocol.maximumWorkers && value.starting in 0..workers.size &&
                workers.map { it.workspaceId }.distinct().size == workers.size &&
                workers.map { it.reservationId }.distinct().size == workers.size && workers.all { worker ->
                    val bounded = worker.workspaceId.matches(Regex("[0-9a-f]{64}")) && canonicalUuid(worker.reservationId) &&
                        WorkerReservationPhase.entries.any { it.name == worker.phase } && worker.reservedMiB >= 0 &&
                        worker.reservedMiB <= Long.MAX_VALUE - total &&
                        (worker.configurationIdentity == null || worker.configurationIdentity.matches(Regex("[0-9a-f]{64}"))) &&
                        (worker.requestedHeapMiB == null || worker.requestedHeapMiB >= io.github.amichne.kast.distribution.contract.IndexerHeapSize.minimumMebibytes && worker.requestedHeapMiB <= worker.reservedMiB)
                    if (bounded) total += worker.reservedMiB
                    bounded
                } && total == value.reservedMiB
            if (valid) Refinement.Refined(CoordinatorStatusSnapshot(value)) else Refinement.Rejected(WorkerControlFailure.IDENTITY_REJECTED)
        } catch (_: Exception) { Refinement.Rejected(WorkerControlFailure.INVALID_REQUEST) }

        private fun canonicalUuid(raw: String): Boolean = try { UUID.fromString(raw).toString() == raw } catch (_: IllegalArgumentException) { false }
    }
}

/** Maximum serialized widths of this generated closed status shape, including every admitted worker.
 * Requests retain their independent small command bound. No environment override widens either bound.
 */
object CoordinatorStatusProtocol {
    const val maximumWorkers: Int = io.github.amichne.kast.distribution.contract.configuration.WorkerCountLimit.Maximum
    const val maximumCommandBytes: Int = 16_384
    val maximumMessageBytes: Int = Json { encodeDefaults = true }.encodeToString(CoordinatorStatusDocument.serializer(),
        CoordinatorStatusDocument(
            status = CoordinatorServiceState.READY,
            installationId = "sha256:" + "f".repeat(64),
            stateEpoch = "ffffffff-ffff-ffff-ffff-ffffffffffff",
            serviceGeneration = "ffffffff-ffff-ffff-ffff-ffffffffffff",
            configurationIdentity = "f".repeat(64),
            reservedMiB = Long.MAX_VALUE,
            starting = maximumWorkers,
            workers = List(maximumWorkers) {
                CoordinatorWorkerDocument(
                    workspaceId = "f".repeat(64), reservationId = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                    phase = WorkerReservationPhase.entries.maxBy { it.name.length }.name,
                    reservedMiB = Long.MAX_VALUE, requestedHeapMiB = Int.MAX_VALUE,
                    configurationIdentity = "f".repeat(64), heapObservation = CoordinatorHeapObservation.UNOBSERVED,
                )
            },
            hostAttachment = CoordinatorHostAttachment.entries.maxBy { it.name.length },
        )
    ).toByteArray(Charsets.UTF_8).size
}
