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
    fun rejected(failure: ProofReceiptFailure) = ProofReceiptAdmission.Rejected(failure)
    if (document.receiptId != expectation.receiptId) {
        return rejected(ProofReceiptFailure.RECEIPT_ID_MISMATCH)
    }
    if (document.baseRevision != expectation.baseRevision) {
        return rejected(ProofReceiptFailure.BASE_REVISION_MISMATCH)
    }
    if (document.exactHead != expectation.exactHead) {
        return rejected(ProofReceiptFailure.EXACT_HEAD_MISMATCH)
    }
    if (document.programFingerprint != expectation.programFingerprint) {
        return rejected(ProofReceiptFailure.PROGRAM_FINGERPRINT_MISMATCH)
    }
    if (document.requirementFingerprint != expectation.requirementFingerprint) {
        return rejected(ProofReceiptFailure.REQUIREMENT_FINGERPRINT_MISMATCH)
    }
    if (document.taskId != expectation.taskId) return rejected(ProofReceiptFailure.TASK_ID_MISMATCH)
    if (document.gateId != expectation.gateId) return rejected(ProofReceiptFailure.GATE_ID_MISMATCH)
    if (document.dependencyReceiptDigests != expectation.dependencyReceiptDigests) {
        return rejected(ProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH)
    }
    if (document.declaredInputDigest != expectation.declaredInputDigest) {
        return rejected(ProofReceiptFailure.DECLARED_INPUT_DIGEST_MISMATCH)
    }
    if (document.commandDigest != expectation.commandDigest) {
        return rejected(ProofReceiptFailure.COMMAND_DIGEST_MISMATCH)
    }
    if (document.observedProofValues != expectation.observedProofValues) {
        return rejected(ProofReceiptFailure.OBSERVATION_MISMATCH)
    }
    if (document.artifactDigests != expectation.artifactDigests) {
        return rejected(ProofReceiptFailure.ARTIFACT_DIGESTS_MISMATCH)
    }
    if (document.receiptDigest != document.derivedDigest()) {
        return rejected(ProofReceiptFailure.RECEIPT_DIGEST_MISMATCH)
    }
    return ProofReceiptAdmission.Complete(
        AdmittedProofReceipt(
            document.receiptId,
            document.receiptDigest,
            document.exactHead,
            document.taskId,
            document.gateId,
        ),
    )
}

internal enum class DeliveryProofInvalidation(val expectedFailure: ProofReceiptFailure) {
    RECEIPT_ID(ProofReceiptFailure.RECEIPT_ID_MISMATCH),
    BASE_REVISION(ProofReceiptFailure.BASE_REVISION_MISMATCH),
    EXACT_HEAD(ProofReceiptFailure.EXACT_HEAD_MISMATCH),
    PROGRAM_FINGERPRINT(ProofReceiptFailure.PROGRAM_FINGERPRINT_MISMATCH),
    REQUIREMENT_FINGERPRINT(ProofReceiptFailure.REQUIREMENT_FINGERPRINT_MISMATCH),
    TASK_ID(ProofReceiptFailure.TASK_ID_MISMATCH),
    GATE_ID(ProofReceiptFailure.GATE_ID_MISMATCH),
    DEPENDENCY_RECEIPT(ProofReceiptFailure.DEPENDENCY_RECEIPTS_MISMATCH),
    DECLARED_INPUT(ProofReceiptFailure.DECLARED_INPUT_DIGEST_MISMATCH),
    COMMAND(ProofReceiptFailure.COMMAND_DIGEST_MISMATCH),
    OBSERVATION(ProofReceiptFailure.OBSERVATION_MISMATCH),
    ARTIFACT(ProofReceiptFailure.ARTIFACT_DIGESTS_MISMATCH),
    FORGED_DIGEST(ProofReceiptFailure.RECEIPT_DIGEST_MISMATCH),
}

internal enum class DeliveryProofDerivationFailure {
    INVALID_FIXTURE,
    INVALID_RECEIPT_ADMITTED,
    INVALIDATION_MISMATCH,
}

@ConsistentCopyVisibility
internal data class DeliveryProof internal constructor(
    val invalidations: Map<DeliveryProofInvalidation, ProofReceiptFailure>,
)

internal sealed interface DeliveryProofResult {
    data class Complete(val proof: DeliveryProof) : DeliveryProofResult
    data class Rejected(val failure: DeliveryProofDerivationFailure) : DeliveryProofResult
}

/**
 * Proof transition: canonical receipt fixture -> `DeliveryProofResult`.
 *
 * Establishes exact finite rejection for every bound identity, revision, fingerprint, dependency,
 * input, command, observation, artifact, and payload digest. Each semantic mutation receives a
 * recomputed digest, so successful proof cannot rely on corruption detection alone. Expected proof
 * failure is finite [DeliveryProofDerivationFailure]; raw fixture values remain inside this proof.
 */
internal fun deriveDeliveryProof(): DeliveryProofResult {
    val zeroDigest = "0".repeat(64)
    val expectation = when (val result = ProofReceiptExpectation.parse(
        "KVP-007-RED-RECEIPT",
        "1".repeat(40),
        "2".repeat(40),
        "3".repeat(64),
        "4".repeat(64),
        "KVP-007",
        "KVP-007-RED",
        mapOf("KVP-006-COMPLETE" to "5".repeat(64)),
        "6".repeat(64),
        "7".repeat(64),
        mapOf("outcome" to "COMPLETE"),
        mapOf("build/reports/delivery/KVP-007-receipts.json" to "8".repeat(64)),
    )) {
        is ProofReceiptExpectationResult.Complete -> result.expectation
        is ProofReceiptExpectationResult.Rejected -> {
            return DeliveryProofResult.Rejected(DeliveryProofDerivationFailure.INVALID_FIXTURE)
        }
    }
    val document = issueProofReceipt(expectation, Instant.parse("2026-08-25T00:00:00Z"))
    val cases = linkedMapOf(
        DeliveryProofInvalidation.RECEIPT_ID to
            document.copy(receiptId = ProofReceiptId("KVP-008-RED-RECEIPT")),
        DeliveryProofInvalidation.BASE_REVISION to
            document.copy(baseRevision = AuthorityGitRevision("9".repeat(40))),
        DeliveryProofInvalidation.EXACT_HEAD to
            document.copy(exactHead = AuthorityGitRevision("9".repeat(40))),
        DeliveryProofInvalidation.PROGRAM_FINGERPRINT to
            document.copy(programFingerprint = ProgramFingerprint("9".repeat(64))),
        DeliveryProofInvalidation.REQUIREMENT_FINGERPRINT to
            document.copy(requirementFingerprint = RequirementFingerprint("9".repeat(64))),
        DeliveryProofInvalidation.TASK_ID to document.copy(taskId = TaskId("KVP-008")),
        DeliveryProofInvalidation.GATE_ID to
            document.copy(gateId = ProofGateId("KVP-008-RED")),
        DeliveryProofInvalidation.DEPENDENCY_RECEIPT to document.copy(
            dependencyReceiptDigests = mapOf(
                ProofReceiptId("KVP-006-COMPLETE") to ProofReceiptDigest("9".repeat(64)),
            ),
        ),
        DeliveryProofInvalidation.DECLARED_INPUT to
            document.copy(declaredInputDigest = DeclaredInputDigest("9".repeat(64))),
        DeliveryProofInvalidation.COMMAND to
            document.copy(commandDigest = ProofCommandDigest("9".repeat(64))),
        DeliveryProofInvalidation.OBSERVATION to document.copy(
            observedProofValues = mapOf(
                ProofObservationName("outcome") to ProofObservationValue("QUALIFIED"),
            ),
        ),
        DeliveryProofInvalidation.ARTIFACT to document.copy(
            artifactDigests = mapOf(
                ProofArtifactPath("build/reports/delivery/KVP-007-receipts.json") to
                    AuthorityArtifactDigest("9".repeat(64)),
            ),
        ),
        DeliveryProofInvalidation.FORGED_DIGEST to
            document.copy(receiptDigest = ProofReceiptDigest(zeroDigest)),
    )
    val observed = mutableMapOf<DeliveryProofInvalidation, ProofReceiptFailure>()
    for ((invalidation, changed) in cases) {
        val candidate = if (invalidation == DeliveryProofInvalidation.FORGED_DIGEST) {
            changed
        } else {
            changed.copy(receiptDigest = changed.derivedDigest())
        }
        val failure = when (val result = admitProofReceipt(candidate, expectation)) {
            is ProofReceiptAdmission.Rejected -> result.failure
            is ProofReceiptAdmission.Complete -> return DeliveryProofResult.Rejected(
                DeliveryProofDerivationFailure.INVALID_RECEIPT_ADMITTED,
            )
        }
        if (failure != invalidation.expectedFailure) {
            return DeliveryProofResult.Rejected(
                DeliveryProofDerivationFailure.INVALIDATION_MISMATCH,
            )
        }
        observed[invalidation] = failure
    }
    return DeliveryProofResult.Complete(DeliveryProof(observed))
}
