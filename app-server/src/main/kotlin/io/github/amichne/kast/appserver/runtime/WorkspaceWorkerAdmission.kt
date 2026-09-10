package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.BrokerServiceGeneration
import io.github.amichne.kast.appserver.BrokerWorkspaceId
import io.github.amichne.kast.appserver.WorkspaceRegistration
import io.github.amichne.kast.appserver.protocol.ThreadBindingOwner
import io.github.amichne.kast.distribution.contract.IndexerHeapSize
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

@JvmInline
internal value class WorkerMemoryMiB private constructor(val value: Long) {
    companion object {
        val Zero = WorkerMemoryMiB(0)
        fun admit(raw: Long): Refinement<WorkerMemoryMiB, WorkerPolicyFailure> =
            if (raw >= 0) Refinement.Refined(WorkerMemoryMiB(raw)) else Refinement.Rejected(WorkerPolicyFailure.MEMORY_REJECTED)
    }
}

internal enum class WorkerPolicyFailure { RESIDENT_LIMIT_REJECTED, STARTUP_LIMIT_REJECTED, MEMORY_REJECTED, MEMORY_OVERFLOW }

/** Declared reservation accounting, not a claim about observed process footprint or physical fit. */
internal class DeclaredWorkerMemory private constructor(
    val heap: IndexerHeapSize,
    val nativeAllowance: WorkerMemoryMiB,
    val gradleAllowance: WorkerMemoryMiB,
    val total: WorkerMemoryMiB,
) {
    fun matches(other: DeclaredWorkerMemory): Boolean = heap == other.heap && nativeAllowance == other.nativeAllowance && gradleAllowance == other.gradleAllowance

    companion object {
        fun admit(heap: IndexerHeapSize, nativeMiB: Long, gradleMiB: Long): Refinement<DeclaredWorkerMemory, WorkerPolicyFailure> {
            val native = when (val admitted = WorkerMemoryMiB.admit(nativeMiB)) { is Refinement.Refined -> admitted.value; is Refinement.Rejected -> return admitted }
            val gradle = when (val admitted = WorkerMemoryMiB.admit(gradleMiB)) { is Refinement.Refined -> admitted.value; is Refinement.Rejected -> return admitted }
            val heapMiB = heap.mebibytes.toLong()
            if (nativeMiB > Long.MAX_VALUE - heapMiB || gradleMiB > Long.MAX_VALUE - heapMiB - nativeMiB) return Refinement.Rejected(WorkerPolicyFailure.MEMORY_OVERFLOW)
            val total = when (val admitted = WorkerMemoryMiB.admit(heapMiB + nativeMiB + gradleMiB)) { is Refinement.Refined -> admitted.value; is Refinement.Rejected -> return admitted }
            return Refinement.Refined(DeclaredWorkerMemory(heap, native, gradle, total))
        }
    }
}

internal class WorkerAdmissionPolicy private constructor(
    val maximumResident: Int,
    val maximumStarting: Int,
    val aggregate: WorkerMemoryMiB,
) {
    companion object {
        fun admit(maximumResident: Int, maximumStarting: Int, aggregateMiB: Long): Refinement<WorkerAdmissionPolicy, WorkerPolicyFailure> {
            if (maximumResident !in 1..io.github.amichne.kast.distribution.contract.configuration.WorkerCountLimit.Maximum) return Refinement.Rejected(WorkerPolicyFailure.RESIDENT_LIMIT_REJECTED)
            if (maximumStarting !in 1..maximumResident) return Refinement.Rejected(WorkerPolicyFailure.STARTUP_LIMIT_REJECTED)
            if (aggregateMiB <= 0) return Refinement.Rejected(WorkerPolicyFailure.MEMORY_REJECTED)
            val aggregate = when (val admitted = WorkerMemoryMiB.admit(aggregateMiB)) { is Refinement.Refined -> admitted.value; is Refinement.Rejected -> return admitted }
            return Refinement.Refined(WorkerAdmissionPolicy(maximumResident, maximumStarting, aggregate))
        }
    }
}

internal data class WorkspaceWorkerIdentity(
    val owner: ThreadBindingOwner.Installation,
    val serviceGeneration: BrokerServiceGeneration,
    val workspace: WorkspaceRegistration,
)

@JvmInline
internal value class WorkerReservationId private constructor(val value: UUID) {
    companion object {
        fun fresh(): WorkerReservationId = WorkerReservationId(UUID.randomUUID())
        fun admit(raw: String): Refinement<WorkerReservationId, WorkerAdmissionFailure> = try {
            val parsed = UUID.fromString(raw)
            if (parsed.toString() == raw) Refinement.Refined(WorkerReservationId(parsed)) else Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_UNKNOWN)
        } catch (_: IllegalArgumentException) { Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_UNKNOWN) }
    }
}

internal fun interface WorkerReservationIdSource { fun next(): WorkerReservationId }

internal class WorkerReservation internal constructor(
    val id: WorkerReservationId,
    val identity: WorkspaceWorkerIdentity,
    val memory: DeclaredWorkerMemory,
)

/** A consumed reservation. Publication additionally checks this exact permit against the active ledger. */
internal class WorkerLaunchPermit internal constructor(val reservation: WorkerReservation)
internal class WorkspaceWorkerRoute internal constructor(
    val reservation: WorkerReservation,
    val bootstrapAttempt: SemanticRuntimeBootstrapAttemptId,
)

internal enum class WorkerAdmissionFailure {
    COORDINATOR_UNAVAILABLE, INSTALLATION_EPOCH_MISMATCH, SERVICE_GENERATION_MISMATCH,
    RESOURCE_CONFIGURATION_CONFLICT, RESIDENT_CAPACITY_EXCEEDED, STARTUP_CAPACITY_EXCEEDED,
    AGGREGATE_RESERVATION_EXCEEDED, RESERVATION_UNKNOWN, RESERVATION_IDENTITY_MISMATCH,
    RESERVATION_ALREADY_CONSUMED, PUBLICATION_REJECTED, RECOVERY_REQUIRED,
    STARTUP_FAILED, RETIREMENT_UNPROVEN,
}

internal sealed interface WorkerReadiness {
    data class Ready(val route: WorkspaceWorkerRoute) : WorkerReadiness
    data class Rejected(val failure: WorkerAdmissionFailure) : WorkerReadiness
}

/** Awaiting clients receive no cancellation capability over service-owned shared startup. */
internal class WorkerReadinessAwaiter internal constructor(private val shared: CompletableDeferred<WorkerReadiness>) {
    suspend fun await(): WorkerReadiness = shared.await()
}

internal sealed interface WorkerDemand {
    data class Start(val reservation: WorkerReservation, val readiness: WorkerReadinessAwaiter) : WorkerDemand
    data class Awaiting(val readiness: WorkerReadinessAwaiter) : WorkerDemand
    data class Ready(val route: WorkspaceWorkerRoute) : WorkerDemand
    data class Rejected(val failure: WorkerAdmissionFailure) : WorkerDemand
}

/** Trusted process boundary must establish exact retirement, not merely loss of reachability. */
internal sealed interface WorkerRetirementObservation {
    data class ExactRetired(val reservation: WorkerReservation) : WorkerRetirementObservation
    data object Unproven : WorkerRetirementObservation
}
internal fun interface WorkerRetirementAuthority {
    suspend fun observe(reservation: WorkerReservation): WorkerRetirementObservation
}
internal enum class WorkerReservationPhase { RESERVED, STARTING, READY, QUARANTINED_STARTUP, QUARANTINED_RUNTIME }
internal data class WorkerReservationStatus(val reservation: WorkerReservation, val phase: WorkerReservationPhase)
internal data class WorkerAdmissionSnapshot(val workers: List<WorkerReservationStatus>, val reserved: WorkerMemoryMiB, val starting: Int)

/** Ledger only. The installed coordinator must own launch effects, startup deadlines and live route observation. */
internal class WorkerAdmissionCoordinator(
    private val owner: ThreadBindingOwner.Installation,
    private val serviceGeneration: BrokerServiceGeneration,
    private val policy: WorkerAdmissionPolicy,
    private val reservationIds: WorkerReservationIdSource,
) {
    private sealed interface Phase {
        data object Reserved : Phase
        data class Starting(val permit: WorkerLaunchPermit) : Phase
        data class Ready(val route: WorkspaceWorkerRoute) : Phase
        data class QuarantinedStartup(val failure: WorkerAdmissionFailure) : Phase
        data class QuarantinedRuntime(val failure: WorkerAdmissionFailure) : Phase
    }
    private class Entry(val reservation: WorkerReservation) {
        val completion = CompletableDeferred<WorkerReadiness>()
        val readiness = WorkerReadinessAwaiter(completion)
        var phase: Phase = Phase.Reserved
    }
    private enum class AdmissionState { OPEN, DRAINING }
    private val entries = linkedMapOf<BrokerWorkspaceId, Entry>()
    private var admissionState = AdmissionState.OPEN

    @Synchronized fun request(identity: WorkspaceWorkerIdentity, memory: DeclaredWorkerMemory): WorkerDemand {
        when (val admitted = admitIdentity(identity)) {
            is Refinement.Rejected -> return WorkerDemand.Rejected(admitted.failure)
            is Refinement.Refined -> Unit
        }
        if (admissionState == AdmissionState.DRAINING) return WorkerDemand.Rejected(WorkerAdmissionFailure.COORDINATOR_UNAVAILABLE)
        entries[identity.workspace.id]?.let { entry ->
            if (entry.reservation.identity != identity) return WorkerDemand.Rejected(WorkerAdmissionFailure.RESERVATION_IDENTITY_MISMATCH)
            when (entry.phase) {
                is Phase.QuarantinedStartup, is Phase.QuarantinedRuntime -> return WorkerDemand.Rejected(WorkerAdmissionFailure.RECOVERY_REQUIRED)
                else -> Unit
            }
            if (!entry.reservation.memory.matches(memory)) return WorkerDemand.Rejected(WorkerAdmissionFailure.RESOURCE_CONFIGURATION_CONFLICT)
            return when (val phase = entry.phase) {
                is Phase.Ready -> WorkerDemand.Ready(phase.route)
                Phase.Reserved, is Phase.Starting -> WorkerDemand.Awaiting(entry.readiness)
                is Phase.QuarantinedStartup, is Phase.QuarantinedRuntime -> WorkerDemand.Rejected(WorkerAdmissionFailure.RECOVERY_REQUIRED)
            }
        }
        if (entries.size >= policy.maximumResident) return WorkerDemand.Rejected(WorkerAdmissionFailure.RESIDENT_CAPACITY_EXCEEDED)
        if (entries.values.count { it.phase.isStarting() } >= policy.maximumStarting) return WorkerDemand.Rejected(WorkerAdmissionFailure.STARTUP_CAPACITY_EXCEEDED)
        val reserved = entries.values.sumOf { it.reservation.memory.total.value }
        if (memory.total.value > policy.aggregate.value - reserved) return WorkerDemand.Rejected(WorkerAdmissionFailure.AGGREGATE_RESERVATION_EXCEEDED)
        val id = reservationIds.next()
        if (entries.values.any { it.reservation.id == id }) return WorkerDemand.Rejected(WorkerAdmissionFailure.RESERVATION_IDENTITY_MISMATCH)
        val reservation = WorkerReservation(id, identity, memory)
        val entry = Entry(reservation)
        entries[identity.workspace.id] = entry
        return WorkerDemand.Start(reservation, entry.readiness)
    }

    @Synchronized fun consume(id: WorkerReservationId, identity: WorkspaceWorkerIdentity): Refinement<WorkerLaunchPermit, WorkerAdmissionFailure> {
        when (val admitted = admitIdentity(identity)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
        if (admissionState == AdmissionState.DRAINING) return Refinement.Rejected(WorkerAdmissionFailure.COORDINATOR_UNAVAILABLE)
        val entry = entries.values.singleOrNull { it.reservation.id == id }
            ?: return Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_UNKNOWN)
        if (entry.reservation.identity != identity) return Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_IDENTITY_MISMATCH)
        if (entry.phase != Phase.Reserved) return Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_ALREADY_CONSUMED)
        val permit = WorkerLaunchPermit(entry.reservation)
        entry.phase = Phase.Starting(permit)
        return Refinement.Refined(permit)
    }

    @Synchronized fun publishReady(permit: WorkerLaunchPermit, bootstrapAttempt: SemanticRuntimeBootstrapAttemptId): Refinement<WorkspaceWorkerRoute, WorkerAdmissionFailure> {
        val entry = entries[permit.reservation.identity.workspace.id]
            ?: return Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_UNKNOWN)
        val phase = entry.phase
        if (entry.reservation !== permit.reservation || phase !is Phase.Starting || phase.permit !== permit || admissionState == AdmissionState.DRAINING) {
            return Refinement.Rejected(WorkerAdmissionFailure.PUBLICATION_REJECTED)
        }
        val route = WorkspaceWorkerRoute(entry.reservation, bootstrapAttempt)
        entry.phase = Phase.Ready(route)
        entry.completion.complete(WorkerReadiness.Ready(route))
        return Refinement.Refined(route)
    }

    @Synchronized fun quarantine(reservation: WorkerReservation, failure: WorkerAdmissionFailure): Refinement<WorkerReservation, WorkerAdmissionFailure> {
        val entry = entries[reservation.identity.workspace.id]
            ?: return Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_UNKNOWN)
        if (entry.reservation !== reservation) return Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_IDENTITY_MISMATCH)
        entry.phase = if (entry.phase.isStarting()) Phase.QuarantinedStartup(failure) else Phase.QuarantinedRuntime(failure)
        entry.completion.complete(WorkerReadiness.Rejected(failure))
        return Refinement.Refined(reservation)
    }

    suspend fun retire(reservation: WorkerReservation, authority: WorkerRetirementAuthority): Refinement<WorkerReservation, WorkerAdmissionFailure> {
        when (val admitted = quarantine(reservation, WorkerAdmissionFailure.RETIREMENT_UNPROVEN)) {
            is Refinement.Rejected -> return admitted
            is Refinement.Refined -> Unit
        }
        val observed = try { authority.observe(reservation) } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { WorkerRetirementObservation.Unproven }
        return synchronized(this) {
            val entry = entries[reservation.identity.workspace.id]
            when {
                entry?.reservation !== reservation -> Refinement.Rejected(WorkerAdmissionFailure.RESERVATION_UNKNOWN)
                observed !is WorkerRetirementObservation.ExactRetired || observed.reservation !== reservation -> Refinement.Rejected(WorkerAdmissionFailure.RETIREMENT_UNPROVEN)
                else -> {
                    entries.remove(reservation.identity.workspace.id)
                    Refinement.Refined(reservation)
                }
            }
        }
    }

    /** Disable admission before installation retirement; retained accounting requires exact stop evidence. */
    @Synchronized fun drain() {
        admissionState = AdmissionState.DRAINING
        entries.values.forEach { entry ->
            if (entry.phase.isStarting()) {
                entry.phase = Phase.QuarantinedStartup(WorkerAdmissionFailure.COORDINATOR_UNAVAILABLE)
                entry.completion.complete(WorkerReadiness.Rejected(WorkerAdmissionFailure.COORDINATOR_UNAVAILABLE))
            }
        }
    }

    @Synchronized fun snapshot(): WorkerAdmissionSnapshot {
        val workers = entries.values.map { entry -> WorkerReservationStatus(entry.reservation, when (entry.phase) {
            Phase.Reserved -> WorkerReservationPhase.RESERVED
            is Phase.Starting -> WorkerReservationPhase.STARTING
            is Phase.Ready -> WorkerReservationPhase.READY
            is Phase.QuarantinedStartup -> WorkerReservationPhase.QUARANTINED_STARTUP
            is Phase.QuarantinedRuntime -> WorkerReservationPhase.QUARANTINED_RUNTIME
        }) }
        val reserved = when (val admitted = WorkerMemoryMiB.admit(entries.values.sumOf { it.reservation.memory.total.value })) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("Admitted reservation arithmetic invariant lost")
        }
        return WorkerAdmissionSnapshot(workers, reserved, entries.values.count { it.phase.isStarting() })
    }

    private fun admitIdentity(identity: WorkspaceWorkerIdentity): Refinement<WorkspaceWorkerIdentity, WorkerAdmissionFailure> = when {
        identity.owner != owner -> Refinement.Rejected(WorkerAdmissionFailure.INSTALLATION_EPOCH_MISMATCH)
        identity.serviceGeneration != serviceGeneration -> Refinement.Rejected(WorkerAdmissionFailure.SERVICE_GENERATION_MISMATCH)
        else -> Refinement.Refined(identity)
    }

    private fun Phase.isStarting(): Boolean = when (this) {
        Phase.Reserved, is Phase.Starting, is Phase.QuarantinedStartup -> true
        is Phase.Ready, is Phase.QuarantinedRuntime -> false
    }
}
