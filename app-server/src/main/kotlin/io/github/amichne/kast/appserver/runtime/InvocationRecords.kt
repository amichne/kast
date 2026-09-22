package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
internal enum class InvocationPhase {
    STARTED,
    COMPLETED,
    UNCERTAIN,
}

internal enum class InvocationSettlement(val phase: InvocationPhase) {
    COMPLETED(InvocationPhase.COMPLETED),
    UNCERTAIN(InvocationPhase.UNCERTAIN),
}

@JvmInline
internal value class InvocationKey private constructor(val value: String) {
    companion object {
        fun of(identity: String) = InvocationKey(invocationDigest(identity))

        fun admit(value: String): Refinement<InvocationKey, InvocationFenceFailure> =
            if (digestPattern.matches(value)) Refinement.Refined(InvocationKey(value))
            else Refinement.Rejected(InvocationFenceFailure.INPUT_REJECTED)
    }
}

@JvmInline
internal value class InvocationFingerprint private constructor(val value: String) {
    companion object {
        fun admit(value: String): Refinement<InvocationFingerprint, InvocationFenceFailure> =
            if (digestPattern.matches(value)) Refinement.Refined(InvocationFingerprint(value))
            else Refinement.Rejected(InvocationFenceFailure.INPUT_REJECTED)
    }
}

private val digestPattern = Regex("[a-f0-9]{64}")

internal fun invocationDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
        "%02x".format(it)
    }

internal data class InvocationRecord(
    val key: InvocationKey,
    val fingerprint: InvocationFingerprint,
    val phase: InvocationPhase,
)

internal sealed interface InvocationRecordLookup {
    data object Absent : InvocationRecordLookup

    data class Present(val record: InvocationRecord) : InvocationRecordLookup
}

internal enum class InvocationCapacity {
    AVAILABLE,
    FULL,
}

internal sealed interface InvocationRecordChange {
    val key: InvocationKey

    data class Begin(
        override val key: InvocationKey,
        val fingerprint: InvocationFingerprint,
        val capacity: InvocationCapacity,
    ) : InvocationRecordChange

    data class Settle(override val key: InvocationKey, val settlement: InvocationSettlement) : InvocationRecordChange
}

/** Pure transition policy shared by the durable adapter and in-memory protocol fixtures. */
internal fun invocationTransition(
    existing: InvocationRecordLookup,
    change: InvocationRecordChange,
): Refinement<InvocationRecord, InvocationFenceFailure> =
    when (change) {
        is InvocationRecordChange.Begin -> beginInvocation(existing, change)
        is InvocationRecordChange.Settle -> settleInvocation(existing, change)
    }

private fun beginInvocation(
    existing: InvocationRecordLookup,
    change: InvocationRecordChange.Begin,
): Refinement<InvocationRecord, InvocationFenceFailure> =
    when (existing) {
        InvocationRecordLookup.Absent ->
            if (change.capacity == InvocationCapacity.FULL)
                Refinement.Rejected(InvocationFenceFailure.CAPACITY_EXCEEDED)
            else Refinement.Refined(InvocationRecord(change.key, change.fingerprint, InvocationPhase.STARTED))
        is InvocationRecordLookup.Present ->
            Refinement.Rejected(
                when {
                    existing.record.fingerprint != change.fingerprint -> InvocationFenceFailure.INPUT_CONFLICT
                    existing.record.phase == InvocationPhase.COMPLETED -> InvocationFenceFailure.ALREADY_COMPLETED
                    else -> InvocationFenceFailure.OUTCOME_UNCERTAIN
                }
            )
    }

private fun settleInvocation(
    existing: InvocationRecordLookup,
    change: InvocationRecordChange.Settle,
): Refinement<InvocationRecord, InvocationFenceFailure> =
    when (existing) {
        InvocationRecordLookup.Absent -> Refinement.Rejected(InvocationFenceFailure.TRANSITION_REJECTED)
        is InvocationRecordLookup.Present ->
            if (existing.record.phase != InvocationPhase.STARTED)
                Refinement.Rejected(InvocationFenceFailure.TRANSITION_REJECTED)
            else Refinement.Refined(existing.record.copy(phase = change.settlement.phase))
    }

internal interface InvocationRecords {
    fun apply(change: InvocationRecordChange): InvocationAdmission
}

internal class MemoryInvocationRecords : InvocationRecords {
    private val records = mutableMapOf<InvocationKey, InvocationRecord>()

    override fun apply(change: InvocationRecordChange): InvocationAdmission {
        val existing = records[change.key]?.let(InvocationRecordLookup::Present) ?: InvocationRecordLookup.Absent
        return when (val transition = invocationTransition(existing, change)) {
            is Refinement.Rejected -> InvocationAdmission.Rejected(transition.failure)
            is Refinement.Refined -> {
                records[change.key] = transition.value
                InvocationAdmission.Admitted
            }
        }
    }
}
