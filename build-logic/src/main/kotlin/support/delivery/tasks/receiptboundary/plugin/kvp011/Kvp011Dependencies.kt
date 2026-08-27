package support.delivery

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal enum class Kvp011DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp011Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp011DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp011Dependencies) :
        Kvp011DependencyAdmission
    data class Rejected(val failure: Kvp011DependencyFailure) : Kvp011DependencyAdmission
}

private sealed interface Kvp011PinnedLegacyAdmission {
    data class Complete(val digest: String) : Kvp011PinnedLegacyAdmission
    data object Rejected : Kvp011PinnedLegacyAdmission
}

private sealed interface Kvp011V2DependencyAdmission {
    data class Complete(val document: TaskProofReceiptDocument) : Kvp011V2DependencyAdmission
    data object Rejected : Kvp011V2DependencyAdmission
}

private sealed interface Kvp011DependencyOutputAdmission {
    data object Complete : Kvp011DependencyOutputAdmission
    data object Rejected : Kvp011DependencyOutputAdmission
}

private sealed interface Kvp011BaselineAdmission {
    data class Complete(val baseline: DeliveryGeneration) : Kvp011BaselineAdmission
    data object Rejected : Kvp011BaselineAdmission
}

private sealed interface Kvp011DependencyTextRead {
    data class Complete(val text: String) : Kvp011DependencyTextRead
    data class Rejected(val failure: AuthoritySourceFailure) : Kvp011DependencyTextRead
}

@Serializable
private data class Kvp011Kvp031ReportDocument(
    val schemaVersion: Int,
    val programVersion: String,
    val taskId: String,
    val taskDefinitionDigest: String,
    val dependencyReceiptDigests: Map<String, String>,
    val packetDigest: String,
    val relevantInputDigest: String,
    val commandDigest: String,
    val toolchainDigest: String,
    val outcome: Kvp011DependencyReportOutcome,
    val misuse: Kvp011DependencyReportCase,
    val legalPath: Kvp011DependencyReportCase,
    val implementationCommits: List<Kvp011DependencyCommit>,
    val allowedWrites: List<String>,
    val forbiddenWork: List<Kvp011DependencyForbiddenWork>,
    val observedRepositoryHead: String,
)

@Serializable private enum class Kvp011DependencyReportOutcome { COMPLETE }
@Serializable private enum class Kvp011DependencySemanticOutcome { REJECTED, COMPLETE }
@Serializable private data class Kvp011DependencyReportCase(
    val name: String,
    val outcome: Kvp011DependencySemanticOutcome,
)
@Serializable private data class Kvp011DependencyCommit(
    val revision: String,
    val changedPaths: List<String>,
)
@Serializable private data class Kvp011DependencyForbiddenWork(
    val description: String,
    val enforcementCaseName: String,
)

private val kvp011DependencyJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * Proof transition: canonical KVP-011 packet plus three predecessor receipt closures ->
 * `Kvp011DependencyAdmission`.
 *
 * Establishes the pinned canonical KVP-010 v1 receipt, canonical KVP-025 v2 output, and current
 * exact-head KVP-031 v2 output. The graph-admitted ready frontier becomes the inclusive batch
 * baseline so a later task can retain its distinct physical paths from shared checkpoints.
 * Expected read, schema, closure, or digest failure is finite data; raw JSON and paths exist only
 * at this boundary.
 */
internal fun admitKvp011Dependencies(
    packet: TaskPacket,
    observedHead: DeliveryGeneration,
    kvp010Path: Path,
    kvp025Path: Path,
    kvp025ReportPath: Path,
    kvp031Path: Path,
    kvp031ReportPath: Path,
): Kvp011DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP011_DEPENDENCY_IDS) {
        return rejected(Kvp011DependencyFailure.CLOSURE_MISMATCH)
    }
    val kvp010Raw = when (val read = readDependencyText(kvp010Path)) {
        is Kvp011DependencyTextRead.Complete -> read.text
        is Kvp011DependencyTextRead.Rejected -> return readRejected()
    }
    val kvp010 = when (val admitted = admitPinnedKvp010(kvp010Raw)) {
        is Kvp011PinnedLegacyAdmission.Complete -> admitted.digest
        Kvp011PinnedLegacyAdmission.Rejected -> return rejected(
            Kvp011DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    val kvp025Raw = when (val read = readDependencyText(kvp025Path)) {
        is Kvp011DependencyTextRead.Complete -> read.text
        is Kvp011DependencyTextRead.Rejected -> return readRejected()
    }
    val kvp025 = when (val admitted = admitV2(
        kvp025Raw, canonicalKvp025Packet(), TaskProofHeadPolicy.CONTENT_SCOPED,
    )) {
        is Kvp011V2DependencyAdmission.Complete -> admitted.document
        Kvp011V2DependencyAdmission.Rejected -> return rejected(
            Kvp011DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    val kvp025Report = when (val read = readDependencyText(kvp025ReportPath)) {
        is Kvp011DependencyTextRead.Complete -> read.text
        is Kvp011DependencyTextRead.Rejected -> return readRejected()
    }
    if (admitDependencyOutput(
            kvp025, canonicalKvp025TaskPacket(), kvp025Report,
        ) is Kvp011DependencyOutputAdmission.Rejected
    ) {
        return rejected(Kvp011DependencyFailure.OUTPUT_MISMATCH)
    }
    val kvp031Raw = when (val read = readDependencyText(kvp031Path)) {
        is Kvp011DependencyTextRead.Complete -> read.text
        is Kvp011DependencyTextRead.Rejected -> return readRejected()
    }
    val kvp031 = when (val admitted = admitV2(
        kvp031Raw, canonicalKvp031Packet(), TaskProofHeadPolicy.EXACT_HEAD,
    )) {
        is Kvp011V2DependencyAdmission.Complete -> admitted.document
        Kvp011V2DependencyAdmission.Rejected -> return rejected(
            Kvp011DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    if (kvp031.observedRepositoryHead != observedHead) {
        return rejected(Kvp011DependencyFailure.CLOSURE_MISMATCH)
    }
    val kvp031Report = when (val read = readDependencyText(kvp031ReportPath)) {
        is Kvp011DependencyTextRead.Complete -> read.text
        is Kvp011DependencyTextRead.Rejected -> return readRejected()
    }
    if (admitDependencyOutput(
            kvp031, canonicalKvp031TaskPacket(), kvp031Report,
        ) is Kvp011DependencyOutputAdmission.Rejected
    ) {
        return rejected(Kvp011DependencyFailure.OUTPUT_MISMATCH)
    }
    when (val admitted = admitKvp031Baseline(kvp031Report, observedHead)) {
        is Kvp011BaselineAdmission.Complete -> admitted.baseline
        Kvp011BaselineAdmission.Rejected -> return rejected(
            Kvp011DependencyFailure.CLOSURE_MISMATCH,
        )
    }
    return Kvp011DependencyAdmission.Complete(
        AdmittedKvp011Dependencies(linkedMapOf(
            KVP010_RECEIPT_ID to kvp010,
            KVP025_RECEIPT_ID to kvp025.receiptDigest.value,
            KVP031_RECEIPT_ID to kvp031.receiptDigest.value,
        ), hostedProductionCompositionBatch().readyFrontier),
    )
}

/** Raw legacy receipt JSON -> exact pinned KVP-010 digest or closed rejection. */
private fun admitPinnedKvp010(raw: String): Kvp011PinnedLegacyAdmission {
    val document = when (val decoded = decodeProofReceiptDocument(raw)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return Kvp011PinnedLegacyAdmission.Rejected
    }
    return if (
        document.receiptId.value == KVP010_RECEIPT_ID &&
            document.taskId.value == KVP010_TASK_ID &&
            document.gateId.value == KVP010_GATE_ID &&
            document.programFingerprint.value == KVP010_PROGRAM_FINGERPRINT &&
            document.requirementFingerprint.value == LEGACY_PREFIX_REQUIREMENT_FINGERPRINT &&
            document.receiptDigest.value == KVP010_RECEIPT_DIGEST &&
            document.receiptDigest == document.derivedDigest() &&
            encodeProofReceiptDocument(document) == raw
    ) Kvp011PinnedLegacyAdmission.Complete(KVP010_RECEIPT_DIGEST)
    else Kvp011PinnedLegacyAdmission.Rejected
}

/** Raw v2 receipt JSON plus canonical packet/policy -> admitted dependency or closed rejection. */
private fun admitV2(
    raw: String,
    expected: Pair<TaskPacket, TaskProofProgramVersion>,
    policy: TaskProofHeadPolicy,
): Kvp011V2DependencyAdmission {
    val document = when (val decoded = decodeTaskProofReceipt(raw)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected ->
            return Kvp011V2DependencyAdmission.Rejected
    }
    val (packet, version) = expected
    return if (
        document.receiptId == packet.receipt.receiptId &&
            document.taskId == packet.task.id &&
            document.programVersion == version &&
            document.taskDefinitionDigest.value == packet.taskDefinitionDigest.value &&
            document.commandDigest == packet.kvp011CommandDigest() &&
            document.dependencyReceiptDigests.keys == packet.receipt.dependencies &&
            document.headPolicy == policy &&
            document.receiptDigest == document.derivedDigest() &&
            encodeTaskProofReceipt(document) == raw
    ) Kvp011V2DependencyAdmission.Complete(document)
    else Kvp011V2DependencyAdmission.Rejected
}

/** Admitted receipt, canonical packet, and physical report -> exact output closure. */
private fun admitDependencyOutput(
    receipt: TaskProofReceiptDocument,
    packet: TaskPacket,
    report: String,
): Kvp011DependencyOutputAdmission = if (
    receipt.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value } ==
    mapOf(packet.task.outputs.single().path to sha256(report).value)
) Kvp011DependencyOutputAdmission.Complete else Kvp011DependencyOutputAdmission.Rejected

/** Canonical KVP-031 report JSON/current head -> last implementation checkpoint or rejection. */
private fun admitKvp031Baseline(
    raw: String,
    observedHead: DeliveryGeneration,
): Kvp011BaselineAdmission {
    val document = try {
        kvp011DependencyJson.decodeFromString(Kvp011Kvp031ReportDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return Kvp011BaselineAdmission.Rejected
    } catch (_: IllegalArgumentException) {
        return Kvp011BaselineAdmission.Rejected
    }
    if (
        document.schemaVersion != 1 || document.taskId != KVP031_TASK_ID ||
        document.outcome != Kvp011DependencyReportOutcome.COMPLETE ||
        document.observedRepositoryHead != observedHead.value ||
        document.implementationCommits.isEmpty() ||
        document.implementationCommits.any {
            it.changedPaths.isEmpty() || it.changedPaths != it.changedPaths.sorted()
        } ||
        raw != kvp011DependencyJson.encodeToString(
            Kvp011Kvp031ReportDocument.serializer(), document,
        ) + "\n"
    ) return Kvp011BaselineAdmission.Rejected
    return when (val refined = refineDeliveryGeneration(
        document.implementationCommits.first().revision,
    )) {
        is DeliveryRefinement.Complete -> Kvp011BaselineAdmission.Complete(refined.value)
        is DeliveryRefinement.Rejected -> Kvp011BaselineAdmission.Rejected
    }
}

/** Dependency `Path -> Kvp011DependencyTextRead`; bounds regular non-symlink UTF-8 evidence. */
private fun readDependencyText(path: Path): Kvp011DependencyTextRead = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp011DependencyTextRead.Complete(
        result.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp011DependencyTextRead.Rejected(result.failure)
}

private fun readRejected() = rejected(Kvp011DependencyFailure.READ_REJECTED)
private fun rejected(failure: Kvp011DependencyFailure) =
    Kvp011DependencyAdmission.Rejected(failure)

private val KVP011_DEPENDENCY_IDS = listOf(
    "KVP-010-COMPLETE", "KVP-025-COMPLETE", "KVP-031-COMPLETE",
)
private const val KVP010_RECEIPT_ID = "KVP-010-COMPLETE"
private const val KVP010_TASK_ID = "KVP-010"
private const val KVP010_GATE_ID = "KVP-010-COMPLETE-GATE"
private const val KVP010_RECEIPT_DIGEST =
    "7d532dba031c394693dfd828c92be3f3b38c6096cc1d39d0864e5d9bac680685"
private const val KVP010_PROGRAM_FINGERPRINT =
    "31fcef0d003e673781fe38c8aa52e9ad3c4aadec4a888764bbe17645abaf8888"
private const val KVP025_RECEIPT_ID = "KVP-025-COMPLETE"
private const val KVP031_RECEIPT_ID = "KVP-031-COMPLETE"
private const val KVP031_TASK_ID = "KVP-031"
