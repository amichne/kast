package support.delivery

import java.time.Instant

@JvmInline
internal value class ProofReceiptId internal constructor(val value: String)

@JvmInline
internal value class ProofGateId internal constructor(val value: String)

@JvmInline
internal value class ProofReceiptDigest internal constructor(val value: String)

@JvmInline
internal value class ProofCommandDigest internal constructor(val value: String)

@JvmInline
internal value class DeclaredInputDigest internal constructor(val value: String)

@JvmInline
internal value class ProofArtifactPath internal constructor(val value: String)

@JvmInline
internal value class ProofObservationName internal constructor(val value: String)

@JvmInline
internal value class ProofObservationValue internal constructor(val value: String)

@JvmInline
internal value class ProofRecordedAt internal constructor(val value: String)

internal enum class ProofReceiptFailure {
    MALFORMED_DOCUMENT,
    UNSUPPORTED_SCHEMA,
    MALFORMED_RECEIPT_ID,
    MALFORMED_TASK_ID,
    MALFORMED_GATE_ID,
    MALFORMED_GIT_REVISION,
    MALFORMED_FINGERPRINT,
    MALFORMED_DEPENDENCY_RECEIPTS,
    MALFORMED_DECLARED_INPUT_DIGEST,
    MALFORMED_COMMAND_DIGEST,
    MALFORMED_OBSERVATION,
    MALFORMED_ARTIFACT_DIGESTS,
    MALFORMED_RECORDED_AT,
    MALFORMED_RECEIPT_DIGEST,
    RECEIPT_ID_MISMATCH,
    BASE_REVISION_MISMATCH,
    EXACT_HEAD_MISMATCH,
    PROGRAM_FINGERPRINT_MISMATCH,
    REQUIREMENT_FINGERPRINT_MISMATCH,
    TASK_ID_MISMATCH,
    GATE_ID_MISMATCH,
    DEPENDENCY_RECEIPTS_MISMATCH,
    DECLARED_INPUT_DIGEST_MISMATCH,
    COMMAND_DIGEST_MISMATCH,
    OBSERVATION_MISMATCH,
    ARTIFACT_DIGESTS_MISMATCH,
    RECEIPT_DIGEST_MISMATCH,
}

internal class ProofReceiptExpectation internal constructor(
    val receiptId: ProofReceiptId,
    val baseRevision: AuthorityGitRevision,
    val exactHead: AuthorityGitRevision,
    val programFingerprint: ProgramFingerprint,
    val requirementFingerprint: RequirementFingerprint,
    val taskId: TaskId,
    val gateId: ProofGateId,
    val dependencyReceiptDigests: Map<ProofReceiptId, ProofReceiptDigest>,
    val declaredInputDigest: DeclaredInputDigest,
    val commandDigest: ProofCommandDigest,
    val observedProofValues: Map<ProofObservationName, ProofObservationValue>,
    val artifactDigests: Map<ProofArtifactPath, AuthorityArtifactDigest>,
) {
    internal companion object {
        /**
         * Proof transition: raw receipt contract fields -> `ProofReceiptExpectation`.
         *
         * Establishes canonical receipt, task, gate, Git, SHA-256, observation, artifact-path, and
         * dependency identities. Expected malformed configuration returns
         * [ProofReceiptExpectationResult.Rejected]. Raw values may be extracted only by the Gradle
         * receipt task boundary.
         */
        fun parse(
            receiptId: String,
            baseRevision: String,
            exactHead: String,
            programFingerprint: String,
            requirementFingerprint: String,
            taskId: String,
            gateId: String,
            dependencyReceiptDigests: Map<String, String>,
            declaredInputDigest: String,
            commandDigest: String,
            observedProofValues: Map<String, String>,
            artifactDigests: Map<String, String>,
        ): ProofReceiptExpectationResult = refineProofReceiptExpectation(
            receiptId,
            baseRevision,
            exactHead,
            programFingerprint,
            requirementFingerprint,
            taskId,
            gateId,
            dependencyReceiptDigests,
            declaredInputDigest,
            commandDigest,
            observedProofValues,
            artifactDigests,
        )
    }
}

internal sealed interface ProofReceiptExpectationResult {
    data class Complete(val expectation: ProofReceiptExpectation) : ProofReceiptExpectationResult
    data class Rejected(val failure: ProofReceiptFailure) : ProofReceiptExpectationResult
}

@ConsistentCopyVisibility
internal data class ProofReceiptDocument internal constructor(
    val schemaVersion: Int,
    val receiptId: ProofReceiptId,
    val baseRevision: AuthorityGitRevision,
    val exactHead: AuthorityGitRevision,
    val programFingerprint: ProgramFingerprint,
    val requirementFingerprint: RequirementFingerprint,
    val taskId: TaskId,
    val gateId: ProofGateId,
    val dependencyReceiptDigests: Map<ProofReceiptId, ProofReceiptDigest>,
    val declaredInputDigest: DeclaredInputDigest,
    val commandDigest: ProofCommandDigest,
    val observedProofValues: Map<ProofObservationName, ProofObservationValue>,
    val artifactDigests: Map<ProofArtifactPath, AuthorityArtifactDigest>,
    val recordedAtUtc: ProofRecordedAt,
    val receiptDigest: ProofReceiptDigest,
) {
    internal companion object {
        /**
         * Proof transition: parsed receipt JSON fields -> `ProofReceiptDocument`.
         *
         * Establishes the complete typed receipt shape, including a canonical UTC instant and
         * syntactically valid receipt digest. Expected malformed data returns
         * [ProofReceiptDocumentResult.Rejected]. Raw JSON extraction remains in
         * `DeliveryReceiptJsonBoundary`.
         */
        fun parse(
            schemaVersion: Int,
            receiptId: String,
            baseRevision: String,
            exactHead: String,
            programFingerprint: String,
            requirementFingerprint: String,
            taskId: String,
            gateId: String,
            dependencyReceiptDigests: Map<String, String>,
            declaredInputDigest: String,
            commandDigest: String,
            observedProofValues: Map<String, String>,
            artifactDigests: Map<String, String>,
            recordedAtUtc: String,
            receiptDigest: String,
        ): ProofReceiptDocumentResult = refineProofReceiptDocument(
            schemaVersion,
            receiptId,
            baseRevision,
            exactHead,
            programFingerprint,
            requirementFingerprint,
            taskId,
            gateId,
            dependencyReceiptDigests,
            declaredInputDigest,
            commandDigest,
            observedProofValues,
            artifactDigests,
            recordedAtUtc,
            receiptDigest,
        )
    }
}

internal sealed interface ProofReceiptDocumentResult {
    data class Complete(val document: ProofReceiptDocument) : ProofReceiptDocumentResult
    data class Rejected(val failure: ProofReceiptFailure) : ProofReceiptDocumentResult
}

internal class AdmittedProofReceipt internal constructor(
    val receiptId: ProofReceiptId,
    val digest: ProofReceiptDigest,
    val exactHead: AuthorityGitRevision,
    val taskId: TaskId,
    val gateId: ProofGateId,
)

internal sealed interface ProofReceiptAdmission {
    data class Complete(val receipt: AdmittedProofReceipt) : ProofReceiptAdmission
    data class Rejected(val failure: ProofReceiptFailure) : ProofReceiptAdmission
}

/**
 * Proof transition: `ProofReceiptExpectation` plus an `Instant` -> `ProofReceiptDocument`.
 *
 * Preserves every admitted contract field and derives the receipt digest from the canonical
 * payload. Raw time extraction is permitted only at the Gradle receipt task boundary.
 */
internal fun issueProofReceipt(
    expectation: ProofReceiptExpectation,
    recordedAt: Instant,
): ProofReceiptDocument {
    val documentWithoutDigest = expectation.asDocument(recordedAt.toString(), ZERO_DIGEST)
    return documentWithoutDigest.copy(receiptDigest = documentWithoutDigest.derivedDigest())
}

/**
 * Proof transition: `ProofReceiptDocument` plus `ProofReceiptExpectation` ->
 * `AdmittedProofReceipt`.
 *
 * Establishes exact equality for every contract-bound identity, dependency, command, input,
 * observation, and artifact plus canonical payload integrity. Every expected mismatch returns a
 * closed [ProofReceiptFailure]. No raw receipt field is exposed past the admission boundary.
 */
internal fun admitProofReceipt(
    document: ProofReceiptDocument,
    expectation: ProofReceiptExpectation,
): ProofReceiptAdmission {
    val failure = when {
        document.receiptId != expectation.receiptId -> ProofReceiptFailure.RECEIPT_ID_MISMATCH
        document.baseRevision != expectation.baseRevision -> ProofReceiptFailure.BASE_REVISION_MISMATCH
        document.exactHead != expectation.exactHead -> ProofReceiptFailure.EXACT_HEAD_MISMATCH
        document.programFingerprint != expectation.programFingerprint -> ProofReceiptFailure.PROGRAM_FINGERPRINT_MISMATCH
        document.requirementFingerprint != expectation.requirementFingerprint -> ProofReceiptFailure.REQUIREMENT_FINGERPRINT_MISMATCH
        document.taskId != expectation.taskId -> ProofReceiptFailure.TASK_ID_MISMATCH
        document.gateId != expectation.gateId -> ProofReceiptFailure.GATE_ID_MISMATCH
        document.dependencyReceiptDigests != expectation.dependencyReceiptDigests -> ProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH
        document.declaredInputDigest != expectation.declaredInputDigest -> ProofReceiptFailure.DECLARED_INPUT_DIGEST_MISMATCH
        document.commandDigest != expectation.commandDigest -> ProofReceiptFailure.COMMAND_DIGEST_MISMATCH
        document.observedProofValues != expectation.observedProofValues -> ProofReceiptFailure.OBSERVATION_MISMATCH
        document.artifactDigests != expectation.artifactDigests -> ProofReceiptFailure.ARTIFACT_DIGESTS_MISMATCH
        document.receiptDigest != document.derivedDigest() -> ProofReceiptFailure.RECEIPT_DIGEST_MISMATCH
        else -> null
    }
    return if (failure == null) {
        ProofReceiptAdmission.Complete(
            AdmittedProofReceipt(
                document.receiptId,
                document.receiptDigest,
                document.exactHead,
                document.taskId,
                document.gateId,
            ),
        )
    } else {
        ProofReceiptAdmission.Rejected(failure)
    }
}
