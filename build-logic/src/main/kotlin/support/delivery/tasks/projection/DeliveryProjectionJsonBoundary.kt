package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class DeliveryProjectionProofDocument(
    val artifactDigests: Map<String, String>,
    val byteIdentical: Boolean,
    val generationCount: Int,
    val outcome: DeliveryProjectionOutcome,
    val schemaValidArtifactCount: Int,
    val schemaVersion: Int,
    val taskId: String,
)

@Serializable
internal enum class DeliveryProjectionOutcome { COMPLETE }

@Serializable
internal data class DeliveryProjectionNegativeProofDocument(
    val observedFailures: List<DeliveryProjectionFailure>,
    val rejectedCases: List<DeliveryProjectionNegativeCase>,
    val schemaVersion: Int,
    val taskId: String,
)

private val deliveryProjectionEvidenceJson = Json {
    encodeDefaults = true
    prettyPrint = true
}

internal fun encodeKvp005ProjectionProof(proof: Kvp005ProjectionProof): String =
    deliveryProjectionEvidenceJson.encodeToString(proof.document()) + "\n"

internal fun encodeKvp005ProjectionNegativeProof(
    proof: Kvp005ProjectionNegativeProof,
): String = deliveryProjectionEvidenceJson.encodeToString(
    DeliveryProjectionNegativeProofDocument(
        observedFailures = proof.failures,
        rejectedCases = proof.cases,
        schemaVersion = 1,
        taskId = "KVP-005",
    ),
) + "\n"

/**
 * Proof transition: KVP-005 report JSON -> `Kvp005ProjectionProofResult`.
 *
 * Establishes exact schema, task, outcome, generation, byte identity, schema count, and artifact
 * digests. Expected malformed or mismatched evidence is finite [Kvp005ProjectionProofFailure]. Raw
 * JSON is extracted only at this generated-serializer report boundary.
 */
internal fun decodeKvp005ProjectionProof(raw: String): Kvp005ProjectionProofResult {
    val document = try {
        deliveryProjectionEvidenceJson.decodeFromString(
            DeliveryProjectionProofDocument.serializer(),
            raw,
        )
    } catch (_: SerializationException) {
        return rejectedProjectionReport(Kvp005ProjectionProofFailure.MALFORMED_DOCUMENT)
    }
    val expected = when (val result = deriveKvp005ProjectionProof()) {
        is Kvp005ProjectionProofResult.Complete -> result.proof
        is Kvp005ProjectionProofResult.Rejected -> return result
    }
    val expectedDocument = expected.document()
    val failure = when {
        document.schemaVersion != expectedDocument.schemaVersion ->
            Kvp005ProjectionProofFailure.MALFORMED_DOCUMENT
        document.taskId != expectedDocument.taskId -> Kvp005ProjectionProofFailure.TASK_ID_MISMATCH
        document.outcome != expectedDocument.outcome -> Kvp005ProjectionProofFailure.OUTCOME_MISMATCH
        document.generationCount != expectedDocument.generationCount ->
            Kvp005ProjectionProofFailure.GENERATION_COUNT_MISMATCH
        document.byteIdentical != expectedDocument.byteIdentical ->
            Kvp005ProjectionProofFailure.BYTE_IDENTITY_MISMATCH
        document.schemaValidArtifactCount != expectedDocument.schemaValidArtifactCount ->
            Kvp005ProjectionProofFailure.SCHEMA_COUNT_MISMATCH
        document.artifactDigests != expectedDocument.artifactDigests ->
            Kvp005ProjectionProofFailure.ARTIFACT_DIGEST_MISMATCH
        else -> return Kvp005ProjectionProofResult.Complete(expected)
    }
    return rejectedProjectionReport(failure)
}

private fun Kvp005ProjectionProof.document() = DeliveryProjectionProofDocument(
    artifactDigests = projection.artifactDigests.entries.associate {
        it.key.repositoryPath to it.value.value
    },
    byteIdentical = true,
    generationCount = 2,
    outcome = DeliveryProjectionOutcome.COMPLETE,
    schemaValidArtifactCount = ProjectionArtifactId.entries.size,
    schemaVersion = 1,
    taskId = "KVP-005",
)

private fun rejectedProjectionReport(failure: Kvp005ProjectionProofFailure) =
    Kvp005ProjectionProofResult.Rejected(failure)
