package support.delivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class Kvp005ProjectionProofFailure {
    ADMISSION_MISMATCH,
    MALFORMED_DOCUMENT,
    TASK_ID_MISMATCH,
    OUTCOME_MISMATCH,
    GENERATION_COUNT_MISMATCH,
    BYTE_IDENTITY_MISMATCH,
    SCHEMA_COUNT_MISMATCH,
    ARTIFACT_DIGEST_MISMATCH,
    NEGATIVE_CASE_MISMATCH,
}

@ConsistentCopyVisibility
internal data class Kvp005ProjectionProof internal constructor(
    val projection: AdmittedDeterministicProgramProjection,
)

internal sealed interface Kvp005ProjectionProofResult {
    data class Complete(val proof: Kvp005ProjectionProof) : Kvp005ProjectionProofResult
    data class Rejected(val failure: Kvp005ProjectionProofFailure) : Kvp005ProjectionProofResult
}

@Serializable
internal enum class DeliveryProjectionNegativeCase {
    REORDERED_JSON_KEYS,
    NON_REPEATABLE_GENERATION,
    SCHEMA_INVALID_PROGRAM,
    STATUS_FIELD_PRESENT,
}

@ConsistentCopyVisibility
internal data class Kvp005ProjectionNegativeProof internal constructor(
    val cases: List<DeliveryProjectionNegativeCase>,
    val failures: List<DeliveryProjectionFailure>,
)

internal sealed interface Kvp005ProjectionNegativeProofResult {
    data class Complete(val proof: Kvp005ProjectionNegativeProof) :
        Kvp005ProjectionNegativeProofResult

    data class Rejected(val failure: Kvp005ProjectionProofFailure) :
        Kvp005ProjectionNegativeProofResult
}

/**
 * Proof transition: canonical admitted program -> `Kvp005ProjectionProofResult`.
 *
 * Establishes two independently generated, byte-identical, canonical, schema-valid projection
 * bundles. Expected failure is [Kvp005ProjectionProofFailure.ADMISSION_MISMATCH]. Raw artifacts
 * remain inside the admitted projection until a Gradle boundary writes or digests them.
 */
internal fun deriveKvp005ProjectionProof(): Kvp005ProjectionProofResult {
    val first = DeterministicProgramProjection.generate(KastVfsPassiveReusedIndexProgram.validated)
    val second = DeterministicProgramProjection.generate(KastVfsPassiveReusedIndexProgram.validated)
    return when (val admitted = admitDeterministicProgramProjection(first, second)) {
        is DeliveryProjectionAdmission.Admitted -> Kvp005ProjectionProofResult.Complete(
            Kvp005ProjectionProof(admitted.projection),
        )
        is DeliveryProjectionAdmission.Rejected -> Kvp005ProjectionProofResult.Rejected(
            Kvp005ProjectionProofFailure.ADMISSION_MISMATCH,
        )
    }
}

/**
 * Proof transition: canonical admitted program -> KVP-005 negative fixture proof.
 *
 * Establishes exact finite rejection of reordered bytes, divergent generations, schema-invalid
 * program content, and writable status content. Any unexpected admission or failure variant returns
 * [Kvp005ProjectionProofFailure.NEGATIVE_CASE_MISMATCH]. Raw JSON fixture mutation is confined to
 * this build-proof boundary.
 */
internal fun deriveKvp005ProjectionNegativeProof(): Kvp005ProjectionNegativeProofResult {
    val canonical = DeterministicProgramProjection.generate(KastVfsPassiveReusedIndexProgram.validated)
    val fixtures = listOf(
        DeliveryProjectionNegativeCase.REORDERED_JSON_KEYS to canonical.replacing(
            ProjectionArtifactId.PROGRAM,
            reorderTopLevelKeys(canonical.program),
        ),
        DeliveryProjectionNegativeCase.NON_REPEATABLE_GENERATION to canonical.replacing(
            ProjectionArtifactId.PROGRAM,
            canonical.program + " ",
        ),
        DeliveryProjectionNegativeCase.SCHEMA_INVALID_PROGRAM to canonical.replacing(
            ProjectionArtifactId.PROGRAM,
            replaceProgramField(canonical.program, "targetHead", JsonPrimitive("invalid")),
        ),
        DeliveryProjectionNegativeCase.STATUS_FIELD_PRESENT to canonical.replacing(
            ProjectionArtifactId.PROGRAM,
            replaceProgramField(canonical.program, "status", JsonPrimitive("COMPLETE")),
        ),
    )
    val expected = listOf(
        DeliveryProjectionFailure.NON_CANONICAL_JSON,
        DeliveryProjectionFailure.NON_REPEATABLE_GENERATION,
        DeliveryProjectionFailure.SCHEMA_VALIDATION_FAILED,
        DeliveryProjectionFailure.STATUS_FIELD_PRESENT,
    )
    val observed = fixtures.mapIndexed { index, (case, fixture) ->
        val comparison = if (case == DeliveryProjectionNegativeCase.NON_REPEATABLE_GENERATION) {
            canonical
        } else {
            fixture
        }
        val failure = when (val result = admitDeterministicProgramProjection(fixture, comparison)) {
            is DeliveryProjectionAdmission.Rejected -> result.failure
            is DeliveryProjectionAdmission.Admitted -> return negativeProjectionProofMismatch()
        }
        if (failure != expected[index]) return negativeProjectionProofMismatch()
        failure
    }
    return Kvp005ProjectionNegativeProofResult.Complete(
        Kvp005ProjectionNegativeProof(fixtures.map { it.first }, observed),
    )
}

private val projectionFixtureJson = Json { ignoreUnknownKeys = false }

private fun reorderTopLevelKeys(document: String): String {
    val source = projectionFixtureJson.parseToJsonElement(document) as JsonObject
    val entries = source.entries.toList()
    return JsonObject(linkedMapOf(entries.last().toPair(), *entries.dropLast(1).map {
        it.toPair()
    }.toTypedArray())).toString() + "\n"
}

private fun replaceProgramField(document: String, key: String, value: JsonPrimitive): String {
    val source = projectionFixtureJson.parseToJsonElement(document) as JsonObject
    return canonicalJson(JsonObject(source + (key to value))) + "\n"
}

private fun negativeProjectionProofMismatch() = Kvp005ProjectionNegativeProofResult.Rejected(
    Kvp005ProjectionProofFailure.NEGATIVE_CASE_MISMATCH,
)
