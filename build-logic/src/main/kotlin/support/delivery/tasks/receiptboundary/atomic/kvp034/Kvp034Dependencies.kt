package support.delivery

import java.nio.file.Path

internal enum class Kvp034DependencyFailure { READ_REJECTED, CLOSURE_MISMATCH }

internal class AdmittedKvp034Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp034DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp034Dependencies) :
        Kvp034DependencyAdmission
    data class Rejected(val failure: Kvp034DependencyFailure) : Kvp034DependencyAdmission
}

private sealed interface Kvp034HeadClosure {
    data object Content : Kvp034HeadClosure
    data class Exact(val head: DeliveryGeneration) : Kvp034HeadClosure
}

private sealed interface Kvp034DependencyRead {
    data class Complete(val raw: String) : Kvp034DependencyRead
    data object Rejected : Kvp034DependencyRead
}

private sealed interface Kvp034ReceiptAdmission {
    data class Complete(val receipt: TaskProofReceiptDocument) : Kvp034ReceiptAdmission
    data object Rejected : Kvp034ReceiptAdmission
}

/**
 * Proof transition: canonical KVP-034 packet plus predecessor receipt/output paths ->
 * `Kvp034DependencyAdmission`.
 *
 * Establishes the exact graph dependency set, canonical v2 receipt bytes, physical output
 * digests, current KVP-031 exact-head identity, and KVP-033's binding to that identity. Expected
 * read or closure mismatch is finite [Kvp034DependencyFailure] data. Raw JSON exists only here.
 */
internal fun admitKvp034Dependencies(
    packet: TaskPacket,
    observedHead: DeliveryGeneration,
    kvp027Receipt: Path,
    kvp027Report: Path,
    kvp031Receipt: Path,
    kvp031Report: Path,
    kvp033Receipt: Path,
    kvp033Report: Path,
): Kvp034DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP034_DEPENDENCIES) {
        return rejected(Kvp034DependencyFailure.CLOSURE_MISMATCH)
    }
    val kvp027 = when (val admitted = admitDependency(
        kvp027Receipt, kvp027Report, canonicalKvp027Packet(), Kvp034HeadClosure.Content,
    )) {
        is Kvp034ReceiptAdmission.Complete -> admitted.receipt
        Kvp034ReceiptAdmission.Rejected -> return rejected(Kvp034DependencyFailure.CLOSURE_MISMATCH)
    }
    val kvp031 = when (val admitted = admitDependency(
        kvp031Receipt, kvp031Report, canonicalKvp031Packet(),
        Kvp034HeadClosure.Exact(observedHead),
    )) {
        is Kvp034ReceiptAdmission.Complete -> admitted.receipt
        Kvp034ReceiptAdmission.Rejected -> return rejected(Kvp034DependencyFailure.CLOSURE_MISMATCH)
    }
    val kvp033 = when (val admitted = admitDependency(
        kvp033Receipt, kvp033Report, canonicalKvp033Packet(), Kvp034HeadClosure.Content,
    )) {
        is Kvp034ReceiptAdmission.Complete -> admitted.receipt
        Kvp034ReceiptAdmission.Rejected -> return rejected(Kvp034DependencyFailure.CLOSURE_MISMATCH)
    }
    if (kvp033.dependencyReceiptDigests[ReceiptId("KVP-031-COMPLETE")]?.value !=
        kvp031.receiptDigest.value
    ) {
        return rejected(Kvp034DependencyFailure.CLOSURE_MISMATCH)
    }
    return Kvp034DependencyAdmission.Complete(AdmittedKvp034Dependencies(
        linkedMapOf(
            "KVP-027-COMPLETE" to kvp027.receiptDigest.value,
            "KVP-031-COMPLETE" to kvp031.receiptDigest.value,
            "KVP-033-COMPLETE" to kvp033.receiptDigest.value,
        ),
        DeliveryGeneration(KVP034_READY_FRONTIER_HEAD),
    ))
}

/**
 * Proof transition: receipt/report paths plus canonical packet/head policy ->
 * `Kvp034ReceiptAdmission`.
 *
 * Establishes canonical receipt identity, head policy, output digest, and self digest. Malformed,
 * unreadable, stale, or mismatched evidence is closed rejection; raw JSON exists only here.
 */
private fun admitDependency(
    receiptPath: Path,
    reportPath: Path,
    expected: Pair<TaskPacket, TaskProofProgramVersion>,
    closure: Kvp034HeadClosure,
): Kvp034ReceiptAdmission {
    val rawReceipt = when (val read = readKvp034Dependency(receiptPath)) {
        is Kvp034DependencyRead.Complete -> read.raw
        Kvp034DependencyRead.Rejected -> return Kvp034ReceiptAdmission.Rejected
    }
    val document = when (val decoded = decodeTaskProofReceipt(rawReceipt)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return Kvp034ReceiptAdmission.Rejected
    }
    val rawReport = when (val read = readKvp034Dependency(reportPath)) {
        is Kvp034DependencyRead.Complete -> read.raw
        Kvp034DependencyRead.Rejected -> return Kvp034ReceiptAdmission.Rejected
    }
    val (packet, version) = expected
    val output = packet.task.outputs.single().path
    val headMatches = when (closure) {
        Kvp034HeadClosure.Content -> document.headPolicy == TaskProofHeadPolicy.CONTENT_SCOPED
        is Kvp034HeadClosure.Exact -> document.headPolicy == TaskProofHeadPolicy.EXACT_HEAD &&
            document.observedRepositoryHead == closure.head
    }
    return if (
        document.receiptId == packet.receipt.receiptId && document.taskId == packet.task.id &&
            document.programVersion == version &&
            document.taskDefinitionDigest.value == packet.taskDefinitionDigest.value &&
            document.dependencyReceiptDigests.keys == packet.receipt.dependencies && headMatches &&
            document.outputDigests.mapKeys { entry -> entry.key.value }.mapValues { entry ->
                entry.value.value
            } == mapOf(output to sha256(rawReport).value) &&
            document.receiptDigest == document.derivedDigest() &&
            encodeTaskProofReceipt(document) == rawReceipt
    ) Kvp034ReceiptAdmission.Complete(document) else Kvp034ReceiptAdmission.Rejected
}

/** Dependency evidence path -> bounded UTF-8 text or closed rejection. */
private fun readKvp034Dependency(path: Path): Kvp034DependencyRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp034DependencyRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp034DependencyRead.Rejected
}

private fun rejected(failure: Kvp034DependencyFailure) =
    Kvp034DependencyAdmission.Rejected(failure)

private val KVP034_DEPENDENCIES =
    listOf("KVP-027-COMPLETE", "KVP-031-COMPLETE", "KVP-033-COMPLETE")
private const val KVP034_READY_FRONTIER_HEAD =
    "8950b785a7ea7cbaa4714d4429f7c9d5d08ba392"
