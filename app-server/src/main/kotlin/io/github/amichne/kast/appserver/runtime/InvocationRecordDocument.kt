package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable

@Serializable
internal data class InvocationRecordDocument(
    val schemaVersion: Int,
    val key: String,
    val fingerprint: String,
    val phase: InvocationPhase,
) {
    fun admit(expected: InvocationKey): Refinement<InvocationRecord, InvocationFenceFailure> {
        if (schemaVersion != VERSION) return Refinement.Rejected(InvocationFenceFailure.VERSION_UNSUPPORTED)
        if (key != expected.value) return Refinement.Rejected(InvocationFenceFailure.STORE_REJECTED)
        return when (val admitted = InvocationFingerprint.admit(fingerprint)) {
            is Refinement.Rejected -> Refinement.Rejected(InvocationFenceFailure.STORE_REJECTED)
            is Refinement.Refined -> Refinement.Refined(InvocationRecord(expected, admitted.value, phase))
        }
    }

    companion object {
        const val VERSION = 2

        fun from(record: InvocationRecord) =
            InvocationRecordDocument(VERSION, record.key.value, record.fingerprint.value, record.phase)
    }
}

@Serializable internal data class InvocationStoreLayout(val schemaVersion: Int)

/** The legacy contract is a digest-keyed dictionary; its keys are refined before migration. */
@Serializable
internal data class LegacyInvocationDocument(val schemaVersion: Int, val records: Map<String, LegacyInvocationRecord>)

@Serializable internal data class LegacyInvocationRecord(val fingerprint: String, val phase: InvocationPhase)
