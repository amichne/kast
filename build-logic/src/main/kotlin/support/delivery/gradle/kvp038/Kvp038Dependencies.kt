package support.delivery

import java.nio.file.Path

internal enum class Kvp038DependencyFailure {
    CLOSURE_MISMATCH,
    READ_REJECTED,
    LEGACY_RECEIPT_REJECTED,
    TASK_RECEIPT_REJECTED,
    OUTPUT_REJECTED,
}

internal class AdmittedKvp038Dependencies internal constructor(
    val digests: Map<String, String>,
    val implementationBaseline: DeliveryGeneration,
)

internal sealed interface Kvp038DependencyAdmission {
    data class Complete(val dependencies: AdmittedKvp038Dependencies) :
        Kvp038DependencyAdmission
    data class Rejected(val failure: Kvp038DependencyFailure) : Kvp038DependencyAdmission
}

internal data class Kvp038DependencyPaths(
    val kvp008Red: Path,
    val kvp008Green: Path,
    val kvp008Complete: Path,
    val kvp008Report: Path,
    val kvp036Complete: Path,
    val kvp036Report: Path,
    val kvp037Complete: Path,
    val kvp037Report: Path,
)

internal sealed interface Kvp038LegacyClosureAdmission {
    data class Complete(val digest: String) : Kvp038LegacyClosureAdmission
    data object Rejected : Kvp038LegacyClosureAdmission
}

private sealed interface Kvp038LegacyDocumentAdmission {
    data class Complete(val document: ProofReceiptDocument) : Kvp038LegacyDocumentAdmission
    data object Rejected : Kvp038LegacyDocumentAdmission
}

private sealed interface Kvp038TaskDocumentAdmission {
    data class Complete(val document: TaskProofReceiptDocument) : Kvp038TaskDocumentAdmission
    data object Rejected : Kvp038TaskDocumentAdmission
}

private sealed interface Kvp038DependencyRead {
    data class Complete(val raw: String) : Kvp038DependencyRead
    data object Rejected : Kvp038DependencyRead
}

private sealed interface Kvp038HeadClosure {
    data object Content : Kvp038HeadClosure
    data class Exact(val head: DeliveryGeneration) : Kvp038HeadClosure
}

/**
 * Proof transition: `(TaskPacket, DeliveryGeneration, Kvp038DependencyPaths) ->
 * Kvp038DependencyAdmission`.
 *
 * Establishes the complete KVP-008 legacy lineage, exact-head KVP-036 output, and content-scoped
 * KVP-037 output. Every read, identity, digest, output, or closure mismatch remains the finite
 * [Kvp038DependencyFailure]; raw receipt/report bytes are extracted only in this boundary.
 */
internal fun admitKvp038Dependencies(
    packet: TaskPacket,
    head: DeliveryGeneration,
    paths: Kvp038DependencyPaths,
): Kvp038DependencyAdmission {
    if (packet.receipt.dependencies.map { it.value }.sorted() != KVP038_DEPENDENCIES) {
        return dependencyRejected(Kvp038DependencyFailure.CLOSURE_MISMATCH)
    }
    val legacy = when (val admitted = admitKvp008(paths)) {
        is Kvp038LegacyClosureAdmission.Complete -> admitted.digest
        Kvp038LegacyClosureAdmission.Rejected -> return dependencyRejected(
            Kvp038DependencyFailure.LEGACY_RECEIPT_REJECTED,
        )
    }
    val kvp036 = admitTaskDependency(
        paths.kvp036Complete, paths.kvp036Report, canonicalKvp036Packet(),
        TaskProofHeadPolicy.EXACT_HEAD, Kvp038HeadClosure.Exact(head),
    ).let { admitted ->
        when (admitted) {
            is Kvp038TaskDocumentAdmission.Complete -> admitted.document
            Kvp038TaskDocumentAdmission.Rejected -> return dependencyRejected(
                Kvp038DependencyFailure.TASK_RECEIPT_REJECTED,
            )
        }
    }
    val kvp037 = admitTaskDependency(
        paths.kvp037Complete, paths.kvp037Report, canonicalKvp037Packet(),
        TaskProofHeadPolicy.CONTENT_SCOPED, Kvp038HeadClosure.Content,
    ).let { admitted ->
        when (admitted) {
            is Kvp038TaskDocumentAdmission.Complete -> admitted.document
            Kvp038TaskDocumentAdmission.Rejected -> return dependencyRejected(
                Kvp038DependencyFailure.TASK_RECEIPT_REJECTED,
            )
        }
    }
    return Kvp038DependencyAdmission.Complete(AdmittedKvp038Dependencies(
        linkedMapOf(
            "KVP-008-COMPLETE" to legacy,
            "KVP-036-COMPLETE" to kvp036.receiptDigest.value,
            "KVP-037-COMPLETE" to kvp037.receiptDigest.value,
        ),
        kvp037.observedRepositoryHead,
    ))
}

private fun admitKvp008(paths: Kvp038DependencyPaths): Kvp038LegacyClosureAdmission {
    val raw = listOf(paths.kvp008Red, paths.kvp008Green, paths.kvp008Complete, paths.kvp008Report)
        .map { path ->
            when (val read = read038(path)) {
                is Kvp038DependencyRead.Complete -> read.raw
                Kvp038DependencyRead.Rejected -> return Kvp038LegacyClosureAdmission.Rejected
            }
        }
    val documents = raw.take(3).map { receipt ->
        when (val admitted = decodeLegacy(receipt)) {
            is Kvp038LegacyDocumentAdmission.Complete -> admitted.document
            Kvp038LegacyDocumentAdmission.Rejected ->
                return Kvp038LegacyClosureAdmission.Rejected
        }
    }
    val red = documents[0]
    val green = documents[1]
    val complete = documents[2]
    val reportRaw = raw[3]
    return admitKvp008LegacyClosure(red, green, complete, reportRaw)
}

/**
 * Proof transition: `(KVP-008 RED receipt, GREEN receipt, completion receipt, report bytes) ->
 * Kvp038LegacyClosureAdmission`.
 *
 * Establishes one internally consistent, canonical, self-digested KVP-008 content closure whose
 * inherited dependencies, exact head, admitted program/requirement identities, receipt-local input
 * identities, gate identities, and report artifact are unchanged. The preserved legacy program
 * fingerprint is the authority for this already-admitted prefix; later task-graph fingerprints are
 * outside this closure. Every mixed or incomplete lineage returns
 * [Kvp038LegacyClosureAdmission.Rejected]. Raw report bytes remain confined to the KVP-038
 * dependency boundary.
 */
internal fun admitKvp008LegacyClosure(
    red: ProofReceiptDocument,
    green: ProofReceiptDocument,
    complete: ProofReceiptDocument,
    reportRaw: String,
): Kvp038LegacyClosureAdmission {
    val documents = listOf(red, green, complete)
    val sameBoundary = documents.all {
        it.taskId.value == "KVP-008" &&
            it.baseRevision == complete.baseRevision &&
            it.exactHead == complete.exactHead &&
            it.programFingerprint == complete.programFingerprint &&
            it.requirementFingerprint == complete.requirementFingerprint
    }
    val reportPath = "build/reports/delivery/KVP-008-derived-state.json"
    val greenArtifacts = green.artifactDigests.mapKeys { it.key.value }.mapValues { it.value.value }
    val inheritedDependencies = red.dependencyReceiptDigests
    if (
        !sameBoundary || red.receiptId.value != "KVP-008-RED-RECEIPT" ||
        red.gateId.value != "KVP-008-RED" ||
        green.receiptId.value != "KVP-008-GREEN-RECEIPT" ||
        green.gateId.value != "KVP-008-GREEN" ||
        complete.receiptId.value != "KVP-008-COMPLETE" ||
        complete.gateId.value != "KVP-008-COMPLETE-GATE" ||
        green.dependencyReceiptDigests != inheritedDependencies +
            (red.receiptId to red.receiptDigest) ||
        complete.dependencyReceiptDigests != inheritedDependencies + mapOf(
            red.receiptId to red.receiptDigest,
            green.receiptId to green.receiptDigest,
        ) ||
        greenArtifacts != mapOf(reportPath to sha256(reportRaw).value)
    ) return Kvp038LegacyClosureAdmission.Rejected
    return Kvp038LegacyClosureAdmission.Complete(complete.receiptDigest.value)
}

/** Legacy receipt JSON -> canonical self-digested document or boundary-local absence. */
private fun decodeLegacy(raw: String): Kvp038LegacyDocumentAdmission {
    val document = when (val decoded = decodeProofReceiptDocument(raw)) {
        is ProofReceiptDocumentResult.Complete -> decoded.document
        is ProofReceiptDocumentResult.Rejected -> return Kvp038LegacyDocumentAdmission.Rejected
    }
    return if (
        document.receiptDigest == document.derivedDigest() &&
            encodeProofReceiptDocument(document) == raw
    ) Kvp038LegacyDocumentAdmission.Complete(document)
    else Kvp038LegacyDocumentAdmission.Rejected
}

/** V2 receipt/report bytes -> exact canonical task receipt or boundary-local absence. */
private fun admitTaskDependency(
    receiptPath: Path,
    reportPath: Path,
    expected: Pair<TaskPacket, TaskProofProgramVersion>,
    headPolicy: TaskProofHeadPolicy,
    closure: Kvp038HeadClosure,
): Kvp038TaskDocumentAdmission {
    val raw = when (val read = read038(receiptPath)) {
        is Kvp038DependencyRead.Complete -> read.raw
        Kvp038DependencyRead.Rejected -> return Kvp038TaskDocumentAdmission.Rejected
    }
    val document = when (val decoded = decodeTaskProofReceipt(raw)) {
        is TaskProofReceiptDocumentRefinement.Complete -> decoded.document
        is TaskProofReceiptDocumentRefinement.Rejected ->
            return Kvp038TaskDocumentAdmission.Rejected
    }
    val report = when (val read = read038(reportPath)) {
        is Kvp038DependencyRead.Complete -> read.raw
        Kvp038DependencyRead.Rejected -> return Kvp038TaskDocumentAdmission.Rejected
    }
    val (packet, version) = expected
    val output = packet.task.outputs.single().path
    val expectedCommand = when (packet.task.id.value) {
        "KVP-036" -> packet.kvp036CommandDigest()
        "KVP-037" -> packet.kvp037CommandDigest()
        else -> return Kvp038TaskDocumentAdmission.Rejected
    }
    val headMatches = when (closure) {
        Kvp038HeadClosure.Content -> true
        is Kvp038HeadClosure.Exact -> document.observedRepositoryHead == closure.head
    }
    return if (
        document.receiptId == packet.receipt.receiptId && document.taskId == packet.task.id &&
            document.programVersion == version &&
            document.taskDefinitionDigest.value == packet.taskDefinitionDigest.value &&
            document.commandDigest == expectedCommand &&
            document.dependencyReceiptDigests.keys == packet.receipt.dependencies &&
            document.headPolicy == headPolicy && headMatches &&
            document.outputDigests.mapKeys { entry -> entry.key.value }
                .mapValues { entry -> entry.value.value } ==
            mapOf(output to sha256(report).value) &&
            document.receiptDigest == document.derivedDigest() &&
            encodeTaskProofReceipt(document) == raw
    ) Kvp038TaskDocumentAdmission.Complete(document)
    else Kvp038TaskDocumentAdmission.Rejected
}

private fun read038(path: Path): Kvp038DependencyRead = when (
    val result = readBoundaryFile(path, MAX_RECEIPT_EVIDENCE_BYTES)
) {
    is BoundaryFileRead.Complete -> Kvp038DependencyRead.Complete(
        result.bytes.toString(Charsets.UTF_8),
    )
    is BoundaryFileRead.Rejected -> Kvp038DependencyRead.Rejected
}

private fun dependencyRejected(failure: Kvp038DependencyFailure) =
    Kvp038DependencyAdmission.Rejected(failure)

private val KVP038_DEPENDENCIES = listOf(
    "KVP-008-COMPLETE", "KVP-036-COMPLETE", "KVP-037-COMPLETE",
)
