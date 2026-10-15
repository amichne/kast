package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceAuthorityMismatchTest {
    private val expected = parsed(ReferenceAuthorityEpoch.parse(3))
    private val observed = parsed(ReferenceAuthorityEpoch.parse(4))
    private val mismatch = parsed(ReferenceAuthorityMismatch.EpochMismatch.compare(expected, observed))
    private val position = parsed(ProtocolOffset.parse(0))

    @Test
    fun `epoch evidence encodes the request and admitted current values`() {
        val encoded = Json.encodeToJsonElement<ReferenceAuthorityMismatch>(mismatch).jsonObject
        assertEquals(setOf("type", "expected_epoch", "observed_epoch"), encoded.keys)
        assertEquals("EPOCH_MISMATCH", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("3", encoded.getValue("expected_epoch").jsonPrimitive.content)
        assertEquals("4", encoded.getValue("observed_epoch").jsonPrimitive.content)
    }

    @Test
    fun `matching or nonpositive epochs cannot establish a mismatch`() {
        assertEquals(
            Refinement.Rejected(ReferenceEpochComparisonFailure.EQUAL),
            ReferenceAuthorityMismatch.EpochMismatch.compare(expected, expected),
        )
        for (invalid in listOf(0L, -1L)) assertTrue(ReferenceAuthorityEpoch.parse(invalid) is Refinement.Rejected)
        for ((before, after) in listOf(3L to 3L, 0L to 4L, 3L to -1L)) {
            val input = Json.encodeToString(RawEpochMismatch(before, after))
            assertThrows(SerializationException::class.java) {
                Json.decodeFromString<ReferenceAuthorityMismatch>(input)
            }
        }
    }

    @Test
    fun `authority evidence cannot attach to an unrelated query failure`() {
        assertEquals(
            Refinement.Rejected(QueryReferenceEvidenceFailure.MISMATCH_REQUIRES_STALE_AUTHORITY),
            QueryRunRejection.ReferenceRejected.admit(position, QueryReferenceRejectionReason.MALFORMED, mismatch),
        )
        val input =
            Json.encodeToString(RawReferenceFailure(position, QueryReferenceRejectionReason.MALFORMED, mismatch))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(QueryRunRejection.ReferenceRejected.serializer(), input)
        }
        val step =
            Json.encodeToString(RawStepFailure(position, position, QueryReferenceRejectionReason.MALFORMED, mismatch))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(QueryRunRejection.StepReferenceRejected.serializer(), step)
        }
        val impact =
            Json.encodeToString(RawReferenceFailure(position, QueryReferenceRejectionReason.MALFORMED, mismatch))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(QueryImpactSourceFailureDocument.Reference.serializer(), impact)
        }
    }

    @Test
    fun `authority evidence cannot attach to an unrelated source failure`() {
        val input =
            Json.encodeToString(
                RawSourceFailure(
                    "reference-rejected",
                    SourceReferenceRole.CANDIDATE,
                    SourceReferenceFailure.MALFORMED,
                    mismatch,
                )
            )
        assertThrows(SerializationException::class.java) { Json.decodeFromString<SourceReadCause>(input) }
    }

    @Test
    fun `source step and impact failures keep the same admitted mismatch`() {
        val reference = QueryRunRejection.ReferenceRejected.staleAuthority(position, mismatch)
        val step = QueryRunRejection.StepReferenceRejected.fromReference(position, reference)
        val impact = QueryImpactSourceFailureDocument.Reference.fromReference(reference)
        assertTrue(reference.authorityMismatch === mismatch)
        assertTrue(step.authorityMismatch === mismatch)
        assertTrue(impact.authorityMismatch === mismatch)
        assertEquals(
            step,
            Json.decodeFromString(QueryRunRejection.StepReferenceRejected.serializer(), Json.encodeToString(step)),
        )
        assertEquals(
            impact,
            Json.decodeFromString(QueryImpactSourceFailureDocument.Reference.serializer(), Json.encodeToString(impact)),
        )
        val source = SourceReadFailureDetail.ReferenceRejected.staleAuthority(SourceReferenceRole.CANDIDATE, mismatch)
        assertTrue(source.authorityMismatch === mismatch)
        assertEquals(source, Json.decodeFromString<SourceReadCause>(Json.encodeToString<SourceReadCause>(source)))
    }

    private fun <T> parsed(refinement: Refinement<T, *>): T =
        when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error("Invalid explicit fixture: ${refinement.failure}")
        }
}

@Serializable
private data class RawEpochMismatch(
    @SerialName("expected_epoch") val expected: Long,
    @SerialName("observed_epoch") val observed: Long,
    @kotlinx.serialization.Required val type: String = "EPOCH_MISMATCH",
)

@Serializable
private data class RawReferenceFailure(
    val position: ProtocolOffset,
    val reason: QueryReferenceRejectionReason,
    @SerialName("authority_mismatch") val authority: ReferenceAuthorityMismatch,
)

@Serializable
private data class RawStepFailure(
    val stepPosition: ProtocolOffset,
    val referencePosition: ProtocolOffset,
    val reason: QueryReferenceRejectionReason,
    @SerialName("authority_mismatch") val authority: ReferenceAuthorityMismatch,
)

@Serializable
private data class RawSourceFailure(
    val type: String,
    val role: SourceReferenceRole,
    val reason: SourceReferenceFailure,
    @SerialName("authority_mismatch") val authority: ReferenceAuthorityMismatch,
)
