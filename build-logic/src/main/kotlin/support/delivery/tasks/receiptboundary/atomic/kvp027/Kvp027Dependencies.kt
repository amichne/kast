package support.delivery

import java.nio.file.Path

internal enum class Kvp027DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    IDENTITY_MISMATCH,
    DIGEST_MISMATCH,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp027Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp027DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp027Dependencies) :
        Kvp027DependencyAdmission
    data class Rejected(val failure: Kvp027DependencyFailure) : Kvp027DependencyAdmission
}

/**
 * Proof transition: canonical KVP-027 packet plus KVP-026 receipt/report paths ->
 * `Kvp027DependencyAdmission`.
 *
 * Establishes the sole graph-declared predecessor, its canonical self-digested v2 receipt, and
 * exact report output digest. The report's last task-owned KVP-026 checkpoint becomes the
 * implementation baseline, independent of later receipt revalidation heads. Expected read,
 * identity, closure, digest, or output mismatch remains finite
 * rejection; raw receipt JSON exists only here.
 */
internal fun admitKvp027Dependencies(
    packet: TaskPacket,
    kvp026Path: Path,
    kvp026ReportPath: Path,
): Kvp027DependencyAdmission {
    val expectedIds = packet.receipt.dependencies.map { it.value }.sorted()
    if (expectedIds != listOf(KVP026_RECEIPT_ID)) return rejected(
        Kvp027DependencyFailure.CLOSURE_MISMATCH,
    )
    val raw = read(kvp026Path) ?: return rejected(Kvp027DependencyFailure.READ_REJECTED)
    val document = when (val decoded = decodeTaskProofReceipt(raw)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return rejected(
            Kvp027DependencyFailure.MALFORMED_RECEIPT,
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
    ) return rejected(Kvp027DependencyFailure.CLOSURE_MISMATCH)
    val report = read(kvp026ReportPath) ?: return rejected(
        Kvp027DependencyFailure.READ_REJECTED,
    )
    val expectedOutput = expectedPacket.task.outputs.single().path
    val outputDigests = document.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value }
    if (outputDigests != mapOf(expectedOutput to sha256(report).value)) {
        return rejected(Kvp027DependencyFailure.OUTPUT_MISMATCH)
    }
    val baseline = when (val admitted = admitKvp026ImplementationBaseline(report)) {
        is Kvp026ImplementationBaselineAdmission.Complete -> admitted.baseline
        Kvp026ImplementationBaselineAdmission.Rejected -> return rejected(
            Kvp027DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    return Kvp027DependencyAdmission.Complete(
        AdmittedKvp027Dependencies(
            mapOf(KVP026_RECEIPT_ID to document.receiptDigest.value),
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

private fun rejected(failure: Kvp027DependencyFailure) =
    Kvp027DependencyAdmission.Rejected(failure)

private const val KVP026_RECEIPT_ID = "KVP-026-COMPLETE"
