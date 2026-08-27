package support.delivery

import java.nio.file.Path

internal enum class Kvp026DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    IDENTITY_MISMATCH,
    DIGEST_MISMATCH,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp026Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp026DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp026Dependencies) :
        Kvp026DependencyAdmission
    data class Rejected(val failure: Kvp026DependencyFailure) :
        Kvp026DependencyAdmission
}

/**
 * Proof transition: canonical KVP-026 packet plus predecessor receipt/report paths ->
 * `Kvp026DependencyAdmission`.
 *
 * Establishes the exact graph-declared predecessor set, pinned and self-digested legacy evidence,
 * and the graph-compatible, self-digested KVP-025 v2 receipt whose output digest matches its
 * report. The admitted KVP-025 observed head becomes the only implementation baseline. Expected
 * read, identity, closure, digest, or output mismatches remain finite rejection; raw receipt JSON
 * exists only here.
 */
internal fun admitKvp026Dependencies(
    packet: TaskPacket,
    kvp013Path: Path,
    kvp024Path: Path,
    kvp025Path: Path,
    kvp025ReportPath: Path,
): Kvp026DependencyAdmission {
    val expectedIds = packet.receipt.dependencies.map { it.value }.sorted()
    if (expectedIds != KVP026_DEPENDENCY_IDS) return dependencyRejected(
        Kvp026DependencyFailure.CLOSURE_MISMATCH,
    )
    val kvp013 = admitPinnedLegacy(
        read(kvp013Path) ?: return readRejected(),
        KVP013_RECEIPT_ID,
        KVP013_TASK_ID,
        KVP013_GATE_ID,
        KVP013_RECEIPT_DIGEST,
    ) ?: return dependencyRejected(Kvp026DependencyFailure.DIGEST_MISMATCH)
    val kvp024 = when (val admitted = admitLegacyKvp024Prefix(
        read(kvp024Path) ?: return readRejected(),
    )) {
        is LegacyReceiptPrefixFileAdmission.Complete -> admitted.prefix.frontierReceiptDigest.value
        is LegacyReceiptPrefixFileAdmission.Rejected -> return dependencyRejected(
            Kvp026DependencyFailure.DIGEST_MISMATCH,
        )
    }
    val kvp025Raw = read(kvp025Path) ?: return readRejected()
    val kvp025Document = when (val decoded = decodeTaskProofReceipt(kvp025Raw)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return dependencyRejected(
            Kvp026DependencyFailure.MALFORMED_RECEIPT,
        )
    }
    val (kvp025Packet, expectedVersion) = canonicalKvp025Packet()
    val expectedKvp025Output = kvp025Packet.task.outputs.single().path
    val kvp025Report = read(kvp025ReportPath) ?: return readRejected()
    if (kvp025Document.receiptId.value != KVP025_RECEIPT_ID ||
        kvp025Document.taskId != kvp025Packet.task.id ||
        kvp025Document.programVersion != expectedVersion ||
        kvp025Document.taskDefinitionDigest.value != kvp025Packet.taskDefinitionDigest.value ||
        kvp025Document.commandDigest != kvp025Packet.kvp026CommandDigest() ||
        kvp025Document.dependencyReceiptDigests.mapKeys { it.key.value }
            .mapValues { it.value.value } != mapOf(KVP024_RECEIPT_ID to KVP024_RECEIPT_DIGEST) ||
        kvp025Document.headPolicy != TaskProofHeadPolicy.CONTENT_SCOPED ||
        kvp025Document.receiptDigest != kvp025Document.derivedDigest() ||
        encodeTaskProofReceipt(kvp025Document) != kvp025Raw
    ) return dependencyRejected(Kvp026DependencyFailure.CLOSURE_MISMATCH)
    val outputDigests = kvp025Document.outputDigests.mapKeys { it.key.value }
        .mapValues { it.value.value }
    if (outputDigests != mapOf(expectedKvp025Output to sha256(kvp025Report).value)) {
        return dependencyRejected(Kvp026DependencyFailure.OUTPUT_MISMATCH)
    }
    return Kvp026DependencyAdmission.Complete(
        AdmittedKvp026Dependencies(
            linkedMapOf(
                KVP013_RECEIPT_ID to kvp013,
                KVP024_RECEIPT_ID to kvp024,
                KVP025_RECEIPT_ID to kvp025Document.receiptDigest.value,
            ),
            kvp025Document.observedRepositoryHead,
        ),
    )
}

private fun admitPinnedLegacy(
    raw: String,
    receiptId: String,
    taskId: String,
    gateId: String,
    digest: String,
): String? {
    val document = when (val decoded = decodeProofReceiptDocument(raw)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return null
    }
    return digest.takeIf {
        document.receiptId.value == receiptId &&
            document.taskId.value == taskId &&
            document.gateId.value == gateId &&
            document.programFingerprint.value == LEGACY_PREFIX_PROGRAM_FINGERPRINT &&
            document.requirementFingerprint.value == LEGACY_PREFIX_REQUIREMENT_FINGERPRINT &&
            document.receiptDigest.value == digest &&
            document.receiptDigest == document.derivedDigest()
    }
}

private fun read(path: Path): String? = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> result.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun readRejected() = dependencyRejected(Kvp026DependencyFailure.READ_REJECTED)

private fun dependencyRejected(failure: Kvp026DependencyFailure) =
    Kvp026DependencyAdmission.Rejected(failure)

private val KVP026_DEPENDENCY_IDS = listOf(
    "KVP-013-COMPLETE",
    "KVP-024-COMPLETE",
    "KVP-025-COMPLETE",
)
private const val KVP013_RECEIPT_ID = "KVP-013-COMPLETE"
private const val KVP013_TASK_ID = "KVP-013"
private const val KVP013_GATE_ID = "KVP-013-COMPLETE-GATE"
private const val KVP013_RECEIPT_DIGEST =
    "1437d70f0623f05567c0032383944f46f3ddcd19173a7082d21f1f77457463f2"
private const val KVP024_RECEIPT_ID = "KVP-024-COMPLETE"
private const val KVP024_RECEIPT_DIGEST = LEGACY_PREFIX_FRONTIER_RECEIPT_DIGEST
private const val KVP025_RECEIPT_ID = "KVP-025-COMPLETE"
