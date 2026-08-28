package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal enum class Kvp007DeliveryProofOutcome { COMPLETE }

internal enum class Kvp007DeliveryProofReportFailure {
    MALFORMED_DOCUMENT,
    TASK_ID_MISMATCH,
    OUTCOME_MISMATCH,
    INVALIDATION_COUNT_MISMATCH,
    INVALIDATIONS_MISMATCH,
    DERIVATION_REJECTED,
}

internal sealed interface Kvp007DeliveryProofReportResult {
    data class Complete(val proof: DeliveryProof) : Kvp007DeliveryProofReportResult
    data class Rejected(val failure: Kvp007DeliveryProofReportFailure) :
        Kvp007DeliveryProofReportResult
}

@Serializable
private data class Kvp007DeliveryProofDocument(
    val invalidationCount: Int,
    val invalidations: Map<String, String>,
    val outcome: Kvp007DeliveryProofOutcome,
    val schemaVersion: Int,
    val taskId: String,
)

private val kvp007DeliveryProofJson = Json { ignoreUnknownKeys = false; prettyPrint = true }

/**
 * Proof transition: `DeliveryProof -> String`.
 * Preserves every finite invalidation and its exact failure in generated JSON. No expected failure
 * exists after derivation; raw JSON is emitted only at the Gradle report boundary.
 */
internal fun encodeKvp007DeliveryProof(proof: DeliveryProof): String =
    kvp007DeliveryProofJson.encodeToString(
        Kvp007DeliveryProofDocument.serializer(),
        Kvp007DeliveryProofDocument(
            proof.invalidations.size,
            proof.invalidations.entries.associate { it.key.name to it.value.name }.toSortedMap(),
            Kvp007DeliveryProofOutcome.COMPLETE,
            1,
            "KVP-007",
        ),
    ) + "\n"

/**
 * Proof transition: report JSON `String -> Kvp007DeliveryProofReportResult`.
 * Establishes exact schema, task identity, outcome, and the complete independently derived
 * invalidation map. Expected malformed or mismatched evidence is finite
 * [Kvp007DeliveryProofReportFailure]; raw JSON stays at this boundary.
 */
internal fun decodeKvp007DeliveryProof(raw: String): Kvp007DeliveryProofReportResult {
    val document = try {
        kvp007DeliveryProofJson.decodeFromString(Kvp007DeliveryProofDocument.serializer(), raw)
    } catch (_: SerializationException) {
        return rejectedKvp007Report(Kvp007DeliveryProofReportFailure.MALFORMED_DOCUMENT)
    }
    val proof = when (val result = deriveDeliveryProof()) {
        is DeliveryProofResult.Complete -> result.proof
        is DeliveryProofResult.Rejected -> {
            return rejectedKvp007Report(Kvp007DeliveryProofReportFailure.DERIVATION_REJECTED)
        }
    }
    val expected = proof.invalidations.entries.associate {
        it.key.name to it.value.name
    }.toSortedMap()
    val failure = when {
        document.schemaVersion != 1 -> Kvp007DeliveryProofReportFailure.MALFORMED_DOCUMENT
        document.taskId != "KVP-007" -> Kvp007DeliveryProofReportFailure.TASK_ID_MISMATCH
        document.outcome != Kvp007DeliveryProofOutcome.COMPLETE ->
            Kvp007DeliveryProofReportFailure.OUTCOME_MISMATCH
        document.invalidationCount != expected.size ->
            Kvp007DeliveryProofReportFailure.INVALIDATION_COUNT_MISMATCH
        document.invalidations != expected ->
            Kvp007DeliveryProofReportFailure.INVALIDATIONS_MISMATCH
        else -> return Kvp007DeliveryProofReportResult.Complete(proof)
    }
    return rejectedKvp007Report(failure)
}

private fun rejectedKvp007Report(failure: Kvp007DeliveryProofReportFailure) =
    Kvp007DeliveryProofReportResult.Rejected(failure)
