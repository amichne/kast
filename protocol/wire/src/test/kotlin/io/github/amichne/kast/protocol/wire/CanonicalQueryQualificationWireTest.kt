package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalQueryQualificationWireTest {
    @Test
    fun `continuation and every terminal reason have independent encoded qualification shapes`() {
        val json = Json { encodeDefaults = true }
        val token =
            QueryExecutionContinuation.Pipeline.parse("query:v1:00000000-0000-0000-0000-000000000001").refinedValue()
        val resumable =
            qualification(
                QueryQualifiedProgressDocument.Resumable(
                    QueryCheckpointDocument.Upstream(token),
                    ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
                )
            )
        assertEquals(
            WireValueEncoding.Encoded(
                json.encodeToJsonElement(
                    ExpectedQueryQualification.serializer(),
                    ExpectedQueryQualification(
                        progress =
                            ExpectedQueryProgress.Resumable(ExpectedUpstream(token.value), "increase_execution_budget")
                    ),
                )
            ),
            CanonicalQuerySerializers.qualification.encode(resumable, WireValueRole.QUALIFICATION),
        )
        val names =
            mapOf(
                QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE to "upstream-incomplete",
                QueryTerminalReasonDocument.OUTPUT_ITEM_TOO_LARGE to "output-item-too-large",
                QueryTerminalReasonDocument.CHECKPOINT_CAPACITY_EXCEEDED to "checkpoint-capacity-exceeded",
                QueryTerminalReasonDocument.NO_PROGRESS to "no-progress",
            )
        for ((reason, encoded) in names) {
            assertEquals(
                WireValueEncoding.Encoded(
                    json.encodeToJsonElement(
                        ExpectedQueryQualification.serializer(),
                        ExpectedQueryQualification(progress = ExpectedQueryProgress.Terminal(encoded)),
                    )
                ),
                CanonicalQuerySerializers.qualification.encode(
                    qualification(QueryQualifiedProgressDocument.TerminalIncomplete(reason)),
                    WireValueRole.QUALIFICATION,
                ),
            )
        }
    }

    @Test
    fun `unknown terminal reason cannot become qualified query evidence`() {
        val json = Json { encodeDefaults = true }
        assertTrue(
            CanonicalQuerySerializers.qualification.decode(
                json.encodeToJsonElement(
                    ExpectedQueryQualification.serializer(),
                    ExpectedQueryQualification(progress = ExpectedQueryProgress.Terminal("invented")),
                ),
                WireValueRole.QUALIFICATION,
            ) is WireDecoding.Rejected
        )
    }

    @Test
    fun `row selection limitation and finite composition rejections retain wire identity`() {
        val qualification =
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(0).refinedValue(),
                    listOf(QueryLimitationDocument.ROW_SELECTION_INCOMPLETE),
                    QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
                )
                .refinedValue()
        val encodedQualification =
            CanonicalQuerySerializers.qualification.encode(qualification, WireValueRole.QUALIFICATION)
                as WireValueEncoding.Encoded
        assertEquals(
            "row-selection-incomplete",
            encodedQualification.value.jsonObject.getValue("limitations").jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(
            qualification,
            (CanonicalQuerySerializers.qualification.decode(encodedQualification.value, WireValueRole.QUALIFICATION)
                    as WireDecoding.Decoded)
                .value,
        )

        for ((reason, name) in finiteCompositionRejections) {
            val rejection = QueryRunRejection.ExecutionRejected(reason)
            val encoded =
                CanonicalQuerySerializers.rejection.encode(rejection, WireValueRole.REJECTION)
                    as WireValueEncoding.Encoded
            assertEquals("execution-rejected", encoded.value.jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(name, encoded.value.jsonObject.getValue("reason").jsonPrimitive.content)
            assertEquals(
                rejection,
                (CanonicalQuerySerializers.rejection.decode(encoded.value, WireValueRole.REJECTION)
                        as WireDecoding.Decoded)
                    .value,
            )
            assertTrue(
                CanonicalQuerySerializers.rejection.decode(
                    Json.parseToJsonElement(encoded.value.toString().replace(name, "retired-rejection")),
                    WireValueRole.REJECTION,
                ) is WireDecoding.Rejected
            )
        }
    }

    private val finiteCompositionRejections =
        mapOf(
            QueryExecutionRejectionDocument.RESULT_ROW_UNAVAILABLE to "result-row-unavailable",
            QueryExecutionRejectionDocument.RESULT_FIELD_UNAVAILABLE to "result-field-unavailable",
            QueryExecutionRejectionDocument.RIGHT_INPUT_INCOMPLETE to "right-input-incomplete",
            QueryExecutionRejectionDocument.UNKNOWN_BINDING_NAME to "unknown-binding-name",
            QueryExecutionRejectionDocument.OUTPUT_KIND_MISMATCH to "output-kind-mismatch",
            QueryExecutionRejectionDocument.CONTINUATION_IN_USE to "continuation-in-use",
            QueryExecutionRejectionDocument.CONTINUATION_OWNER_RETIRED to "continuation-owner-retired",
            QueryExecutionRejectionDocument.CONTINUATION_CLAIM_UNAVAILABLE to "continuation-claim-unavailable",
            QueryExecutionRejectionDocument.CONTINUATION_EXPIRED to "continuation-expired",
            QueryExecutionRejectionDocument.CONTINUATION_DEPENDENCY_UNAVAILABLE to
                "continuation-dependency-unavailable",
            QueryExecutionRejectionDocument.PUBLISHED_PAGE_MISMATCH to "published-page-mismatch",
            QueryExecutionRejectionDocument.NON_ADVANCING_CONTINUATION to "non-advancing-continuation",
        )

    private fun qualification(progress: QueryQualifiedProgressDocument) =
        QueryRunQualification.create(
                QueryKnownMinimum.parse(0).refinedValue(),
                listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                progress,
            )
            .refinedValue()

    private fun <Value, Failure> Refinement<Value, Failure>.refinedValue(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}

@Serializable
private data class ExpectedQueryQualification(
    val knownMinimum: Int = 0,
    val limitations: List<String> = listOf("work-limit-reached"),
    val progress: ExpectedQueryProgress,
)

@Serializable
private sealed interface ExpectedQueryProgress {
    @Serializable
    @kotlinx.serialization.SerialName("resumable")
    data class Resumable(
        val checkpoint: ExpectedUpstream,
        @kotlinx.serialization.SerialName("next_action") val nextAction: String,
    ) : ExpectedQueryProgress

    @Serializable
    @kotlinx.serialization.SerialName("terminal_incomplete")
    data class Terminal(val reason: String) : ExpectedQueryProgress
}

@Serializable private data class ExpectedUpstream(val token: String, val type: String = "upstream")
