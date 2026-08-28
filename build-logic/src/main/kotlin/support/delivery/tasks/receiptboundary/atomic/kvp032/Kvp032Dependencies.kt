package support.delivery

import java.nio.file.Path

internal enum class Kvp032DependencyFailure {
    READ_REJECTED,
    MALFORMED_RECEIPT,
    CLOSURE_MISMATCH,
    OUTPUT_MISMATCH,
}

internal class AdmittedKvp032Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp032DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp032Dependencies) :
        Kvp032DependencyAdmission
    data class Rejected(val failure: Kvp032DependencyFailure) : Kvp032DependencyAdmission
}

internal enum class Kvp032LegacyKvp009WitnessFailure {
    MALFORMED_RECEIPT,
    IDENTITY_MISMATCH,
    CLOSURE_MISMATCH,
    DIGEST_MISMATCH,
    NON_CANONICAL_RECEIPT,
}

internal class AdmittedKvp032LegacyKvp009Witness internal constructor(
    val dependencyDigest: TaskProofDependencyDigest,
)

internal sealed interface Kvp032LegacyKvp009WitnessAdmission {
    data class Complete(val witness: AdmittedKvp032LegacyKvp009Witness) :
        Kvp032LegacyKvp009WitnessAdmission
    data class Rejected(val failure: Kvp032LegacyKvp009WitnessFailure) :
        Kvp032LegacyKvp009WitnessAdmission
}

private sealed interface Kvp032DependencyTextRead {
    data class Complete(val text: String) : Kvp032DependencyTextRead
    data object Rejected : Kvp032DependencyTextRead
}

private sealed interface Kvp032ReceiptAdmission {
    data class Complete(val digest: String) : Kvp032ReceiptAdmission
    data object Rejected : Kvp032ReceiptAdmission
}

/**
 * Proof transition: canonical KVP-032 packet plus five predecessor receipt closures ->
 * `Kvp032DependencyAdmission`.
 *
 * Establishes KVP-009 through its admitted KVP-010 successor witness, the pinned KVP-023 legacy
 * receipt, exact KVP-011/KVP-027 v2 outputs, and current exact-head KVP-031 v2 output without
 * rerunning unchanged legacy work. The recorded ready frontier is retained as KVP-032's
 * implementation-scope baseline. Expected read, schema, closure, or digest failure remains finite
 * [Kvp032DependencyFailure] data; raw JSON exists only here.
 */
internal fun admitKvp032Dependencies(
    packet: TaskPacket,
    observedHead: DeliveryGeneration,
    kvp010WitnessPath: Path,
    kvp011Path: Path,
    kvp011ReportPath: Path,
    kvp023Path: Path,
    kvp027Path: Path,
    kvp027ReportPath: Path,
    kvp031Path: Path,
    kvp031ReportPath: Path,
): Kvp032DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP032_DEPENDENCY_IDS) {
        return rejected(Kvp032DependencyFailure.CLOSURE_MISMATCH)
    }
    val dependencies = linkedMapOf<String, String>()
    dependencies[KVP009_RECEIPT_ID] = when (val raw = read(kvp010WitnessPath)) {
        is Kvp032DependencyTextRead.Complete -> when (
            val admitted = admitKvp009ViaKvp010Witness(raw.text)
        ) {
            is Kvp032LegacyKvp009WitnessAdmission.Complete ->
                admitted.witness.dependencyDigest.value
            is Kvp032LegacyKvp009WitnessAdmission.Rejected -> return closureRejected()
        }
        Kvp032DependencyTextRead.Rejected -> return closureRejected()
    }
    dependencies[KVP011_RECEIPT_ID] = when (val admitted = admitV2Output(
        read(kvp011Path), canonicalKvp011Packet(), TaskProofHeadPolicy.CONTENT_SCOPED,
        read(kvp011ReportPath),
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    dependencies[KVP023_RECEIPT_ID] = when (val admitted = admitLegacy(
        read(kvp023Path), KVP023_RECEIPT_ID, KVP023_TASK_ID, KVP023_GATE_ID,
        KVP023_PROGRAM_FINGERPRINT, KVP023_RECEIPT_DIGEST,
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    dependencies[KVP027_RECEIPT_ID] = when (val admitted = admitV2Output(
        read(kvp027Path), canonicalKvp027Packet(), TaskProofHeadPolicy.CONTENT_SCOPED,
        read(kvp027ReportPath),
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    dependencies[KVP031_RECEIPT_ID] = when (val admitted = admitV2Output(
        read(kvp031Path), canonicalKvp031Packet(), TaskProofHeadPolicy.EXACT_HEAD,
        read(kvp031ReportPath), observedHead,
    )) {
        is Kvp032ReceiptAdmission.Complete -> admitted.digest
        Kvp032ReceiptAdmission.Rejected -> return closureRejected()
    }
    return Kvp032DependencyAdmission.Complete(
        AdmittedKvp032Dependencies(
            dependencies,
            hostedProductionCompositionBatch().readyFrontier,
        ),
    )
}

/**
 * Proof transition: canonical raw KVP-010 receipt ->
 * `Kvp032LegacyKvp009WitnessAdmission`.
 *
 * Establishes that the exact admitted KVP-010 receipt canonically self-digests and carries the
 * required KVP-009 dependency digest together with its two gate receipts. The stronger witness
 * exposes only KVP-009 dependency authority. Expected schema, identity, closure, digest, and
 * canonical-encoding failures remain closed [Kvp032LegacyKvp009WitnessFailure] data; raw JSON may
 * be extracted only by [admitKvp032Dependencies].
 */
internal fun admitKvp009ViaKvp010Witness(
    raw: String,
): Kvp032LegacyKvp009WitnessAdmission {
    fun rejected(failure: Kvp032LegacyKvp009WitnessFailure) =
        Kvp032LegacyKvp009WitnessAdmission.Rejected(failure)
    val document = when (val decoded = decodeProofReceiptDocument(raw)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return rejected(
            Kvp032LegacyKvp009WitnessFailure.MALFORMED_RECEIPT,
        )
    }
    if (
        document.receiptId.value != KVP010_RECEIPT_ID ||
        document.taskId.value != KVP010_TASK_ID ||
        document.gateId.value != KVP010_GATE_ID ||
        document.programFingerprint.value != KVP010_PROGRAM_FINGERPRINT ||
        document.requirementFingerprint.value != LEGACY_PREFIX_REQUIREMENT_FINGERPRINT
    ) return rejected(Kvp032LegacyKvp009WitnessFailure.IDENTITY_MISMATCH)
    if (
        document.dependencyReceiptDigests.mapKeys { it.key.value }
            .mapValues { it.value.value } != KVP010_DEPENDENCY_DIGESTS
    ) return rejected(Kvp032LegacyKvp009WitnessFailure.CLOSURE_MISMATCH)
    if (
        document.receiptDigest.value != KVP010_RECEIPT_DIGEST ||
        document.receiptDigest != document.derivedDigest()
    ) return rejected(Kvp032LegacyKvp009WitnessFailure.DIGEST_MISMATCH)
    if (encodeProofReceiptDocument(document) != raw) {
        return rejected(Kvp032LegacyKvp009WitnessFailure.NON_CANONICAL_RECEIPT)
    }
    return Kvp032LegacyKvp009WitnessAdmission.Complete(
        AdmittedKvp032LegacyKvp009Witness(
            TaskProofDependencyDigest(KVP009_RECEIPT_DIGEST),
        ),
    )
}

/** Raw legacy receipt JSON -> exact pinned prefix digest or closed rejection. */
private fun admitLegacy(
    raw: Kvp032DependencyTextRead,
    receiptId: String,
    taskId: String,
    gateId: String,
    programFingerprint: String,
    receiptDigest: String,
): Kvp032ReceiptAdmission {
    if (raw !is Kvp032DependencyTextRead.Complete) return Kvp032ReceiptAdmission.Rejected
    val document = when (val decoded = decodeProofReceiptDocument(raw.text)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return Kvp032ReceiptAdmission.Rejected
    }
    return if (
        document.receiptId.value == receiptId && document.taskId.value == taskId &&
        document.gateId.value == gateId &&
        document.programFingerprint.value == programFingerprint &&
        document.requirementFingerprint.value == LEGACY_PREFIX_REQUIREMENT_FINGERPRINT &&
        document.receiptDigest.value == receiptDigest &&
        document.receiptDigest == document.derivedDigest() &&
        encodeProofReceiptDocument(document) == raw.text
    ) Kvp032ReceiptAdmission.Complete(receiptDigest) else Kvp032ReceiptAdmission.Rejected
}

/** Raw v2 receipt/report JSON plus canonical packet/policy -> exact output closure. */
private fun admitV2Output(
    rawReceipt: Kvp032DependencyTextRead,
    expected: Pair<TaskPacket, TaskProofProgramVersion>,
    policy: TaskProofHeadPolicy,
    rawReport: Kvp032DependencyTextRead,
    exactHead: DeliveryGeneration? = null,
): Kvp032ReceiptAdmission {
    if (
        rawReceipt !is Kvp032DependencyTextRead.Complete ||
        rawReport !is Kvp032DependencyTextRead.Complete
    ) return Kvp032ReceiptAdmission.Rejected
    val document = when (val decoded = decodeTaskProofReceipt(rawReceipt.text)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected -> return Kvp032ReceiptAdmission.Rejected
    }
    val (packet, version) = expected
    val output = packet.task.outputs.single().path
    return if (
        document.receiptId == packet.receipt.receiptId && document.taskId == packet.task.id &&
        document.programVersion == version &&
        document.taskDefinitionDigest.value == packet.taskDefinitionDigest.value &&
        document.commandDigest == packet.kvp032CommandDigest() &&
        document.dependencyReceiptDigests.keys == packet.receipt.dependencies &&
        document.headPolicy == policy &&
        (exactHead == null || document.observedRepositoryHead == exactHead) &&
        document.outputDigests.mapKeys { it.key.value }.mapValues { it.value.value } ==
        mapOf(output to sha256(rawReport.text).value) &&
        document.receiptDigest == document.derivedDigest() &&
        encodeTaskProofReceipt(document) == rawReceipt.text
    ) Kvp032ReceiptAdmission.Complete(document.receiptDigest.value)
    else Kvp032ReceiptAdmission.Rejected
}

/** Dependency `Path -> Kvp032DependencyTextRead`; bounds regular non-symlink UTF-8 evidence. */
private fun read(path: Path): Kvp032DependencyTextRead = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp032DependencyTextRead.Complete(
        result.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp032DependencyTextRead.Rejected
}

private fun rejected(failure: Kvp032DependencyFailure) =
    Kvp032DependencyAdmission.Rejected(failure)
private fun closureRejected() = rejected(Kvp032DependencyFailure.CLOSURE_MISMATCH)

private val KVP032_DEPENDENCY_IDS = listOf(
    "KVP-009-COMPLETE", "KVP-011-COMPLETE", "KVP-023-COMPLETE",
    "KVP-027-COMPLETE", "KVP-031-COMPLETE",
)
private const val KVP009_RECEIPT_ID = "KVP-009-COMPLETE"
private const val KVP009_RECEIPT_DIGEST =
    "64efc0e33344ccc55f2436a6dab19e828d52d3f25a9e839ab905600e894da7ea"
private const val KVP010_RECEIPT_ID = "KVP-010-COMPLETE"
private const val KVP010_TASK_ID = "KVP-010"
private const val KVP010_GATE_ID = "KVP-010-COMPLETE-GATE"
private const val KVP010_PROGRAM_FINGERPRINT =
    "31fcef0d003e673781fe38c8aa52e9ad3c4aadec4a888764bbe17645abaf8888"
private const val KVP010_RECEIPT_DIGEST =
    "7d532dba031c394693dfd828c92be3f3b38c6096cc1d39d0864e5d9bac680685"
private val KVP010_DEPENDENCY_DIGESTS = linkedMapOf(
    KVP009_RECEIPT_ID to KVP009_RECEIPT_DIGEST,
    "KVP-010-GREEN-RECEIPT" to
        "45d83ea3b78bd28f05412f4a53e503e9a67845d34607878449456efd4f17fe85",
    "KVP-010-RED-RECEIPT" to
        "034113e88b6e4c1a93a9fd08943d83dbfff1239ad95109f8b55fa527625f2803",
)
private const val KVP011_RECEIPT_ID = "KVP-011-COMPLETE"
private const val KVP023_RECEIPT_ID = "KVP-023-COMPLETE"
private const val KVP023_TASK_ID = "KVP-023"
private const val KVP023_GATE_ID = "KVP-023-COMPLETE-GATE"
private const val KVP023_PROGRAM_FINGERPRINT =
    "f564dea6a123a43320ae96933f370f446eb738b32de16fc53d2c94685ab89d44"
private const val KVP023_RECEIPT_DIGEST =
    "e3d7587ea38783234f7735dd8715d424eddeb399210dd41fbf00faf96ba8292e"
private const val KVP027_RECEIPT_ID = "KVP-027-COMPLETE"
private const val KVP031_RECEIPT_ID = "KVP-031-COMPLETE"
