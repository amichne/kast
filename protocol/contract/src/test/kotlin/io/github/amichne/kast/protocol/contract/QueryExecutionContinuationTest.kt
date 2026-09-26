package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

private const val PIPELINE = "query:v1:00000000-0000-0000-0000-000000000001"
private const val OUTPUT = "query-output:v1:00000000-0000-0000-0000-000000000002"
private const val RESULT = "result:v1:00000000-0000-0000-0000-000000000001"

class QueryExecutionContinuationTest {
    @Test
    fun `pipeline and output continuation families cannot be interchanged`() {
        assertInstanceOf(Refinement.Refined::class.java, QueryExecutionContinuation.Pipeline.parse(PIPELINE))
        assertInstanceOf(Refinement.Refined::class.java, QueryExecutionContinuation.Output.parse(OUTPUT))
        assertInstanceOf(
            QueryExecutionContinuation.Pipeline::class.java,
            QueryExecutionContinuation.parse(PIPELINE).refined(),
        )
        assertInstanceOf(
            QueryExecutionContinuation.Output::class.java,
            QueryExecutionContinuation.parse(OUTPUT).refined(),
        )
        assertEquals(
            Refinement.Rejected(QueryExecutionContinuationFailure.MALFORMED),
            QueryExecutionContinuation.Pipeline.parse(OUTPUT),
        )
        assertEquals(
            Refinement.Rejected(QueryExecutionContinuationFailure.MALFORMED),
            QueryExecutionContinuation.Output.parse(PIPELINE),
        )
        for (other in listOf(RESULT, "exact:v5:opaque", "query:v1:UPPER")) {
            assertEquals(
                Refinement.Rejected(QueryExecutionContinuationFailure.MALFORMED),
                QueryExecutionContinuation.parse(other),
            )
        }
    }

    @Test
    fun `resume encodes only its action and opaque continuation string`() {
        val pipeline = QueryExecutionContinuation.Pipeline.parse(PIPELINE).refined()
        val encoded = Json.encodeToString(QueryRunRequest.serializer(), QueryRunRequest.Resume(pipeline))
        val fields = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("action", "continuation"), fields.keys)
        assertEquals("resume", fields.getValue("action").jsonPrimitive.content)
        assertEquals(PIPELINE, fields.getValue("continuation").jsonPrimitive.content)

        val output = QueryExecutionContinuation.Output.parse(OUTPUT).refined()
        val outputEncoded = Json.encodeToString(QueryRunRequest.serializer(), QueryRunRequest.Resume(output))
        val decoded = Json.decodeFromString(QueryRunRequest.serializer(), outputEncoded)
        val resumed = assertInstanceOf(QueryRunRequest.Resume::class.java, decoded)
        assertInstanceOf(QueryExecutionContinuation.Output::class.java, resumed.continuation)
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(QueryRunRequest.serializer(), encoded.replace(PIPELINE, RESULT))
        }
    }

    @Test
    fun `checkpoint variants keep typed families and string tokens`() {
        val pipeline = QueryExecutionContinuation.Pipeline.parse(PIPELINE).refined()
        val output = QueryExecutionContinuation.Output.parse(OUTPUT).refined()
        val upstream = QueryCheckpointDocument.Upstream(pipeline)
        val upstreamEncoded = Json.encodeToString(QueryCheckpointDocument.serializer(), upstream)
        val upstreamFields = Json.parseToJsonElement(upstreamEncoded).jsonObject
        assertEquals(setOf("type", "token"), upstreamFields.keys)
        assertEquals("upstream", upstreamFields.getValue("type").jsonPrimitive.content)
        assertEquals(PIPELINE, upstreamFields.getValue("token").jsonPrimitive.content)

        val retained = QueryCheckpointDocument.RetainedOutput(output, QueryPreparedCoverageDocument.Complete)
        val retainedEncoded = Json.encodeToString(QueryCheckpointDocument.serializer(), retained)
        val retainedFields = Json.parseToJsonElement(retainedEncoded).jsonObject
        assertEquals(setOf("type", "token", "upstream"), retainedFields.keys)
        assertEquals("retained_output", retainedFields.getValue("type").jsonPrimitive.content)
        assertEquals(OUTPUT, retainedFields.getValue("token").jsonPrimitive.content)
        val coverageFields = retainedFields.getValue("upstream").jsonObject
        assertEquals(setOf("type"), coverageFields.keys)
        assertEquals("complete", coverageFields.getValue("type").jsonPrimitive.content)

        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(QueryCheckpointDocument.serializer(), upstreamEncoded.replace(PIPELINE, OUTPUT))
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(QueryCheckpointDocument.serializer(), retainedEncoded.replace(OUTPUT, PIPELINE))
        }
        assertEquals(
            pipeline,
            QueryQualifiedProgressDocument.Resumable(upstream, ReadResumeActionDocument.RESUME).continuationToken,
        )
        assertEquals(
            output,
            QueryQualifiedProgressDocument.Resumable(retained, ReadResumeActionDocument.RESUME).continuationToken,
        )
        assertNull(
            QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE)
                .continuationToken
        )
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = (this as Refinement.Refined).value
}
