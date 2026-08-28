package support.delivery

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class Kvp031DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp031Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp031DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp031Dependencies) :
        Kvp031DependencyAdmission

    data class Rejected(val failure: Kvp031DependencyFailure) : Kvp031DependencyAdmission
}

private sealed interface Kvp030BaselineAdmission {
    data class Complete(val baseline: DeliveryGeneration) : Kvp030BaselineAdmission
    data object Rejected : Kvp030BaselineAdmission
}

@Serializable
private data class Kvp031Kvp030ReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp031DependencyReportOutcome,
    val misuse: Kvp031DependencyReportCase,
    val legalPath: Kvp031DependencyReportCase,
    val implementationCommits: List<Kvp031DependencyImplementationCommit>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp031DependencyForbiddenWork>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp031DependencyReportOutcome { COMPLETE }
@Serializable private enum class Kvp031DependencySemanticOutcome { REJECTED, COMPLETE }

@Serializable
private data class Kvp031DependencyReportCase(
    val name: String,
    val outcome: Kvp031DependencySemanticOutcome,
)

@Serializable
private data class Kvp031DependencyImplementationCommit(
    val revision: String,
    val changedPaths: List<String>,
)

@Serializable
private data class Kvp031DependencyForbiddenWork(
    val description: String,
    val enforcementCaseName: String,
)

private val kvp031DependencyJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: canonical KVP-031 packet plus current KVP-030 receipt/report evidence ->
 * `Kvp031DependencyAdmission`.
 *
 * Establishes KVP-030's exact v2 receipt identity, canonical task packet, self-digest, report
 * output digest, and last implementation checkpoint. Read, closure, digest, or output mismatch is
 * finite rejection; raw JSON exists only here.
 */
internal fun admitKvp031Dependencies(
    packet: TaskPacket,
    kvp030Path: Path,
    kvp030ReportPath: Path,
): Kvp031DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value } != listOf(KVP030_RECEIPT_ID)) {
        return rejected(Kvp031DependencyFailure.CLOSURE_MISMATCH)
    }
    val rawReceipt = read(kvp030Path)
        ?: return rejected(Kvp031DependencyFailure.READ_REJECTED)
    val receipt = when (val decoded = decodeTaskProofReceipt(rawReceipt)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected ->
            return rejected(Kvp031DependencyFailure.MALFORMED_RECEIPT)
    }
    val (expectedPacket, expectedVersion) = canonicalKvp030Packet()
    if (
        receipt.receiptId.value != KVP030_RECEIPT_ID ||
        receipt.taskId != expectedPacket.task.id ||
        receipt.programVersion != expectedVersion ||
        receipt.taskDefinitionDigest.value != expectedPacket.taskDefinitionDigest.value ||
        receipt.commandDigest != expectedPacket.kvp030CommandDigest() ||
        receipt.dependencyReceiptDigests.keys != expectedPacket.receipt.dependencies ||
        receipt.headPolicy != TaskProofHeadPolicy.CONTENT_SCOPED ||
        receipt.receiptDigest != receipt.derivedDigest() ||
        encodeTaskProofReceipt(receipt) != rawReceipt
    ) return rejected(Kvp031DependencyFailure.CLOSURE_MISMATCH)
    val report = read(kvp030ReportPath)
        ?: return rejected(Kvp031DependencyFailure.READ_REJECTED)
    val expectedOutput = expectedPacket.task.outputs.single().path
    val outputDigests = receipt.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value }
    if (outputDigests != mapOf(expectedOutput to sha256(report).value)) {
        return rejected(Kvp031DependencyFailure.OUTPUT_MISMATCH)
    }
    val baseline = when (val admitted = admitKvp030Baseline(report)) {
        is Kvp030BaselineAdmission.Complete -> admitted.baseline
        Kvp030BaselineAdmission.Rejected ->
            return rejected(Kvp031DependencyFailure.CLOSURE_MISMATCH)
    }
    return Kvp031DependencyAdmission.Complete(
        AdmittedKvp031Dependencies(
            mapOf(KVP030_RECEIPT_ID to receipt.receiptDigest.value),
            baseline,
        ),
    )
}

/**
 * Proof transition: canonical KVP-030 report JSON -> `Kvp030BaselineAdmission`.
 *
 * Establishes the last ordered KVP-030 implementation checkpoint from a generated, canonically
 * re-encoded complete report. Malformed, wrong-task, or empty reports remain closed rejection.
 */
private fun admitKvp030Baseline(raw: String): Kvp030BaselineAdmission {
    val document = try {
        kvp031DependencyJson.decodeFromString(Kvp031Kvp030ReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp030BaselineAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp030BaselineAdmission.Rejected
    }
    if (
        document.schemaVersion != 1 ||
        document.taskId != KVP030_TASK_ID ||
        document.outcome != Kvp031DependencyReportOutcome.COMPLETE ||
        document.implementationCommits.isEmpty() ||
        raw != kvp031DependencyJson.encodeToString(
            Kvp031Kvp030ReportDocument.serializer(),
            document,
        ) + "\n"
    ) return Kvp030BaselineAdmission.Rejected
    return Kvp030BaselineAdmission.Complete(
        DeliveryGeneration(document.implementationCommits.last().revision),
    )
}

private fun read(path: Path): String? = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> result.bytes.toString(Charsets.UTF_8)
    is BoundaryFileRead.Rejected -> null
}

private fun rejected(failure: Kvp031DependencyFailure) =
    Kvp031DependencyAdmission.Rejected(failure)

private const val KVP030_RECEIPT_ID = "KVP-030-COMPLETE"
private const val KVP030_TASK_ID = "KVP-030"
