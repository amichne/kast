package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.NonEmptyFailures
import io.github.amichne.kast.kernel.Refinement
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class DaemonUpgradeFailure {
    UNAVAILABLE,
    CANDIDATE_REJECTED,
    REQUEST_REJECTED,
    CANDIDATE_CONFLICT,
    UNKNOWN_REQUEST,
    ADMISSION_SEALED,
    NOT_QUIESCENT,
    ALREADY_COMMITTED,
    REQUEST_CANCELLED,
}

@JvmInline
internal value class UpgradeCandidate private constructor(val digest: String) {
    companion object {
        fun admit(raw: String): Refinement<UpgradeCandidate, DaemonUpgradeFailure> =
            if (raw.matches(Regex("[a-f0-9]{64}"))) Refinement.Refined(UpgradeCandidate(raw))
            else Refinement.Rejected(DaemonUpgradeFailure.CANDIDATE_REJECTED)
    }
}

@JvmInline
internal value class UpgradeRequestId private constructor(val value: UUID) {
    companion object {
        fun fresh() = UpgradeRequestId(UUID.randomUUID())

        fun admit(raw: String): Refinement<UpgradeRequestId, DaemonUpgradeFailure> {
            val value =
                try {
                    UUID.fromString(raw)
                } catch (_: IllegalArgumentException) {
                    null
                }
            return if (value != null && value.toString() == raw) Refinement.Refined(UpgradeRequestId(value))
            else Refinement.Rejected(DaemonUpgradeFailure.REQUEST_REJECTED)
        }
    }
}

@Serializable
internal enum class UpgradeBlocker {
    ACTIVE_TURN,
    RECONCILIATION_REQUIRED,
    REQUEST_PENDING,
    INVOCATION_ACTIVE,
    WORKSPACE_EXECUTION_ACTIVE,
    WORKSPACE_RECOVERY_REQUIRED,
    PREPARATION_ACTIVE,
    SESSION_INITIALIZING,
}

internal data class UpgradeRequest(val id: UpgradeRequestId, val candidate: UpgradeCandidate)

internal sealed interface UpgradeStatus {
    data object Idle : UpgradeStatus

    sealed interface Requested : UpgradeStatus {
        val request: UpgradeRequest
    }

    data class Pending(override val request: UpgradeRequest, val blockers: NonEmptyFailures<UpgradeBlocker>) : Requested

    data class Sealed(override val request: UpgradeRequest) : Requested

    data class Committed(override val request: UpgradeRequest) : Requested

    data class Cancelled(override val request: UpgradeRequest) : Requested
}

/** Pure transition owner. The caller must hold the same admission boundary while observing blockers and sealing. */
internal class DaemonUpgradeAdmission(private val newId: () -> UpgradeRequestId = UpgradeRequestId::fresh) {
    private var state: UpgradeStatus = UpgradeStatus.Idle

    fun prepare(
        candidate: UpgradeCandidate,
        blockers: Set<UpgradeBlocker>,
    ): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure> {
        val request =
            when (val current = state) {
                UpgradeStatus.Idle,
                is UpgradeStatus.Cancelled -> UpgradeRequest(newId(), candidate)
                is UpgradeStatus.Requested -> {
                    if (current.request.candidate != candidate)
                        return Refinement.Rejected(DaemonUpgradeFailure.CANDIDATE_CONFLICT)
                    if (current !is UpgradeStatus.Pending) return Refinement.Refined(current)
                    current.request
                }
            }
        val ordered = blockers.sortedBy { it.ordinal }
        val next =
            if (ordered.isEmpty()) UpgradeStatus.Sealed(request)
            else UpgradeStatus.Pending(request, NonEmptyFailures.from(ordered.first(), ordered.drop(1)))
        state = next
        return Refinement.Refined(next)
    }

    fun observe(id: UpgradeRequestId): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure> =
        when (val current = state) {
            UpgradeStatus.Idle -> Refinement.Rejected(DaemonUpgradeFailure.UNKNOWN_REQUEST)
            is UpgradeStatus.Requested ->
                if (current.request.id == id) Refinement.Refined(current)
                else Refinement.Rejected(DaemonUpgradeFailure.UNKNOWN_REQUEST)
        }

    fun admitWork(): Refinement<Unit, DaemonUpgradeFailure> =
        when (state) {
            is UpgradeStatus.Sealed,
            is UpgradeStatus.Committed -> Refinement.Rejected(DaemonUpgradeFailure.ADMISSION_SEALED)
            UpgradeStatus.Idle,
            is UpgradeStatus.Pending,
            is UpgradeStatus.Cancelled -> Refinement.Refined(Unit)
        }

    fun cancel(id: UpgradeRequestId): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure> =
        transition(id) { current ->
            when (current) {
                is UpgradeStatus.Committed -> Refinement.Rejected(DaemonUpgradeFailure.ALREADY_COMMITTED)
                is UpgradeStatus.Cancelled -> Refinement.Refined(current)
                is UpgradeStatus.Pending,
                is UpgradeStatus.Sealed -> Refinement.Refined(UpgradeStatus.Cancelled(current.request))
            }
        }

    fun commit(id: UpgradeRequestId): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure> =
        transition(id) { current ->
            when (current) {
                is UpgradeStatus.Pending -> Refinement.Rejected(DaemonUpgradeFailure.NOT_QUIESCENT)
                is UpgradeStatus.Cancelled -> Refinement.Rejected(DaemonUpgradeFailure.REQUEST_CANCELLED)
                is UpgradeStatus.Committed -> Refinement.Refined(current)
                is UpgradeStatus.Sealed -> Refinement.Refined(UpgradeStatus.Committed(current.request))
            }
        }

    private fun transition(
        id: UpgradeRequestId,
        apply: (UpgradeStatus.Requested) -> Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure>,
    ): Refinement<UpgradeStatus.Requested, DaemonUpgradeFailure> =
        when (val observed = observe(id)) {
            is Refinement.Rejected -> observed
            is Refinement.Refined -> apply(observed.value).also { if (it is Refinement.Refined) state = it.value }
        }
}
