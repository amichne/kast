package support.delivery

import java.nio.file.Path

internal enum class Kvp028DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    IDENTITY_MISMATCH,
    DIGEST_MISMATCH,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp028Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp028DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp028Dependencies) :
        Kvp028DependencyAdmission
    data class Rejected(val failure: Kvp028DependencyFailure) : Kvp028DependencyAdmission
}

/**
 * Proof transition: canonical KVP-028 packet plus KVP-023/KVP-026 receipt evidence ->
 * `Kvp028DependencyAdmission`.
 *
 * Establishes both graph-declared predecessors: the preserved canonical self-digested v1 KVP-023
 * receipt and the canonical self-digested v2 KVP-026 receipt with its exact report output digest.
 * The KVP-026 report's last task-owned checkpoint becomes the implementation baseline, independent
 * of later receipt revalidation heads. Expected read, identity, closure, digest, or output mismatch
 * remains finite rejection; raw receipt JSON exists only here.
 */
internal fun admitKvp028Dependencies(
    packet: TaskPacket,
    kvp023Path: Path,
    kvp026Path: Path,
    kvp026ReportPath: Path,
): Kvp028DependencyAdmission {
    val expectedIds = packet.receipt.dependencies.map { it.value }.sorted()
    if (expectedIds != listOf(KVP023_RECEIPT_ID, KVP026_RECEIPT_ID)) return rejected(
        Kvp028DependencyFailure.CLOSURE_MISMATCH,
    )
    val kvp023Raw = read(kvp023Path) ?: return rejected(Kvp028DependencyFailure.READ_REJECTED)
    val kvp023 = when (val decoded = decodeProofReceiptDocument(kvp023Raw)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return rejected(
            Kvp028DependencyFailure.MALFORMED_RECEIPT,
        )
    }
    if (
        kvp023.receiptId.value != KVP023_RECEIPT_ID ||
        kvp023.taskId.value != KVP023_TASK_ID ||
        kvp023.gateId.value != KVP023_GATE_ID ||
        kvp023.programFingerprint.value != LEGACY_PREFIX_PROGRAM_FINGERPRINT ||
        kvp023.requirementFingerprint.value != LEGACY_PREFIX_REQUIREMENT_FINGERPRINT ||
        kvp023.receiptDigest != kvp023.derivedDigest() ||
        encodeProofReceiptDocument(kvp023) != kvp023Raw
    ) return rejected(Kvp028DependencyFailure.CLOSURE_MISMATCH)
    val raw = read(kvp026Path) ?: return rejected(Kvp028DependencyFailure.READ_REJECTED)
    val document = when (val decoded = decodeTaskProofReceipt(raw)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return rejected(
            Kvp028DependencyFailure.MALFORMED_RECEIPT,
        )
    }
    val (expectedPacket, expectedVersion) = canonicalKvp026Packet()
    if (
        document.receiptId.value != KVP026_RECEIPT_ID ||
        document.taskId != expectedPacket.task.id ||
        document.programVersion != expectedVersion ||
        document.taskDefinitionDigest.value != expectedPacket.taskDefinitionDigest.value ||
        document.commandDigest != expectedPacket.kvp026CommandDigest() ||
        document.dependencyReceiptDigests.keys != expectedPacket.receipt.dependencies ||
        document.headPolicy != TaskProofHeadPolicy.CONTENT_SCOPED ||
        document.receiptDigest != document.derivedDigest() ||
        encodeTaskProofReceipt(document) != raw
    ) return rejected(Kvp028DependencyFailure.CLOSURE_MISMATCH)
    val report = read(kvp026ReportPath) ?: return rejected(
        Kvp028DependencyFailure.READ_REJECTED,
    )
    val expectedOutput = expectedPacket.task.outputs.single().path
    val outputDigests = document.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value }
    if (outputDigests != mapOf(expectedOutput to sha256(report).value)) {
        return rejected(Kvp028DependencyFailure.OUTPUT_MISMATCH)
    }
    val baseline = when (val admitted = admitKvp026ImplementationBaseline(report)) {
        is Kvp026ImplementationBaselineAdmission.Complete -> admitted.baseline
        Kvp026ImplementationBaselineAdmission.Rejected -> return rejected(
            Kvp028DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    return Kvp028DependencyAdmission.Complete(
        AdmittedKvp028Dependencies(
            mapOf(
                KVP023_RECEIPT_ID to kvp023.receiptDigest.value,
                KVP026_RECEIPT_ID to document.receiptDigest.value,
            ),
            baseline,
        ),
    )
}

private fun read(path: Path): String? = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> result.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun rejected(failure: Kvp028DependencyFailure) =
    Kvp028DependencyAdmission.Rejected(failure)

private const val KVP026_RECEIPT_ID = "KVP-026-COMPLETE"
private const val KVP023_RECEIPT_ID = "KVP-023-COMPLETE"
private const val KVP023_TASK_ID = "KVP-023"
private const val KVP023_GATE_ID = "KVP-023-COMPLETE-GATE"
