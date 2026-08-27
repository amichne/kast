package support.delivery

import java.time.Instant
import java.time.format.DateTimeParseException

private data class CommonReceiptFields(
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
)

private sealed interface CommonReceiptRefinement {
    data class Complete(val fields: CommonReceiptFields) : CommonReceiptRefinement
    data class Rejected(val failure: ProofReceiptFailure) : CommonReceiptRefinement
}

/**
 * Proof transition: raw common receipt fields -> `CommonReceiptFields`.
 *
 * Establishes typed identities, digests, portable artifact paths, and a nonempty observation set.
 * Expected malformed data returns [CommonReceiptRefinement.Rejected]. Raw fields remain at the
 * receipt JSON or Gradle configuration boundary.
 */
private fun refineCommonReceiptFields(
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
): CommonReceiptRefinement {
    if (!receiptId.matches(RECEIPT_ID_PATTERN)) return rejected(ProofReceiptFailure.MALFORMED_RECEIPT_ID)
    if (!taskId.matches(TASK_ID_PATTERN)) return rejected(ProofReceiptFailure.MALFORMED_TASK_ID)
    if (!gateId.matches(GATE_ID_PATTERN)) return rejected(ProofReceiptFailure.MALFORMED_GATE_ID)
    if (!baseRevision.isGitRevisionValue() || !exactHead.isGitRevisionValue()) {
        return rejected(ProofReceiptFailure.MALFORMED_GIT_REVISION)
    }
    if (!programFingerprint.isSha256Value() || !requirementFingerprint.isSha256Value()) {
        return rejected(ProofReceiptFailure.MALFORMED_FINGERPRINT)
    }
    if (dependencyReceiptDigests.any { (id, digest) ->
            !id.matches(RECEIPT_ID_PATTERN) || !digest.isSha256Value()
        }
    ) return rejected(ProofReceiptFailure.MALFORMED_DEPENDENCY_RECEIPTS)
    if (!declaredInputDigest.isSha256Value()) {
        return rejected(ProofReceiptFailure.MALFORMED_DECLARED_INPUT_DIGEST)
    }
    if (!commandDigest.isSha256Value()) return rejected(ProofReceiptFailure.MALFORMED_COMMAND_DIGEST)
    if (observedProofValues.isEmpty() || observedProofValues.any { (name, value) ->
            !name.matches(OBSERVATION_NAME_PATTERN) || value.isBlank()
        }
    ) return rejected(ProofReceiptFailure.MALFORMED_OBSERVATION)
    if (artifactDigests.any { (path, digest) ->
            !path.isPortableReceiptPath() || !digest.isSha256Value()
        }
    ) return rejected(ProofReceiptFailure.MALFORMED_ARTIFACT_DIGESTS)
    return CommonReceiptRefinement.Complete(
        CommonReceiptFields(
            ProofReceiptId(receiptId),
            AuthorityGitRevision(baseRevision),
            AuthorityGitRevision(exactHead),
            ProgramFingerprint(programFingerprint),
            RequirementFingerprint(requirementFingerprint),
            TaskId(taskId),
            ProofGateId(gateId),
            dependencyReceiptDigests.mapKeys { ProofReceiptId(it.key) }
                .mapValues { ProofReceiptDigest(it.value) },
            DeclaredInputDigest(declaredInputDigest),
            ProofCommandDigest(commandDigest),
            observedProofValues.mapKeys { ProofObservationName(it.key) }
                .mapValues { ProofObservationValue(it.value) },
            artifactDigests.mapKeys { ProofArtifactPath(it.key) }
                .mapValues { AuthorityArtifactDigest(it.value) },
        ),
    )
}

private fun rejected(failure: ProofReceiptFailure) = CommonReceiptRefinement.Rejected(failure)

/**
 * Proof transition: raw expectation fields -> `ProofReceiptExpectation`.
 *
 * Preserves only fully refined common fields. Expected malformed data returns
 * [ProofReceiptExpectationResult.Rejected]; raw extraction remains at the caller's Gradle boundary.
 */
internal fun refineProofReceiptExpectation(
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
): ProofReceiptExpectationResult = when (
    val common = refineCommonReceiptFields(
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
) {
    is CommonReceiptRefinement.Complete -> {
        ProofReceiptExpectationResult.Complete(common.fields.asExpectation())
    }
    is CommonReceiptRefinement.Rejected -> ProofReceiptExpectationResult.Rejected(common.failure)
}

/**
 * Proof transition: parsed receipt JSON fields -> `ProofReceiptDocument`.
 *
 * Adds the supported schema, canonical timestamp, and receipt-digest invariants to the common field
 * proof. Expected malformed data returns [ProofReceiptDocumentResult.Rejected]; raw extraction
 * remains at the generated JSON boundary.
 */
internal fun refineProofReceiptDocument(
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
): ProofReceiptDocumentResult {
    if (schemaVersion != RECEIPT_SCHEMA_VERSION) {
        return ProofReceiptDocumentResult.Rejected(ProofReceiptFailure.UNSUPPORTED_SCHEMA)
    }
    val common = refineCommonReceiptFields(
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
    val fields = when (common) {
        is CommonReceiptRefinement.Complete -> common.fields
        is CommonReceiptRefinement.Rejected -> {
            return ProofReceiptDocumentResult.Rejected(common.failure)
        }
    }
    if (!recordedAtUtc.isCanonicalInstant()) {
        return ProofReceiptDocumentResult.Rejected(ProofReceiptFailure.MALFORMED_RECORDED_AT)
    }
    if (!receiptDigest.isSha256Value()) {
        return ProofReceiptDocumentResult.Rejected(ProofReceiptFailure.MALFORMED_RECEIPT_DIGEST)
    }
    return ProofReceiptDocumentResult.Complete(
        fields.asDocument(recordedAtUtc, receiptDigest),
    )
}

private fun CommonReceiptFields.asExpectation() = ProofReceiptExpectation(
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

private fun CommonReceiptFields.asDocument(
    recordedAtUtc: String,
    receiptDigest: String,
) = asExpectation().asDocument(recordedAtUtc, receiptDigest)

internal fun ProofReceiptExpectation.asDocument(
    recordedAtUtc: String,
    receiptDigest: String,
) = ProofReceiptDocument(
    RECEIPT_SCHEMA_VERSION,
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
    ProofRecordedAt(recordedAtUtc),
    ProofReceiptDigest(receiptDigest),
)

internal fun ProofReceiptDocument.derivedDigest(): ProofReceiptDigest = ProofReceiptDigest(
    sha256(
        canonicalJson(
            linkedMapOf(
                "schemaVersion" to schemaVersion,
                "receiptId" to receiptId.value,
                "baseRevision" to baseRevision.value,
                "exactHead" to exactHead.value,
                "programFingerprint" to programFingerprint.value,
                "requirementFingerprint" to requirementFingerprint.value,
                "taskId" to taskId.value,
                "gateId" to gateId.value,
                "dependencyReceiptDigests" to dependencyReceiptDigests.mapKeys { it.key.value }
                    .mapValues { it.value.value },
                "declaredInputDigest" to declaredInputDigest.value,
                "commandDigest" to commandDigest.value,
                "observedProofValues" to observedProofValues.mapKeys { it.key.value }
                    .mapValues { it.value.value },
                "artifactDigests" to artifactDigests.mapKeys { it.key.value }
                    .mapValues { it.value.value },
                "recordedAtUtc" to recordedAtUtc.value,
            ),
        ),
    ).value,
)

/**
 * Proof transition: raw timestamp text -> canonical `Instant` syntax evidence.
 *
 * Returns true only for text whose parsed `Instant` renders byte-for-byte identically. The caller
 * refines successful text to [ProofRecordedAt] at the receipt JSON boundary.
 */
private fun String.isCanonicalInstant(): Boolean = try {
    Instant.parse(this).toString() == this
} catch (_: DateTimeParseException) {
    false
}

private fun String.isPortableReceiptPath(): Boolean =
    isNotBlank() && !startsWith('/') && '\\' !in this &&
        split('/').none { it.isEmpty() || it == "." || it == ".." }

private fun String.isGitRevisionValue() = matches(Regex("[0-9a-f]{40}"))
private fun String.isSha256Value() = matches(Regex("[0-9a-f]{64}"))

private val RECEIPT_ID_PATTERN = Regex("KVP-[0-9]{3}-(?:RED-RECEIPT|GREEN-RECEIPT|COMPLETE)")
private val TASK_ID_PATTERN = Regex("KVP-[0-9]{3}")
private val GATE_ID_PATTERN = Regex("KVP-[0-9]{3}-(?:RED|GREEN|COMPLETE-GATE)")
private val OBSERVATION_NAME_PATTERN = Regex("[a-z][A-Za-z0-9]*")
private const val RECEIPT_SCHEMA_VERSION = 1
internal const val ZERO_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
