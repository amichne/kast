package support.delivery

import java.nio.file.Path

internal enum class Kvp035DependencyFailure { READ_REJECTED, CLOSURE_MISMATCH }

internal class AdmittedKvp035Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp035DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp035Dependencies) :
        Kvp035DependencyAdmission
    data class Rejected(val failure: Kvp035DependencyFailure) : Kvp035DependencyAdmission
}

private sealed interface Kvp035DependencyRead {
    data class Complete(val raw: String) : Kvp035DependencyRead
    data object Rejected : Kvp035DependencyRead
}

private sealed interface Kvp035ReceiptAdmission {
    data class Complete(val receipt: TaskProofReceiptDocument) : Kvp035ReceiptAdmission
    data object Rejected : Kvp035ReceiptAdmission
}

/**
 * Proof transition: canonical packet plus KVP-011/KVP-034 physical evidence ->
 * `Kvp035DependencyAdmission`.
 *
 * Establishes the exact graph dependency set, canonical receipt bytes, physical output digests,
 * content closure for KVP-011, current exact-head closure for KVP-034, and the graph-owned release
 * frontier. Malformed, stale, or mismatched evidence is finite rejection; raw JSON exists here.
 */
internal fun admitKvp035Dependencies(
    packet: TaskPacket,
    observedHead: DeliveryGeneration,
    kvp011Receipt: Path,
    kvp011Report: Path,
    kvp034Receipt: Path,
    kvp034Report: Path,
): Kvp035DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP035_DEPENDENCIES) {
        return dependencyRejected(Kvp035DependencyFailure.CLOSURE_MISMATCH)
    }
    val kvp011 = when (val admitted = admitDependency(
        kvp011Receipt, kvp011Report, canonicalKvp011Packet(), TaskProofHeadPolicy.CONTENT_SCOPED,
        null,
    )) {
        is Kvp035ReceiptAdmission.Complete -> admitted.receipt
        Kvp035ReceiptAdmission.Rejected -> return dependencyRejected(
            Kvp035DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    val kvp034 = when (val admitted = admitDependency(
        kvp034Receipt, kvp034Report, canonicalKvp034Packet(), TaskProofHeadPolicy.EXACT_HEAD,
        observedHead,
    )) {
        is Kvp035ReceiptAdmission.Complete -> admitted.receipt
        Kvp035ReceiptAdmission.Rejected -> return dependencyRejected(
            Kvp035DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    return Kvp035DependencyAdmission.Complete(AdmittedKvp035Dependencies(
        linkedMapOf(
            "KVP-011-COMPLETE" to kvp011.receiptDigest.value,
            "KVP-034-COMPLETE" to kvp034.receiptDigest.value,
        ),
        defaultHostedReleaseBatch().readyFrontier,
    ))
}

/**
 * Proof transition: receipt/report paths plus canonical packet and head policy ->
 * `Kvp035ReceiptAdmission`.
 *
 * Establishes canonical v2 receipt identity, self-digest, physical output digest, and requested
 * head closure. Any bounded-read, schema, digest, policy, or head mismatch is closed rejection;
 * raw receipt JSON is extracted only at this dependency boundary.
 */
private fun admitDependency(
    receiptPath: Path,
    reportPath: Path,
    expected: Pair<TaskPacket, TaskProofProgramVersion>,
    policy: TaskProofHeadPolicy,
    exactHead: DeliveryGeneration?,
): Kvp035ReceiptAdmission {
    val rawReceipt = when (val read = readDependency(receiptPath)) {
        is Kvp035DependencyRead.Complete -> read.raw
        Kvp035DependencyRead.Rejected -> return Kvp035ReceiptAdmission.Rejected
    }
    val document = when (val decoded = decodeTaskProofReceipt(rawReceipt)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return Kvp035ReceiptAdmission.Rejected
    }
    val rawReport = when (val read = readDependency(reportPath)) {
        is Kvp035DependencyRead.Complete -> read.raw
        Kvp035DependencyRead.Rejected -> return Kvp035ReceiptAdmission.Rejected
    }
    val (packet, version) = expected
    val output = packet.task.outputs.single().path
    val headMatches = document.headPolicy == policy &&
        (exactHead == null || document.observedRepositoryHead == exactHead)
    return if (
        document.receiptId == packet.receipt.receiptId && document.taskId == packet.task.id &&
        document.programVersion == version &&
        document.taskDefinitionDigest.value == packet.taskDefinitionDigest.value &&
        document.dependencyReceiptDigests.keys == packet.receipt.dependencies && headMatches &&
        document.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value } ==
        mapOf(output to sha256(rawReport).value) &&
        document.receiptDigest == document.derivedDigest() &&
        encodeTaskProofReceipt(document) == rawReceipt
    ) Kvp035ReceiptAdmission.Complete(document) else Kvp035ReceiptAdmission.Rejected
}

/** Evidence `Path -> Kvp035DependencyRead`; raw UTF-8 exits only at dependency admission. */
private fun readDependency(path: Path): Kvp035DependencyRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp035DependencyRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp035DependencyRead.Rejected
}

private fun dependencyRejected(failure: Kvp035DependencyFailure) =
    Kvp035DependencyAdmission.Rejected(failure)

private val KVP035_DEPENDENCIES = listOf("KVP-011-COMPLETE", "KVP-034-COMPLETE")
