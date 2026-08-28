package support.delivery

import java.nio.file.Path

internal enum class Kvp039DependencyFailure {
    CLOSURE_MISMATCH,
    READ_REJECTED,
    RECEIPT_REJECTED,
    OUTPUT_REJECTED,
    HEAD_MISMATCH,
}

internal class AdmittedKvp039Dependency internal constructor(
    val receiptDigest: String,
    val observedHead: DeliveryGeneration,
)

internal sealed interface Kvp039DependencyAdmission {
    data class Complete(val dependency: AdmittedKvp039Dependency) : Kvp039DependencyAdmission
    data class Rejected(val failure: Kvp039DependencyFailure) : Kvp039DependencyAdmission
}

private sealed interface Kvp039DependencyRead {
    data class Complete(val raw: String) : Kvp039DependencyRead
    data object Rejected : Kvp039DependencyRead
}

/**
 * Proof transition: `(TaskPacket, DeliveryGeneration, KVP-038 receipt, KVP-038 report) ->
 * Kvp039DependencyAdmission`.
 *
 * Establishes the canonical self-digested KVP-038 receipt, its graph identity, its exact current
 * repository observation, and its clean-checkout output digest. Every read, identity, digest,
 * closure, or head mismatch remains finite [Kvp039DependencyFailure]; raw bytes are extracted only
 * at this predecessor boundary.
 */
internal fun admitKvp039Dependency(
    packet: TaskPacket,
    head: DeliveryGeneration,
    receiptPath: Path,
    reportPath: Path,
): Kvp039DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value } != listOf("KVP-038-COMPLETE")) {
        return rejected039Dependency(Kvp039DependencyFailure.CLOSURE_MISMATCH)
    }
    val receiptRaw = when (val read = read039Dependency(receiptPath)) {
        is Kvp039DependencyRead.Complete -> read.raw
        Kvp039DependencyRead.Rejected ->
            return rejected039Dependency(Kvp039DependencyFailure.READ_REJECTED)
    }
    val receipt = when (val decoded = decodeTaskProofReceipt(receiptRaw)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected ->
            return rejected039Dependency(Kvp039DependencyFailure.RECEIPT_REJECTED)
    }
    val reportRaw = when (val read = read039Dependency(reportPath)) {
        is Kvp039DependencyRead.Complete -> read.raw
        Kvp039DependencyRead.Rejected ->
            return rejected039Dependency(Kvp039DependencyFailure.READ_REJECTED)
    }
    val report = try {
        kotlinx.serialization.json.Json.decodeFromString(
            Kvp038CleanCheckoutDocument.serializer(), reportRaw,
        )
    } catch (_: kotlinx.serialization.SerializationException) {
        return rejected039Dependency(Kvp039DependencyFailure.OUTPUT_REJECTED)
    } catch (_: IllegalArgumentException) {
        return rejected039Dependency(Kvp039DependencyFailure.OUTPUT_REJECTED)
    }
    val (expectedPacket, version) = canonicalKvp038Packet()
    val output = expectedPacket.task.outputs.single().path
    val identityMatches = receipt.receiptId == expectedPacket.receipt.receiptId &&
        receipt.taskId == expectedPacket.task.id && receipt.programVersion == version &&
        receipt.taskDefinitionDigest.value == expectedPacket.taskDefinitionDigest.value &&
        receipt.commandDigest == expectedPacket.kvp038CommandDigest() &&
        receipt.dependencyReceiptDigests.keys == expectedPacket.receipt.dependencies &&
        receipt.headPolicy == TaskProofHeadPolicy.CONTENT_SCOPED
    val outputMatches = receipt.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value } ==
        mapOf(output to sha256(reportRaw).value)
    val canonical = receipt.receiptDigest == receipt.derivedDigest() &&
        encodeTaskProofReceipt(receipt) == receiptRaw && encodeKvp038Report(report) == reportRaw
    if (!identityMatches || !outputMatches || !canonical || report.outcome != Kvp038Outcome.COMPLETE) {
        return rejected039Dependency(Kvp039DependencyFailure.RECEIPT_REJECTED)
    }
    if (
        receipt.observedRepositoryHead != head || report.repositoryHead != head.value ||
        report.detachedHead != head.value
    ) return rejected039Dependency(Kvp039DependencyFailure.HEAD_MISMATCH)
    return Kvp039DependencyAdmission.Complete(AdmittedKvp039Dependency(
        receipt.receiptDigest.value,
        receipt.observedRepositoryHead,
    ))
}

private fun read039Dependency(path: Path): Kvp039DependencyRead = when (
    val read = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp039DependencyRead.Complete(
        read.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp039DependencyRead.Rejected
}

private fun rejected039Dependency(failure: Kvp039DependencyFailure) =
    Kvp039DependencyAdmission.Rejected(failure)
