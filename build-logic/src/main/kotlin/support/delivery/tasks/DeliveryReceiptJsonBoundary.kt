package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class ProofReceiptJsonDocument(
    val schemaVersion: Int,
    val receiptId: String,
    val baseRevision: String,
    val exactHead: String,
    val programFingerprint: String,
    val requirementFingerprint: String,
    val taskId: String,
    val gateId: String,
    val dependencyReceiptDigests: Map<String, String>,
    val declaredInputDigest: String,
    val commandDigest: String,
    val observedProofValues: Map<String, String>,
    val artifactDigests: Map<String, String>,
    val recordedAtUtc: String,
    val receiptDigest: String,
)

private val proofReceiptJson = Json {
    ignoreUnknownKeys = false
    prettyPrint = true
}

/**
 * Proof transition: receipt JSON bytes -> parsed `ProofReceiptDocument`.
 *
 * The generated serializer establishes the closed JSON schema; domain refinement establishes every
 * typed identity and invariant. Expected malformed input returns
 * [ProofReceiptDocumentResult.Rejected]. Raw JSON exists only at this build-policy boundary.
 */
internal fun decodeProofReceiptDocument(rawDocument: String): ProofReceiptDocumentResult {
    val raw = try {
        proofReceiptJson.decodeFromString(ProofReceiptJsonDocument.serializer(), rawDocument)
    } catch (_: SerializationException) {
        return ProofReceiptDocumentResult.Rejected(ProofReceiptFailure.MALFORMED_DOCUMENT)
    }
    return ProofReceiptDocument.parse(
        raw.schemaVersion,
        raw.receiptId,
        raw.baseRevision,
        raw.exactHead,
        raw.programFingerprint,
        raw.requirementFingerprint,
        raw.taskId,
        raw.gateId,
        raw.dependencyReceiptDigests,
        raw.declaredInputDigest,
        raw.commandDigest,
        raw.observedProofValues,
        raw.artifactDigests,
        raw.recordedAtUtc,
        raw.receiptDigest,
    )
}

/**
 * Proof transition: receipt JSON bytes plus `ProofReceiptExpectation` ->
 * `AdmittedProofReceipt`.
 *
 * Preserves generated-schema parsing and all domain admission invariants. Malformed or mismatched
 * bytes return a closed [ProofReceiptFailure]. Raw JSON exists only at this build-policy boundary.
 */
internal fun admitProofReceipt(
    rawDocument: String,
    expectation: ProofReceiptExpectation,
): ProofReceiptAdmission = when (val decoded = decodeProofReceiptDocument(rawDocument)) {
    is ProofReceiptDocumentResult.Complete -> admitProofReceipt(decoded.document, expectation)
    is ProofReceiptDocumentResult.Rejected -> ProofReceiptAdmission.Rejected(decoded.failure)
}

internal fun encodeProofReceiptDocument(document: ProofReceiptDocument): String {
    val raw = ProofReceiptJsonDocument(
        document.schemaVersion,
        document.receiptId.value,
        document.baseRevision.value,
        document.exactHead.value,
        document.programFingerprint.value,
        document.requirementFingerprint.value,
        document.taskId.value,
        document.gateId.value,
        document.dependencyReceiptDigests.entries.associate {
            it.key.value to it.value.value
        }.toSortedMap(),
        document.declaredInputDigest.value,
        document.commandDigest.value,
        document.observedProofValues.entries.associate {
            it.key.value to it.value.value
        }.toSortedMap(),
        document.artifactDigests.entries.associate {
            it.key.value to it.value.value
        }.toSortedMap(),
        document.recordedAtUtc.value,
        document.receiptDigest.value,
    )
    return proofReceiptJson.encodeToString(ProofReceiptJsonDocument.serializer(), raw) + "\n"
}
