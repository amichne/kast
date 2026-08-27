package support.delivery

import java.nio.file.Path

internal enum class Kvp036DependencyFailure { READ_REJECTED, CLOSURE_MISMATCH }

internal class AdmittedKvp036Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp036DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp036Dependencies) :
        Kvp036DependencyAdmission
    data class Rejected(val failure: Kvp036DependencyFailure) : Kvp036DependencyAdmission
}

private sealed interface Kvp036DependencyRead {
    data class Complete(val raw: String) : Kvp036DependencyRead
    data object Rejected : Kvp036DependencyRead
}

private sealed interface Kvp036ReceiptAdmission {
    data class Complete(val receipt: TaskProofReceiptDocument) : Kvp036ReceiptAdmission
    data object Rejected : Kvp036ReceiptAdmission
}

/**
 * Proof transition: canonical packet plus KVP-027/KVP-035 physical evidence ->
 * `Kvp036DependencyAdmission`.
 *
 * Establishes the exact graph dependency set, canonical receipt bytes, physical output digests,
 * content closure for both dependencies and the graph-owned retirement frontier. Malformed,
 * stale, or mismatched evidence is finite rejection; raw JSON exists here.
 */
internal fun admitKvp036Dependencies(
    packet: TaskPacket,
    kvp027Receipt: Path,
    kvp027Report: Path,
    kvp035Receipt: Path,
    kvp035Report: Path,
): Kvp036DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP036_DEPENDENCIES) {
        return dependencyRejected(Kvp036DependencyFailure.CLOSURE_MISMATCH)
    }
    val kvp027 = when (val admitted = admitDependency(
        kvp027Receipt, kvp027Report, canonicalKvp027Packet(), TaskProofHeadPolicy.CONTENT_SCOPED,
        null,
    )) {
        is Kvp036ReceiptAdmission.Complete -> admitted.receipt
        Kvp036ReceiptAdmission.Rejected -> return dependencyRejected(
            Kvp036DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    val kvp035 = when (val admitted = admitDependency(
        kvp035Receipt, kvp035Report, canonicalKvp035Packet(), TaskProofHeadPolicy.CONTENT_SCOPED,
        null,
    )) {
        is Kvp036ReceiptAdmission.Complete -> admitted.receipt
        Kvp036ReceiptAdmission.Rejected -> return dependencyRejected(
            Kvp036DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    return Kvp036DependencyAdmission.Complete(AdmittedKvp036Dependencies(
        linkedMapOf(
            "KVP-027-COMPLETE" to kvp027.receiptDigest.value,
            "KVP-035-COMPLETE" to kvp035.receiptDigest.value,
        ),
        defaultIsolatedRuntimeRetirementBatch().readyFrontier,
    ))
}

/**
 * Proof transition: receipt/report paths plus canonical packet and head policy ->
 * `Kvp036ReceiptAdmission`.
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
): Kvp036ReceiptAdmission {
    val rawReceipt = when (val read = readDependency(receiptPath)) {
        is Kvp036DependencyRead.Complete -> read.raw
        Kvp036DependencyRead.Rejected -> return Kvp036ReceiptAdmission.Rejected
    }
    val document = when (val decoded = decodeTaskProofReceipt(rawReceipt)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return Kvp036ReceiptAdmission.Rejected
    }
    val rawReport = when (val read = readDependency(reportPath)) {
        is Kvp036DependencyRead.Complete -> read.raw
        Kvp036DependencyRead.Rejected -> return Kvp036ReceiptAdmission.Rejected
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
    ) Kvp036ReceiptAdmission.Complete(document) else Kvp036ReceiptAdmission.Rejected
}

/** Evidence `Path -> Kvp036DependencyRead`; raw UTF-8 exits only at dependency admission. */
private fun readDependency(path: Path): Kvp036DependencyRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp036DependencyRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp036DependencyRead.Rejected
}

private fun dependencyRejected(failure: Kvp036DependencyFailure) =
    Kvp036DependencyAdmission.Rejected(failure)

private val KVP036_DEPENDENCIES = listOf("KVP-027-COMPLETE", "KVP-035-COMPLETE")
