package support.delivery

import java.nio.file.Path

internal enum class Kvp032DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp032Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp032DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp032Dependencies) :
        Kvp032DependencyAdmission
    data class Rejected(val failure: Kvp032DependencyFailure) : Kvp032DependencyAdmission
}

private sealed interface Kvp032DependencyTextRead {
    data class Complete(val text: String) : Kvp032DependencyTextRead
    data object Rejected : Kvp032DependencyTextRead
}

private sealed interface Kvp032ReceiptAdmission {
    data class Complete(val digest: String) : Kvp032ReceiptAdmission
    data object Rejected : Kvp032ReceiptAdmission
}

/**
 * Proof transition: canonical KVP-032 packet plus five predecessor receipt closures ->
 * `Kvp032DependencyAdmission`.
 *
 * Establishes the admitted KVP-009/KVP-023 legacy prefix, exact KVP-011/KVP-027 v2 outputs, and
 * current exact-head KVP-031 v2 output without rerunning unchanged legacy work. The recorded ready
 * frontier is retained as KVP-032's implementation-scope baseline. Expected read, schema, closure,
 * or digest failure remains finite [Kvp032DependencyFailure] data; raw JSON exists only here.
 */
internal fun admitKvp032Dependencies(
    packet: TaskPacket,
    observedHead: DeliveryGeneration,
    kvp009Path: Path,
    kvp011Path: Path,
    kvp011ReportPath: Path,
    kvp023Path: Path,
    kvp027Path: Path,
    kvp027ReportPath: Path,
    kvp031Path: Path,
    kvp031ReportPath: Path,
): Kvp032DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP032_DEPENDENCY_IDS) {
        return rejected(Kvp032DependencyFailure.CLOSURE_MISMATCH)
    }
    val dependencies = linkedMapOf<String, String>()
    dependencies[KVP009_RECEIPT_ID] = when (val admitted = admitLegacy(
        read(kvp009Path), KVP009_RECEIPT_ID, KVP009_TASK_ID, KVP009_GATE_ID,
        KVP009_PROGRAM_FINGERPRINT, KVP009_RECEIPT_DIGEST,
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    dependencies[KVP011_RECEIPT_ID] = when (val admitted = admitV2Output(
        read(kvp011Path), canonicalKvp011Packet(), TaskProofHeadPolicy.CONTENT_SCOPED,
        read(kvp011ReportPath),
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    dependencies[KVP023_RECEIPT_ID] = when (val admitted = admitLegacy(
        read(kvp023Path), KVP023_RECEIPT_ID, KVP023_TASK_ID, KVP023_GATE_ID,
        KVP023_PROGRAM_FINGERPRINT, KVP023_RECEIPT_DIGEST,
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    dependencies[KVP027_RECEIPT_ID] = when (val admitted = admitV2Output(
        read(kvp027Path), canonicalKvp027Packet(), TaskProofHeadPolicy.CONTENT_SCOPED,
        read(kvp027ReportPath),
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    dependencies[KVP031_RECEIPT_ID] = when (val admitted = admitV2Output(
        read(kvp031Path), canonicalKvp031Packet(), TaskProofHeadPolicy.EXACT_HEAD,
        read(kvp031ReportPath), observedHead,
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    return Kvp032DependencyAdmission.Complete(
        AdmittedKvp032Dependencies(
            dependencies,
            hostedProductionCompositionBatch().readyFrontier,
        ),
    )
}

/** Raw legacy receipt JSON -> exact pinned prefix digest or closed rejection. */
private fun admitLegacy(
    raw: Kvp032DependencyTextRead,
    receiptId: String,
    taskId: String,
    gateId: String,
    programFingerprint: String,
    receiptDigest: String,
): Kvp032ReceiptAdmission {
    if (raw !is Kvp032DependencyTextRead.Complete) return Kvp032ReceiptAdmission.Rejected
    val document = when (val decoded = decodeProofReceiptDocument(raw.text)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return Kvp032ReceiptAdmission.Rejected
    }
    return if (
        document.receiptId.value == receiptId && document.taskId.value == taskId &&
        document.gateId.value == gateId &&
        document.programFingerprint.value == programFingerprint &&
        document.requirementFingerprint.value == LEGACY_PREFIX_REQUIREMENT_FINGERPRINT &&
        document.receiptDigest.value == receiptDigest &&
        document.receiptDigest == document.derivedDigest() &&
        encodeProofReceiptDocument(document) == raw.text
    ) Kvp032ReceiptAdmission.Complete(receiptDigest) else Kvp032ReceiptAdmission.Rejected
}

/** Raw v2 receipt/report JSON plus canonical packet/policy -> exact output closure. */
private fun admitV2Output(
    rawReceipt: Kvp032DependencyTextRead,
    expected: Pair<TaskPacket, TaskProofProgramVersion>,
    policy: TaskProofHeadPolicy,
    rawReport: Kvp032DependencyTextRead,
    exactHead: DeliveryGeneration? = null,
): Kvp032ReceiptAdmission {
    if (
        rawReceipt !is Kvp032DependencyTextRead.Complete ||
        rawReport !is Kvp032DependencyTextRead.Complete
    ) return Kvp032ReceiptAdmission.Rejected
    val document = when (val decoded = decodeTaskProofReceipt(rawReceipt.text)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return Kvp032ReceiptAdmission.Rejected
    }
    val (packet, version) = expected
    val output = packet.task.outputs.single().path
    return if (
        document.receiptId == packet.receipt.receiptId && document.taskId == packet.task.id &&
        document.programVersion == version &&
        document.taskDefinitionDigest.value == packet.taskDefinitionDigest.value &&
        document.commandDigest == packet.kvp032CommandDigest() &&
        document.dependencyReceiptDigests.keys == packet.receipt.dependencies &&
        document.headPolicy == policy &&
        (exactHead == null || document.observedRepositoryHead == exactHead) &&
        document.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value } ==
        mapOf(output to sha256(rawReport.text).value) &&
        document.receiptDigest == document.derivedDigest() &&
        encodeTaskProofReceipt(document) == rawReceipt.text
    ) Kvp032ReceiptAdmission.Complete(document.receiptDigest.value)
    else Kvp032ReceiptAdmission.Rejected
}

/** Dependency `Path -> Kvp032DependencyTextRead`; bounds regular non-symlink UTF-8 evidence. */
private fun read(path: Path): Kvp032DependencyTextRead = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp032DependencyTextRead.Complete(
        result.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp032DependencyTextRead.Rejected
}

private fun rejected(failure: Kvp032DependencyFailure) =
    Kvp032DependencyAdmission.Rejected(failure)
private fun closureRejected() = rejected(Kvp032DependencyFailure.CLOSURE_MISMATCH)

private val KVP032_DEPENDENCY_IDS = listOf(
    "KVP-009-COMPLETE", "KVP-011-COMPLETE", "KVP-023-COMPLETE",
    "KVP-027-COMPLETE", "KVP-031-COMPLETE",
)
private const val KVP009_RECEIPT_ID = "KVP-009-COMPLETE"
private const val KVP009_TASK_ID = "KVP-009"
private const val KVP009_GATE_ID = "KVP-009-COMPLETE-GATE"
private const val KVP009_PROGRAM_FINGERPRINT =
    "31fcef0d003e673781fe38c8aa52e9ad3c4aadec4a888764bbe17645abaf8888"
private const val KVP009_RECEIPT_DIGEST =
    "64efc0e33344ccc55f2436a6dab19e828d52d3f25a9e839ab905600e894da7ea"
private const val KVP011_RECEIPT_ID = "KVP-011-COMPLETE"
private const val KVP023_RECEIPT_ID = "KVP-023-COMPLETE"
private const val KVP023_TASK_ID = "KVP-023"
private const val KVP023_GATE_ID = "KVP-023-COMPLETE-GATE"
private const val KVP023_PROGRAM_FINGERPRINT =
    "f564dea6a123a43320ae96933f370f446eb738b32de16fc53d2c94685ab89d44"
private const val KVP023_RECEIPT_DIGEST =
    "e3d7587ea38783234f7735dd8715d424eddeb399210dd41fbf00faf96ba8292e"
private const val KVP027_RECEIPT_ID = "KVP-027-COMPLETE"
private const val KVP031_RECEIPT_ID = "KVP-031-COMPLETE"
