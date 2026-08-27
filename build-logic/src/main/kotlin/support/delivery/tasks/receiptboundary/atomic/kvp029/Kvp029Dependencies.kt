package support.delivery

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class Kvp029DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp029Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp029DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp029Dependencies) :
        Kvp029DependencyAdmission
    data class Rejected(val failure: Kvp029DependencyFailure) : Kvp029DependencyAdmission
}

private sealed interface Kvp029LegacyDependencyAdmission {
    data class Complete(val digest: String) : Kvp029LegacyDependencyAdmission
    data object Rejected : Kvp029LegacyDependencyAdmission
}

private sealed interface Kvp028BaselineAdmission {
    data class Complete(val baseline: DeliveryGeneration) : Kvp028BaselineAdmission
    data object Rejected : Kvp028BaselineAdmission
}

@Serializable
private data class Kvp029Kvp028ReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp029DependencyReportOutcome,
    val misuse: Kvp029DependencyReportCase,
    val legalPath: Kvp029DependencyReportCase,
    val implementationCommits: List<Kvp029DependencyImplementationCommit>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp029DependencyForbiddenWork>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp029DependencyReportOutcome { COMPLETE }
@Serializable private enum class Kvp029DependencySemanticOutcome { REJECTED, COMPLETE }

@Serializable
private data class Kvp029DependencyReportCase(
    val name: String,
    val outcome: Kvp029DependencySemanticOutcome,
)

@Serializable
private data class Kvp029DependencyImplementationCommit(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp029DependencyForbiddenWork(
    val description: String,
    val enforcementCaseName: String,
)

private val kvp029DependencyJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: canonical KVP-029 packet plus KVP-021/KVP-023/KVP-028 evidence ->
 * `Kvp029DependencyAdmission`.
 *
 * Establishes the exact pinned legacy receipt bytes for KVP-021 and KVP-023, plus KVP-028's v2
 * receipt, exact report output digest, and last implementation checkpoint. Expected read,
 * closure, digest, or output mismatch remains finite rejection; raw JSON exists only here.
 */
internal fun admitKvp029Dependencies(
    packet: TaskPacket,
    kvp021Path: Path,
    kvp023Path: Path,
    kvp028Path: Path,
    kvp028ReportPath: Path,
): Kvp029DependencyAdmission {
    val expectedIds = packet.receipt.dependencies.map { it.value }.sorted()
    if (expectedIds != listOf(KVP021_RECEIPT_ID, KVP023_RECEIPT_ID, KVP028_RECEIPT_ID)) {
        return rejected(Kvp029DependencyFailure.CLOSURE_MISMATCH)
    }
    val kvp021 = admitLegacyDependency(
        kvp021Path,
        KVP021_RECEIPT_ID,
        KVP021_TASK_ID,
        KVP021_GATE_ID,
        KVP021_RECEIPT_DIGEST,
    )
    val kvp023 = admitLegacyDependency(
        kvp023Path,
        KVP023_RECEIPT_ID,
        KVP023_TASK_ID,
        KVP023_GATE_ID,
        KVP023_RECEIPT_DIGEST,
    )
    if (kvp021 !is Kvp029LegacyDependencyAdmission.Complete ||
        kvp023 !is Kvp029LegacyDependencyAdmission.Complete
    ) return rejected(Kvp029DependencyFailure.CLOSURE_MISMATCH)

    val rawReceipt = read(kvp028Path)
        ?: return rejected(Kvp029DependencyFailure.READ_REJECTED)
    val receipt = when (val decoded = decodeTaskProofReceipt(rawReceipt)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected ->
            return rejected(Kvp029DependencyFailure.MALFORMED_RECEIPT)
    }
    val (expectedPacket, expectedVersion) = canonicalKvp028Packet()
    if (
        receipt.receiptId.value != KVP028_RECEIPT_ID ||
        receipt.taskId != expectedPacket.task.id ||
        receipt.programVersion != expectedVersion ||
        receipt.taskDefinitionDigest.value != expectedPacket.taskDefinitionDigest.value ||
        receipt.commandDigest != expectedPacket.kvp028CommandDigest() ||
        receipt.dependencyReceiptDigests.keys != expectedPacket.receipt.dependencies ||
        receipt.headPolicy != TaskProofHeadPolicy.CONTENT_SCOPED ||
        receipt.receiptDigest != receipt.derivedDigest() ||
        encodeTaskProofReceipt(receipt) != rawReceipt
    ) return rejected(Kvp029DependencyFailure.CLOSURE_MISMATCH)
    val report = read(kvp028ReportPath)
        ?: return rejected(Kvp029DependencyFailure.READ_REJECTED)
    val expectedOutput = expectedPacket.task.outputs.single().path
    val outputDigests = receipt.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value }
    if (outputDigests != mapOf(expectedOutput to sha256(report).value)) {
        return rejected(Kvp029DependencyFailure.OUTPUT_MISMATCH)
    }
    val baseline = when (val admitted = admitKvp028Baseline(report)) {
        is Kvp028BaselineAdmission.Complete -> admitted.baseline
        Kvp028BaselineAdmission.Rejected ->
            return rejected(Kvp029DependencyFailure.CLOSURE_MISMATCH)
    }
    return Kvp029DependencyAdmission.Complete(
        AdmittedKvp029Dependencies(
            mapOf(
                KVP021_RECEIPT_ID to kvp021.digest,
                KVP023_RECEIPT_ID to kvp023.digest,
                KVP028_RECEIPT_ID to receipt.receiptDigest.value,
            ),
            baseline,
        ),
    )
}

/**
 * Proof transition: legacy v1 receipt path plus pinned identity ->
 * `Kvp029LegacyDependencyAdmission`.
 *
 * Establishes exact canonical-prefix task, gate, head, program, requirement, and self-digest
 * identity. Any different regenerated receipt is rejected even when internally self-consistent.
 */
private fun admitLegacyDependency(
    path: Path,
    receiptId: String,
    taskId: String,
    gateId: String,
    receiptDigest: String,
): Kvp029LegacyDependencyAdmission {
    val raw = read(path) ?: return Kvp029LegacyDependencyAdmission.Rejected
    val document = when (val decoded = decodeProofReceiptDocument(raw)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return Kvp029LegacyDependencyAdmission.Rejected
    }
    return if (
        document.receiptId.value == receiptId &&
        document.taskId.value == taskId &&
        document.gateId.value == gateId &&
        document.exactHead.value == LEGACY_PREFIX_EXACT_HEAD &&
        document.programFingerprint.value == LEGACY_PREFIX_PROGRAM_FINGERPRINT &&
        document.requirementFingerprint.value == LEGACY_PREFIX_REQUIREMENT_FINGERPRINT &&
        document.receiptDigest.value == receiptDigest &&
        document.receiptDigest == document.derivedDigest() &&
        encodeProofReceiptDocument(document) == raw
    ) {
        Kvp029LegacyDependencyAdmission.Complete(document.receiptDigest.value)
    } else {
        Kvp029LegacyDependencyAdmission.Rejected
    }
}

/**
 * Proof transition: canonical KVP-028 report JSON -> `Kvp028BaselineAdmission`.
 *
 * Establishes the last ordered KVP-028 implementation checkpoint from a generated, canonically
 * re-encoded complete report. Malformed, wrong-task, or empty reports remain closed rejection.
 */
private fun admitKvp028Baseline(raw: String): Kvp028BaselineAdmission {
    val document = try {
        kvp029DependencyJson.decodeFromString(Kvp029Kvp028ReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp028BaselineAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp028BaselineAdmission.Rejected
    }
    if (
        document.schemaVersion != 1 ||
        document.taskId != KVP028_TASK_ID ||
        document.outcome != Kvp029DependencyReportOutcome.COMPLETE ||
        document.implementationCommits.isEmpty() ||
        raw != kvp029DependencyJson.encodeToString(
            Kvp029Kvp028ReportDocument.serializer(),
            document,
        ) + "\n"
    ) return Kvp028BaselineAdmission.Rejected
    return Kvp028BaselineAdmission.Complete(
        DeliveryGeneration(document.implementationCommits.last().revision),
    )
}

private fun read(path: Path): String? = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> result.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun rejected(failure: Kvp029DependencyFailure) =
    Kvp029DependencyAdmission.Rejected(failure)

private const val LEGACY_PREFIX_EXACT_HEAD = "4a323a97d93964dd6b49c27ce77c45bf651b29c4"
private const val KVP021_RECEIPT_ID = "KVP-021-COMPLETE"
private const val KVP021_TASK_ID = "KVP-021"
private const val KVP021_GATE_ID = "KVP-021-COMPLETE-GATE"
private const val KVP021_RECEIPT_DIGEST =
    "eb7dfda5be1fb282e164d0fc6148f19f2c18786302322989954d2657197dcaf2"
private const val KVP023_RECEIPT_ID = "KVP-023-COMPLETE"
private const val KVP023_TASK_ID = "KVP-023"
private const val KVP023_GATE_ID = "KVP-023-COMPLETE-GATE"
private const val KVP023_RECEIPT_DIGEST =
    "e3d7587ea38783234f7735dd8715d424eddeb399210dd41fbf00faf96ba8292e"
private const val KVP028_RECEIPT_ID = "KVP-028-COMPLETE"
private const val KVP028_TASK_ID = "KVP-028"
