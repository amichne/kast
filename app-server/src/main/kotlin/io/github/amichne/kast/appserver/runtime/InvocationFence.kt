package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

@kotlinx.serialization.Serializable
internal enum class InvocationFenceFailure {
    STORE_REJECTED,
    DOCUMENT_MALFORMED,
    CAPACITY_EXCEEDED,
    INPUT_CONFLICT,
    ALREADY_COMPLETED,
    OUTCOME_UNCERTAIN,
    CANCELLED_AFTER_RECOVERY,
    INPUT_REJECTED,
    LIMIT_REJECTED,
    TRANSITION_REJECTED,
    STORE_BUSY,
    VERSION_UNSUPPORTED,
    MIGRATION_REJECTED,
}

internal sealed interface InvocationAdmission {
    data object Admitted : InvocationAdmission

    data class Rejected(val failure: InvocationFenceFailure) : InvocationAdmission
}

/** Durable history is independent of the bounded set of invocations admitted by this process. */
internal class InvocationFence(
    file: Path?,
    private val maximumActive: InvocationCapacityLimit = InvocationCapacityLimit.Default,
    observeMigration: (InvocationMigrationObservation) -> Unit = { System.err.println(it.toJson()) },
) {
    private val store: Refinement<InvocationRecords, InvocationFenceFailure> =
        if (file == null) Refinement.Refined(MemoryInvocationRecords())
        else FileInvocationRecords.open(file, observeMigration)
    private val active = mutableSetOf<InvocationKey>()

    @Synchronized
    fun initialization(): InvocationAdmission =
        when (store) {
            is Refinement.Refined -> InvocationAdmission.Admitted
            is Refinement.Rejected -> InvocationAdmission.Rejected(store.failure)
        }

    @Synchronized
    fun admit(identity: String, fingerprint: String): InvocationAdmission {
        val records =
            when (store) {
                is Refinement.Refined -> store.value
                is Refinement.Rejected -> return InvocationAdmission.Rejected(store.failure)
            }
        val admitted =
            when (val result = InvocationFingerprint.admit(fingerprint)) {
                is Refinement.Refined -> result.value
                is Refinement.Rejected -> return InvocationAdmission.Rejected(result.failure)
            }
        val key = InvocationKey.of(identity)
        val capacity = if (active.size < maximumActive.value) InvocationCapacity.AVAILABLE else InvocationCapacity.FULL
        val result = records.apply(InvocationRecordChange.Begin(key, admitted, capacity))
        if (result == InvocationAdmission.Admitted) active.add(key)
        return result
    }

    @Synchronized
    fun finish(identity: String, settlement: InvocationSettlement): InvocationAdmission {
        val records =
            when (store) {
                is Refinement.Refined -> store.value
                is Refinement.Rejected -> return InvocationAdmission.Rejected(store.failure)
            }
        val key = InvocationKey.of(identity)
        if (key !in active) return InvocationAdmission.Rejected(InvocationFenceFailure.TRANSITION_REJECTED)
        val result = records.apply(InvocationRecordChange.Settle(key, settlement))
        if (result == InvocationAdmission.Admitted) active.remove(key)
        return result
    }

    companion object {
        fun digest(value: String): String = invocationDigest(value)
    }
}
