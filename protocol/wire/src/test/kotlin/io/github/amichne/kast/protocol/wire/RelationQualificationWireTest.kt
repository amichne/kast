package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.RelationCheckpointDocument
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKnownMinimumDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.RelationPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadQualificationFailure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelationQualificationWireTest {
    private val codec = CanonicalReadSerializers.relationReadQualification
    private val upstream =
        RelationContinuationDocument.parse(
                "relation-continuation:v1:cXVhbGlmaWNhdGlvbg:" +
                    "1dbf39600b5761d58378447f494a50c8b9c01b559b6ef420720f99f4e45717c9"
            )
            .proven()
    private val output =
        RelationContinuationDocument.parse("relation-output:v1:00000000-0000-0000-0000-000000000001").proven()
    private val minimum = RelationKnownMinimumDocument.parse(2).proven()

    @Test
    fun `retained relation wire preserves each upstream coverage state and legacy token`() {
        for (coverage in RelationPreparedCoverageDocument.entries) {
            val qualification =
                RelationReadQualification.admitResumable(
                        knownMinimum = minimum,
                        limitations = limitations(coverage),
                        checkpoint = RelationCheckpointDocument.RetainedOutput(output, coverage),
                        nextAction = ReadResumeActionDocument.RESUME,
                    )
                    .proven()
            val expected = fixture("retained-${coverage.name.lowercase()}")
            assertEquals(WireValueEncoding.Encoded(expected), codec.encode(qualification, WireValueRole.QUALIFICATION))
            assertEquals(WireDecoding.Decoded(qualification), codec.decode(expected, WireValueRole.QUALIFICATION))
            assertEquals(output, qualification.continuation)
        }
    }

    @Test
    fun `upstream relation explicitly preserves a supported budget increase action`() {
        val qualification =
            RelationReadQualification.resumable(
                    knownMinimum = minimum,
                    limitations = listOf(RelationLimitationDocument.WORK_LIMIT_REACHED),
                    continuation = upstream,
                    nextAction = ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
                )
                .proven()
        val expected = fixture("upstream-increase-budget")
        assertEquals(WireValueEncoding.Encoded(expected), codec.encode(qualification, WireValueRole.QUALIFICATION))
        assertEquals(WireDecoding.Decoded(qualification), codec.decode(expected, WireValueRole.QUALIFICATION))
    }

    @Test
    fun `checkpoint families output coverage and unsupported output actions reject finitely`() {
        val outputCheckpoint =
            RelationCheckpointDocument.RetainedOutput(output, RelationPreparedCoverageDocument.COMPLETE)
        val cases =
            listOf(
                RelationCheckpointDocument.Upstream(output) to
                    RelationReadQualificationFailure.CONTINUATION_KIND_MISMATCH,
                RelationCheckpointDocument.RetainedOutput(upstream, RelationPreparedCoverageDocument.COMPLETE) to
                    RelationReadQualificationFailure.CONTINUATION_KIND_MISMATCH,
            )
        for ((checkpoint, expected) in cases) {
            assertEquals(
                Refinement.Rejected(expected),
                RelationReadQualification.admitResumable(
                    knownMinimum = minimum,
                    limitations = listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                    checkpoint = checkpoint,
                    nextAction = ReadResumeActionDocument.RESUME,
                ),
            )
        }
        assertEquals(
            Refinement.Rejected(RelationReadQualificationFailure.RETAINED_COVERAGE_CONFLICT),
            RelationReadQualification.admitResumable(
                minimum,
                listOf(RelationLimitationDocument.PROVIDER_FAILURE),
                outputCheckpoint,
                ReadResumeActionDocument.RESUME,
            ),
        )
        assertEquals(
            Refinement.Rejected(RelationReadQualificationFailure.UNSUPPORTED_NEXT_ACTION),
            RelationReadQualification.admitResumable(
                minimum,
                listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED),
                outputCheckpoint,
                ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
            ),
        )
    }

    @Test
    fun `missing unknown and contradictory checkpoint fields reject during wire decoding`() {
        val expected = WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.QUALIFICATION))
        for (document in fixture("rejected").jsonArray) {
            assertEquals(expected, codec.decode(document, WireValueRole.QUALIFICATION), document.toString())
        }
    }

    private fun limitations(coverage: RelationPreparedCoverageDocument) =
        when (coverage) {
            RelationPreparedCoverageDocument.COMPLETE -> listOf(RelationLimitationDocument.RESULT_LIMIT_REACHED)
            RelationPreparedCoverageDocument.RESUMABLE,
            RelationPreparedCoverageDocument.TERMINAL_INCOMPLETE ->
                listOf(
                    RelationLimitationDocument.RESULT_LIMIT_REACHED,
                    RelationLimitationDocument.PROVIDER_FAILURE,
                )
        }

    private fun fixture(name: String): JsonElement =
        wireJson.parseToJsonElement(
            checkNotNull(javaClass.getResource("/relation/qualification/$name.json")).readText()
        )

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
