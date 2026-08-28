package support.delivery

import java.nio.file.Path

internal enum class Kvp033DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp033Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp033DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp033Dependencies) :
        Kvp033DependencyAdmission
    data class Rejected(val failure: Kvp033DependencyFailure) : Kvp033DependencyAdmission
}

private sealed interface Kvp033DependencyTextRead {
    data class Complete(val text: String) : Kvp033DependencyTextRead
    data object Rejected : Kvp033DependencyTextRead
}

private sealed interface Kvp033ReceiptAdmission {
    data class Complete(val document: TaskProofReceiptDocument) : Kvp033ReceiptAdmission
    data object Rejected : Kvp033ReceiptAdmission
}

private sealed interface Kvp033LegacyReceiptAdmission {
    data class Complete(val digest: String) : Kvp033LegacyReceiptAdmission
    data object Rejected : Kvp033LegacyReceiptAdmission
}

private sealed interface Kvp033HeadClosure {
    data object ContentScoped : Kvp033HeadClosure
    data class Exact(val head: DeliveryGeneration) : Kvp033HeadClosure
}

/**
 * Proof transition: canonical KVP-033 packet plus four predecessor receipt/output closures ->
 * `Kvp033DependencyAdmission`.
 *
 * Establishes the pinned KVP-022 prefix, current KVP-025/KVP-032 content receipts, and current
 * exact-head KVP-031 receipt. The KVP-032 receipt must itself bind the admitted KVP-031 digest.
 * Expected read, schema, output, or closure mismatch remains finite [Kvp033DependencyFailure]
 * data; raw JSON extraction is permitted only at this dependency boundary.
 */
internal fun admitKvp033Dependencies(
    packet: TaskPacket,
    observedHead: DeliveryGeneration,
    kvp022Path: Path,
    kvp025Path: Path,
    kvp025ReportPath: Path,
    kvp031Path: Path,
    kvp031ReportPath: Path,
    kvp032Path: Path,
    kvp032ReportPath: Path,
): Kvp033DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP033_DEPENDENCY_IDS) {
        return dependencyRejected(Kvp033DependencyFailure.CLOSURE_MISMATCH)
    }
    val legacy = when (val admitted = admitKvp022Legacy(readKvp033Dependency(kvp022Path))) {
        is Kvp033LegacyReceiptAdmission.Complete -> admitted.digest
        Kvp033LegacyReceiptAdmission.Rejected -> return dependencyRejected(
            Kvp033DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    val kvp025 = when (val admitted = admitKvp033V2Output(
        readKvp033Dependency(kvp025Path), canonicalKvp025Packet(),
        Kvp033HeadClosure.ContentScoped, readKvp033Dependency(kvp025ReportPath),
    )) {
        is Kvp033ReceiptAdmission.Complete -> admitted.document
        Kvp033ReceiptAdmission.Rejected -> return dependencyRejected(
            Kvp033DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    val kvp031 = when (val admitted = admitKvp033V2Output(
        readKvp033Dependency(kvp031Path), canonicalKvp031Packet(),
        Kvp033HeadClosure.Exact(observedHead), readKvp033Dependency(kvp031ReportPath),
    )) {
        is Kvp033ReceiptAdmission.Complete -> admitted.document
        Kvp033ReceiptAdmission.Rejected -> return dependencyRejected(
            Kvp033DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    val kvp032 = when (val admitted = admitKvp033V2Output(
        readKvp033Dependency(kvp032Path), canonicalKvp032Packet(),
        Kvp033HeadClosure.ContentScoped, readKvp033Dependency(kvp032ReportPath),
    )) {
        is Kvp033ReceiptAdmission.Complete -> admitted.document
        Kvp033ReceiptAdmission.Rejected -> return dependencyRejected(
            Kvp033DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    if (
        kvp032.dependencyReceiptDigests.getValue(ReceiptId(KVP031_RECEIPT_ID)).value !=
        kvp031.receiptDigest.value
    ) return dependencyRejected(Kvp033DependencyFailure.CLOSURE_MISMATCH)
    val digests = linkedMapOf(
        KVP022_RECEIPT_ID to legacy,
        KVP025_RECEIPT_ID to kvp025.receiptDigest.value,
        KVP031_RECEIPT_ID to kvp031.receiptDigest.value,
        KVP032_RECEIPT_ID to kvp032.receiptDigest.value,
    )
    return Kvp033DependencyAdmission.Complete(AdmittedKvp033Dependencies(
        digests,
        DeliveryGeneration(KVP033_READY_FRONTIER_HEAD),
    ))
}

/**
 * Proof transition: bounded legacy receipt text -> `Kvp033LegacyReceiptAdmission`.
 *
 * Establishes the exact pinned KVP-022 identity, program/requirement fingerprints, canonical bytes,
 * and self digest. Any read, schema, identity, or digest mismatch is closed rejection; raw legacy
 * JSON is extracted only at this dependency boundary.
 */
private fun admitKvp022Legacy(
    raw: Kvp033DependencyTextRead,
): Kvp033LegacyReceiptAdmission {
    if (raw !is Kvp033DependencyTextRead.Complete) return Kvp033LegacyReceiptAdmission.Rejected
    val document = when (val decoded = decodeProofReceiptDocument(raw.text)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return Kvp033LegacyReceiptAdmission.Rejected
    }
    return if (
        document.receiptId.value == KVP022_RECEIPT_ID &&
        document.taskId.value == KVP022_TASK_ID && document.gateId.value == KVP022_GATE_ID &&
        document.programFingerprint.value == KVP022_PROGRAM_FINGERPRINT &&
        document.requirementFingerprint.value == LEGACY_PREFIX_REQUIREMENT_FINGERPRINT &&
        document.receiptDigest.value == KVP022_RECEIPT_DIGEST &&
        document.receiptDigest == document.derivedDigest() &&
        encodeProofReceiptDocument(document) == raw.text
    ) Kvp033LegacyReceiptAdmission.Complete(KVP022_RECEIPT_DIGEST)
    else Kvp033LegacyReceiptAdmission.Rejected
}

/**
 * Proof transition: bounded v2 receipt/report texts plus canonical packet/policy ->
 * `Kvp033ReceiptAdmission`.
 *
 * Establishes exact task, definition, command, predecessor-key, head-policy, output, canonical-byte,
 * and self-digest closure. Any mismatch is closed rejection; raw JSON is extracted only here.
 */
private fun admitKvp033V2Output(
    rawReceipt: Kvp033DependencyTextRead,
    expected: Pair<TaskPacket, TaskProofProgramVersion>,
    headClosure: Kvp033HeadClosure,
    rawReport: Kvp033DependencyTextRead,
): Kvp033ReceiptAdmission {
    if (
        rawReceipt !is Kvp033DependencyTextRead.Complete ||
        rawReport !is Kvp033DependencyTextRead.Complete
    ) return Kvp033ReceiptAdmission.Rejected
    val document = when (val decoded = decodeTaskProofReceipt(rawReceipt.text)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return Kvp033ReceiptAdmission.Rejected
    }
    val (dependencyPacket, version) = expected
    val output = dependencyPacket.task.outputs.single().path
    val headComplete = when (headClosure) {
        Kvp033HeadClosure.ContentScoped ->
            document.headPolicy == TaskProofHeadPolicy.CONTENT_SCOPED
        is Kvp033HeadClosure.Exact ->
            document.headPolicy == TaskProofHeadPolicy.EXACT_HEAD &&
                document.observedRepositoryHead == headClosure.head
    }
    return if (
        document.receiptId == dependencyPacket.receipt.receiptId &&
        document.taskId == dependencyPacket.task.id && document.programVersion == version &&
        document.taskDefinitionDigest.value == dependencyPacket.taskDefinitionDigest.value &&
        document.commandDigest == dependencyPacket.kvp033CommandDigest() &&
        document.dependencyReceiptDigests.keys == dependencyPacket.receipt.dependencies &&
        headComplete &&
        document.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value } ==
        mapOf(output to sha256(rawReport.text).value) &&
        document.receiptDigest == document.derivedDigest() &&
        encodeTaskProofReceipt(document) == rawReceipt.text
    ) Kvp033ReceiptAdmission.Complete(document) else Kvp033ReceiptAdmission.Rejected
}

/**
 * Proof transition: dependency `Path -> Kvp033DependencyTextRead`.
 *
 * Establishes bounded regular non-symlink UTF-8 evidence or closed rejection. Raw text extraction
 * is permitted only at this receipt dependency boundary.
 */
private fun readKvp033Dependency(path: Path): Kvp033DependencyTextRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp033DependencyTextRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp033DependencyTextRead.Rejected
}

private fun dependencyRejected(failure: Kvp033DependencyFailure) =
    Kvp033DependencyAdmission.Rejected(failure)

private val KVP033_DEPENDENCY_IDS = listOf(
    "KVP-022-COMPLETE", "KVP-025-COMPLETE", "KVP-031-COMPLETE", "KVP-032-COMPLETE",
)
private const val KVP022_RECEIPT_ID = "KVP-022-COMPLETE"
private const val KVP022_TASK_ID = "KVP-022"
private const val KVP022_GATE_ID = "KVP-022-COMPLETE-GATE"
private const val KVP022_PROGRAM_FINGERPRINT =
    "f564dea6a123a43320ae96933f370f446eb738b32de16fc53d2c94685ab89d44"
private const val KVP022_RECEIPT_DIGEST =
    "3ed9cc63d4fb9d04ad452c6497012a0ae9f849e598253c82f3bfca8053751c11"
private const val KVP025_RECEIPT_ID = "KVP-025-COMPLETE"
private const val KVP031_RECEIPT_ID = "KVP-031-COMPLETE"
private const val KVP032_RECEIPT_ID = "KVP-032-COMPLETE"
private const val KVP033_READY_FRONTIER_HEAD =
    "70b79962ea8a1185132c88fdd5b633ea2bc29f37"
