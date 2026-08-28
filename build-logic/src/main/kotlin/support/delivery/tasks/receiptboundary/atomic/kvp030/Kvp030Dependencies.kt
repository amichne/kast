package support.delivery

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class Kvp030DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp030Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp030DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp030Dependencies) :
        Kvp030DependencyAdmission

    data class Rejected(val failure: Kvp030DependencyFailure) : Kvp030DependencyAdmission
}

private sealed interface Kvp029BaselineAdmission {
    data class Complete(val baseline: DeliveryGeneration) : Kvp029BaselineAdmission
    data object Rejected : Kvp029BaselineAdmission
}

@Serializable
private data class Kvp030Kvp029ReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp030DependencyReportOutcome,
    val misuse: Kvp030DependencyReportCase,
    val legalPath: Kvp030DependencyReportCase,
    val implementationCommits: List<Kvp030DependencyImplementationCommit>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp030DependencyForbiddenWork>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp030DependencyReportOutcome { COMPLETE }
@Serializable private enum class Kvp030DependencySemanticOutcome { REJECTED, COMPLETE }

@Serializable
private data class Kvp030DependencyReportCase(
    val name: String,
    val outcome: Kvp030DependencySemanticOutcome,
)

@Serializable
private data class Kvp030DependencyImplementationCommit(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp030DependencyForbiddenWork(
    val description: String,
    val enforcementCaseName: String,
)

private val kvp030DependencyJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: canonical KVP-030 packet plus current KVP-029 receipt/report evidence ->
 * `Kvp030DependencyAdmission`.
 *
 * Establishes KVP-029's exact v2 receipt identity, canonical task packet, self-digest, report
 * output digest, and last implementation checkpoint. Read, closure, digest, or output mismatch is
 * finite rejection; raw JSON exists only here.
 */
internal fun admitKvp030Dependencies(
    packet: TaskPacket,
    kvp029Path: Path,
    kvp029ReportPath: Path,
): Kvp030DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value } != listOf(KVP029_RECEIPT_ID)) {
        return rejected(Kvp030DependencyFailure.CLOSURE_MISMATCH)
    }
    val rawReceipt = read(kvp029Path)
        ?: return rejected(Kvp030DependencyFailure.READ_REJECTED)
    val receipt = when (val decoded = decodeTaskProofReceipt(rawReceipt)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected ->
            return rejected(Kvp030DependencyFailure.MALFORMED_RECEIPT)
    }
    val (expectedPacket, expectedVersion) = canonicalKvp029Packet()
    if (
        receipt.receiptId.value != KVP029_RECEIPT_ID ||
        receipt.taskId != expectedPacket.task.id ||
        receipt.programVersion != expectedVersion ||
        receipt.taskDefinitionDigest.value != expectedPacket.taskDefinitionDigest.value ||
        receipt.commandDigest != expectedPacket.kvp029CommandDigest() ||
        receipt.dependencyReceiptDigests.keys != expectedPacket.receipt.dependencies ||
        receipt.headPolicy != TaskProofHeadPolicy.CONTENT_SCOPED ||
        receipt.receiptDigest != receipt.derivedDigest() ||
        encodeTaskProofReceipt(receipt) != rawReceipt
    ) return rejected(Kvp030DependencyFailure.CLOSURE_MISMATCH)
    val report = read(kvp029ReportPath)
        ?: return rejected(Kvp030DependencyFailure.READ_REJECTED)
    val expectedOutput = expectedPacket.task.outputs.single().path
    val outputDigests = receipt.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value }
    if (outputDigests != mapOf(expectedOutput to sha256(report).value)) {
        return rejected(Kvp030DependencyFailure.OUTPUT_MISMATCH)
    }
    val baseline = when (val admitted = admitKvp029Baseline(report)) {
        is Kvp029BaselineAdmission.Complete -> admitted.baseline
        Kvp029BaselineAdmission.Rejected ->
            return rejected(Kvp030DependencyFailure.CLOSURE_MISMATCH)
    }
    return Kvp030DependencyAdmission.Complete(
        AdmittedKvp030Dependencies(
            mapOf(KVP029_RECEIPT_ID to receipt.receiptDigest.value),
            baseline,
        ),
    )
}

/**
 * Proof transition: canonical KVP-029 report JSON -> `Kvp029BaselineAdmission`.
 *
 * Establishes the last ordered KVP-029 implementation checkpoint from a generated, canonically
 * re-encoded complete report. Malformed, wrong-task, or empty reports remain closed rejection.
 */
private fun admitKvp029Baseline(raw: String): Kvp029BaselineAdmission {
    val document = try {
        kvp030DependencyJson.decodeFromString(Kvp030Kvp029ReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp029BaselineAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp029BaselineAdmission.Rejected
    }
    if (
        document.schemaVersion != 1 ||
        document.taskId != KVP029_TASK_ID ||
        document.outcome != Kvp030DependencyReportOutcome.COMPLETE ||
        document.implementationCommits.isEmpty() ||
        raw != kvp030DependencyJson.encodeToString(
            Kvp030Kvp029ReportDocument.serializer(),
            document,
        ) + "\n"
    ) return Kvp029BaselineAdmission.Rejected
    return Kvp029BaselineAdmission.Complete(
        DeliveryGeneration(document.implementationCommits.last().revision),
    )
}

private fun read(path: Path): String? = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> result.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun rejected(failure: Kvp030DependencyFailure) =
    Kvp030DependencyAdmission.Rejected(failure)

private const val KVP029_RECEIPT_ID = "KVP-029-COMPLETE"
private const val KVP029_TASK_ID = "KVP-029"
