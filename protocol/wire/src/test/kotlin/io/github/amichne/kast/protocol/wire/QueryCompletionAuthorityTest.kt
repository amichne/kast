package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryCheckpointDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryPreparedCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class QueryCompletionAuthorityTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `query results cannot independently claim a cursor or terminal reason`() {
        for (value in
            listOf(
                LegacyQueryResult(continuation = "query:v1:00000000-0000-0000-0000-000000000001"),
                LegacyQueryResult(terminalReason = "upstream-incomplete"),
                LegacyQueryResult(
                    continuation = "query:v1:00000000-0000-0000-0000-000000000001",
                    terminalReason = "no-progress",
                ),
            )) assertInstanceOf(
            WireDecoding.Rejected::class.java,
            CanonicalQuerySerializers.result.decode(
                json.encodeToJsonElement(LegacyQueryResult.serializer(), value),
                WireValueRole.RESULT,
            ),
        )
    }

    @Test
    fun `checkpoint family must match its declared continuation scope`() {
        val id = "00000000-0000-0000-0000-000000000001"
        val pipeline = (QueryExecutionContinuation.Pipeline.parse("query:v1:$id") as Refinement.Refined).value
        val output = (QueryExecutionContinuation.Output.parse("query-output:v1:$id") as Refinement.Refined).value
        val cases =
            listOf(
                QueryCheckpointDocument.Upstream(pipeline) to "query-output:v1:$id",
                QueryCheckpointDocument.Upstream(pipeline) to "query:v1:not-an-issued-identity",
                QueryCheckpointDocument.RetainedOutput(output, QueryPreparedCoverageDocument.Complete) to
                    "query:v1:$id",
            )
        for ((checkpoint, invalidToken) in cases) {
            val boundary =
                QueryRunQualificationWireDocument(
                    0,
                    listOf(QueryLimitationWireDocument.WORK_LIMIT_REACHED),
                    QueryQualifiedProgressDocument.Resumable(
                        checkpoint,
                        ReadResumeActionDocument.INCREASE_EXECUTION_BUDGET,
                    ),
                )
            val encoded = json.encodeToString(QueryRunQualificationWireDocument.serializer(), boundary)
            val malformed = json.parseToJsonElement(encoded.replace(checkpoint.token.value, invalidToken))
            assertInstanceOf(
                WireDecoding.Rejected::class.java,
                CanonicalQuerySerializers.qualification.decode(
                    malformed,
                    WireValueRole.QUALIFICATION,
                ),
            )
        }
    }

    @Test
    fun `qualified query evidence requires an explicit closed continuation state`() {
        assertInstanceOf(
            WireDecoding.Rejected::class.java,
            CanonicalQuerySerializers.qualification.decode(
                json.encodeToJsonElement(QualificationWithoutProgress.serializer(), QualificationWithoutProgress()),
                WireValueRole.QUALIFICATION,
            ),
        )
    }
}

/** Deliberately incompatible pre-refinement shapes prove that outcome authority cannot be split across payloads. */
@Serializable
private data class LegacyQueryResult(
    val items: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val continuation: String? = null,
    val terminalReason: String? = null,
)

@Serializable
private data class QualificationWithoutProgress(
    val knownMinimum: Int = 0,
    val limitations: List<String> = listOf("work-limit-reached"),
)
