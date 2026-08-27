package support.delivery

import java.nio.file.Path

internal enum class Kvp037DependencyFailure { CLOSURE_MISMATCH }

internal class AdmittedKvp037Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp037DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp037Dependencies) : Kvp037DependencyAdmission
    data class Rejected(val failure: Kvp037DependencyFailure) : Kvp037DependencyAdmission
}

private sealed interface Kvp037ReceiptAdmission {
    data class Complete(val receipt: TaskProofReceiptDocument) : Kvp037ReceiptAdmission
    data object Rejected : Kvp037ReceiptAdmission
}

private sealed interface Kvp037Read {
    data class Complete(val raw: String) : Kvp037Read
    data object Rejected : Kvp037Read
}

private sealed interface Kvp037HeadClosure {
    data object Content : Kvp037HeadClosure
    data class Exact(val head: DeliveryGeneration) : Kvp037HeadClosure
}

/** Canonical packet plus five physical predecessor receipt/output pairs -> admitted closure. */
internal fun admitKvp037Dependencies(
    packet: TaskPacket,
    head: DeliveryGeneration,
    evidence: List<Pair<Path, Path>>,
): Kvp037DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP037_DEPENDENCIES ||
        evidence.size != KVP037_EXPECTATIONS.size
    ) return dependencyRejected()
    val receipts = linkedMapOf<String, TaskProofReceiptDocument>()
    KVP037_EXPECTATIONS.zip(evidence).forEach { (expectation, paths) ->
        val closure = if (expectation.first in KVP037_EXACT) Kvp037HeadClosure.Exact(head)
        else Kvp037HeadClosure.Content
        val document = when (val admitted = admitDependency(
            paths.first,
            paths.second,
            expectation.second(),
            closure,
        )) {
            is Kvp037ReceiptAdmission.Complete -> admitted.receipt
            Kvp037ReceiptAdmission.Rejected -> return dependencyRejected()
        }
        receipts[expectation.first] = document
    }
    return Kvp037DependencyAdmission.Complete(AdmittedKvp037Dependencies(
        receipts.mapValues { it.value.receiptDigest.value },
        defaultIsolatedRuntimeRetirementBatch().readyFrontier,
    ))
}

private fun admitDependency(
    receiptPath: Path,
    reportPath: Path,
    expected: Pair<TaskPacket, TaskProofProgramVersion>,
    closure: Kvp037HeadClosure,
): Kvp037ReceiptAdmission {
    val rawReceipt = (read037(receiptPath) as? Kvp037Read.Complete)?.raw
        ?: return Kvp037ReceiptAdmission.Rejected
    val document = when (val decoded = decodeTaskProofReceipt(rawReceipt)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return Kvp037ReceiptAdmission.Rejected
    }
    val rawReport = (read037(reportPath) as? Kvp037Read.Complete)?.raw
        ?: return Kvp037ReceiptAdmission.Rejected
    val (packet, version) = expected
    val output = packet.task.outputs.single().path
    val headMatches = when (closure) {
        Kvp037HeadClosure.Content -> document.headPolicy == TaskProofHeadPolicy.CONTENT_SCOPED
        is Kvp037HeadClosure.Exact -> document.headPolicy == TaskProofHeadPolicy.EXACT_HEAD &&
            document.observedRepositoryHead == closure.head
    }
    return if (
        document.receiptId == packet.receipt.receiptId && document.taskId == packet.task.id &&
        document.programVersion == version &&
        document.taskDefinitionDigest.value == packet.taskDefinitionDigest.value &&
        document.dependencyReceiptDigests.keys == packet.receipt.dependencies && headMatches &&
        document.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value } ==
        mapOf(output to sha256(rawReport).value) &&
        document.receiptDigest == document.derivedDigest() &&
        encodeTaskProofReceipt(document) == rawReceipt
    ) Kvp037ReceiptAdmission.Complete(document) else Kvp037ReceiptAdmission.Rejected
}

private fun read037(path: Path): Kvp037Read = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp037Read.Complete(read.bytes.toString(Charsets.UTF_8))
    is BoundaryFileRead.Rejected -> Kvp037Read.Rejected
}

private fun dependencyRejected() = Kvp037DependencyAdmission.Rejected(
    Kvp037DependencyFailure.CLOSURE_MISMATCH,
)

private val KVP037_DEPENDENCIES = listOf(
    "KVP-025-COMPLETE",
    "KVP-026-COMPLETE",
    "KVP-027-COMPLETE",
    "KVP-031-COMPLETE",
    "KVP-036-COMPLETE",
)
private val KVP037_EXACT = setOf("KVP-031-COMPLETE", "KVP-036-COMPLETE")
private val KVP037_EXPECTATIONS = listOf<Pair<String, () -> Pair<TaskPacket, TaskProofProgramVersion>>>(
    "KVP-025-COMPLETE" to ::canonicalKvp025Packet,
    "KVP-026-COMPLETE" to ::canonicalKvp026Packet,
    "KVP-027-COMPLETE" to ::canonicalKvp027Packet,
    "KVP-031-COMPLETE" to ::canonicalKvp031Packet,
    "KVP-036-COMPLETE" to ::canonicalKvp036Packet,
)
