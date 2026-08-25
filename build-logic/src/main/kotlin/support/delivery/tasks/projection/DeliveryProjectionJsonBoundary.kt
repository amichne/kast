package support.delivery

import kotlinx.serialization.Serializable
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

@Serializable
internal enum class DeliveryProjectionNegativeCase {
    REORDERED_JSON_KEYS,
    NON_REPEATABLE_GENERATION,
    SCHEMA_INVALID_PROGRAM,
    STATUS_FIELD_PRESENT,
}

private val deliveryProjectionEvidenceJson = Json {
    encodeDefaults = true
    prettyPrint = true
}

internal fun encodeProjectionProof(document: DeliveryProjectionProofDocument): String =
    deliveryProjectionEvidenceJson.encodeToString(document) + "\n"

internal fun encodeProjectionNegativeProof(
    document: DeliveryProjectionNegativeProofDocument,
): String = deliveryProjectionEvidenceJson.encodeToString(document) + "\n"
