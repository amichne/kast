package support.delivery

internal enum class LegacyReceiptPrefixFailure {
    MALFORMED_RECEIPT,
    RECEIPT_ID_MISMATCH,
    TASK_ID_MISMATCH,
    GATE_ID_MISMATCH,
    PROGRAM_FINGERPRINT_MISMATCH,
    REQUIREMENT_FINGERPRINT_MISMATCH,
    RECEIPT_DIGEST_MISMATCH,
    SELF_DIGEST_MISMATCH,
}

internal class AdmittedLegacyReceiptPrefix internal constructor(
    val frontierTaskId: TaskId,
    val frontierReceiptId: ReceiptId,
    val frontierReceiptDigest: TaskProofDependencyDigest,
    val observedRepositoryHead: DeliveryGeneration,
)

internal sealed interface LegacyReceiptPrefixAdmission {
    data class Complete(val prefix: AdmittedLegacyReceiptPrefix) :
        LegacyReceiptPrefixAdmission
    data class Rejected(val failure: LegacyReceiptPrefixFailure) :
        LegacyReceiptPrefixAdmission
}

/**
 * Proof transition: legacy v1 `ProofReceiptDocument` -> `LegacyReceiptPrefixAdmission`.
 *
 * Establishes the exact previously admitted KVP-024 frontier receipt by pinned program,
 * requirement, task, gate, and receipt digests while preserving its observed historical head.
 * Current source bytes and current head are intentionally outside this transition: the v1 prefix
 * was admitted before the v2 content-scoped protocol and must not be rerun merely for migration.
 * Every mismatch returns finite [LegacyReceiptPrefixFailure]. Raw v1 JSON remains at its decoder.
 */
internal fun admitLegacyKvp024Prefix(
    document: ProofReceiptDocument,
): LegacyReceiptPrefixAdmission {
    fun rejected(failure: LegacyReceiptPrefixFailure) =
        LegacyReceiptPrefixAdmission.Rejected(failure)
    if (document.receiptId.value != LEGACY_PREFIX_FRONTIER_RECEIPT_ID) {
        return rejected(LegacyReceiptPrefixFailure.RECEIPT_ID_MISMATCH)
    }
    if (document.taskId.value != LEGACY_PREFIX_FRONTIER_TASK_ID) {
        return rejected(LegacyReceiptPrefixFailure.TASK_ID_MISMATCH)
    }
    if (document.gateId.value != LEGACY_PREFIX_FRONTIER_GATE_ID) {
        return rejected(LegacyReceiptPrefixFailure.GATE_ID_MISMATCH)
    }
    if (document.programFingerprint.value != LEGACY_PREFIX_PROGRAM_FINGERPRINT) {
        return rejected(LegacyReceiptPrefixFailure.PROGRAM_FINGERPRINT_MISMATCH)
    }
    if (document.requirementFingerprint.value != LEGACY_PREFIX_REQUIREMENT_FINGERPRINT) {
        return rejected(LegacyReceiptPrefixFailure.REQUIREMENT_FINGERPRINT_MISMATCH)
    }
    if (document.receiptDigest.value != LEGACY_PREFIX_FRONTIER_RECEIPT_DIGEST) {
        return rejected(LegacyReceiptPrefixFailure.RECEIPT_DIGEST_MISMATCH)
    }
    if (document.receiptDigest != document.derivedDigest()) {
        return rejected(LegacyReceiptPrefixFailure.SELF_DIGEST_MISMATCH)
    }
    return LegacyReceiptPrefixAdmission.Complete(
        AdmittedLegacyReceiptPrefix(
            document.taskId,
            ReceiptId(document.receiptId.value),
            TaskProofDependencyDigest(document.receiptDigest.value),
            DeliveryGeneration(document.exactHead.value),
        ),
    )
}

internal const val LEGACY_PREFIX_FRONTIER_TASK_ID = "KVP-024"
internal const val LEGACY_PREFIX_FRONTIER_RECEIPT_ID = "KVP-024-COMPLETE"
internal const val LEGACY_PREFIX_FRONTIER_GATE_ID = "KVP-024-COMPLETE-GATE"
internal const val LEGACY_PREFIX_PROGRAM_FINGERPRINT =
    "f564dea6a123a43320ae96933f370f446eb738b32de16fc53d2c94685ab89d44"
internal const val LEGACY_PREFIX_REQUIREMENT_FINGERPRINT =
    "de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c"
internal const val LEGACY_PREFIX_FRONTIER_RECEIPT_DIGEST =
    "24265429b38776da6801db7b5ed23a944d589a4dcb6760d93b8187270dc57da3"
