package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TraversalQualificationWireTest {
    private val codec = CanonicalReadSerializers.traversalRunQualification
    private val upstreamToken =
        TraversalContinuationDocument.parse(
                "traversal-continuation:v1:cXVhbGlmaWNhdGlvbg:" +
                    "1dbf39600b5761d58378447f494a50c8b9c01b559b6ef420720f99f4e45717c9"
            )
            .value()
    private val outputToken =
        TraversalContinuationDocument.parse("traversal-output:v1:00000000-0000-0000-0000-000000000001").value()

    @Test
    fun `upstream checkpoint preserves explicit actions and derived continuation`() {
        val resumable =
            TraversalRunQualification.resumable(
                    listOf(
                        TraversalLimitationDocument.RECORD_LIMIT_REACHED,
                        TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
                    ),
                    listOf(RelationLimitationDocument.PROVIDER_INCOMPLETE),
                    upstreamToken,
                )
                .value()
        assertQualification(resumable, "upstream-resume")
        assertEquals(upstreamToken, resumable.continuation)
        assertQualification(
            TraversalRunQualification.admitResumable(
                    resumable.limitations,
                    resumable.relationLimitations,
                    resumable.checkpoint,
                    ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
                )
                .value(),
            "upstream-increase-budget",
        )
    }

    @Test
    fun `retained output preserves every upstream coverage variant`() {
        for (coverage in TraversalPreparedCoverageDocument.entries) {
            val limitations =
                when (coverage) {
                    TraversalPreparedCoverageDocument.COMPLETE,
                    TraversalPreparedCoverageDocument.RESUMABLE ->
                        listOf(TraversalLimitationDocument.RECORD_LIMIT_REACHED)
                    TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE ->
                        listOf(
                            TraversalLimitationDocument.RECORD_LIMIT_REACHED,
                            TraversalLimitationDocument.DEPTH_LIMIT_REACHED,
                        )
                }
            val qualification =
                TraversalRunQualification.admitResumable(
                        limitations,
                        emptyList(),
                        TraversalCheckpointDocument.RetainedOutput(outputToken, coverage),
                        ReadResumeActionDocument.RESUME,
                    )
                    .value()
            val fixture =
                when (coverage) {
                    TraversalPreparedCoverageDocument.COMPLETE -> "retained-complete"
                    TraversalPreparedCoverageDocument.RESUMABLE -> "retained-resumable"
                    TraversalPreparedCoverageDocument.TERMINAL_INCOMPLETE -> "retained-terminal"
                }
            assertQualification(qualification, fixture)
            assertEquals(outputToken, qualification.continuation)
        }
    }

    @Test
    fun `terminal qualification preserves relation evidence without a checkpoint`() {
        assertQualification(
            TraversalRunQualification.terminalIncomplete(
                    listOf(TraversalLimitationDocument.ONE_HOP_INCOMPLETE),
                    listOf(RelationLimitationDocument.UNRESOLVED_TARGET),
                )
                .value(),
            "terminal",
        )
    }

    @Test
    fun `malformed and incompatible qualifications fail closed`() {
        val rejected = WireDecoding.Rejected(WireFailure.InvalidPayload(WireValueRole.QUALIFICATION))
        for (document in fixture("rejected").jsonArray) {
            assertEquals(rejected, codec.decode(document, WireValueRole.QUALIFICATION), document.toString())
        }
    }

    private fun assertQualification(qualification: TraversalRunQualification, name: String) {
        val expected = fixture(name)
        assertEquals(WireValueEncoding.Encoded(expected), codec.encode(qualification, WireValueRole.QUALIFICATION))
        assertEquals(WireDecoding.Decoded(qualification), codec.decode(expected, WireValueRole.QUALIFICATION))
    }

    private fun fixture(name: String): JsonElement =
        wireJson.parseToJsonElement(
            checkNotNull(javaClass.getResource("/traversal/qualification/$name.json")).readText()
        )

    private fun <Value, Failure> Refinement<Value, Failure>.value(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement, got $failure")
        }
}
