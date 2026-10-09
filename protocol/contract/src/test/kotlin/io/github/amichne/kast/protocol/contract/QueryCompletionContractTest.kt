@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryCompletionContractTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `canonical run has no completion input and question encodes fixed proof`() {
        val input =
            DefaultCompletionRun(
                QueryFromDocument.Location(
                    (ProtocolText.parse("src/Example.kt") as Refinement.Refined).value,
                    (ProtocolOffset.parse(0) as Refinement.Refined).value,
                ),
                (BoundedProtocolList.create(emptyList<QueryStepDocument>()) as Refinement.Refined).value,
                QueryOutputDocument.Occurrences,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            )
        val request =
            json.decodeFromString(
                QueryRunRequest.Run.serializer(),
                json.encodeToString(DefaultCompletionRun.serializer(), input),
            )
        assertFalse(
            json.encodeToJsonElement(QueryRunRequest.Run.serializer(), request).jsonObject.containsKey("completion")
        )
        val question = Json.encodeToJsonElement(QueryQuestionDocument.serializer(), QueryQuestionDocument.from(request))
        assertEquals(
            "COMPLETE_ONLY",
            question.jsonObject.getValue("completion").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "COMPILER_RESOLVED_STATIC_V1",
            question.jsonObject.getValue("completion").jsonObject.getValue("model").jsonPrimitive.content,
        )
        val missing =
            json.encodeToString(
                CompletionlessQuestion.serializer(),
                CompletionlessQuestion(request.from, request.steps, request.output),
            )
        assertThrows(MissingFieldException::class.java) {
            json.decodeFromString(QueryQuestionDocument.serializer(), missing)
        }
    }

    @Test
    fun `complete only has required explicit discriminator and model`() {
        val policy = QueryCompletionDocument()
        val encoded = Json.encodeToJsonElement(QueryCompletionDocument.serializer(), policy).jsonObject
        assertEquals(setOf("type", "model"), encoded.keys)
        assertEquals("COMPLETE_ONLY", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("COMPILER_RESOLVED_STATIC_V1", encoded.getValue("model").jsonPrimitive.content)
        for (invalid in
            listOf(
                InvalidPolicy(),
                InvalidPolicy("COMPLETE_ONLY"),
                InvalidPolicy("complete_only", "COMPILER_RESOLVED_STATIC_V1"),
                InvalidPolicy("COMPLETE_ONLY", "UNKNOWN"),
                InvalidPolicy("PROGRESSIVE", "COMPILER_RESOLVED_STATIC_V1"),
            )) {
            for (raw in
                listOf(
                    Json.encodeToString(InvalidPolicy.serializer(), invalid),
                    json.encodeToString(InvalidPolicy.serializer(), invalid),
                )) {
                assertThrows(SerializationException::class.java) {
                    json.decodeFromString(QueryCompletionDocument.serializer(), raw)
                }
            }
        }
    }

    @Test
    fun `canonical run rejects every supplied completion field`() {
        val input =
            DefaultCompletionRun(
                QueryFromDocument.Location(
                    (ProtocolText.parse("src/Example.kt") as Refinement.Refined).value,
                    (ProtocolOffset.parse(0) as Refinement.Refined).value,
                ),
                (BoundedProtocolList.create(emptyList<QueryStepDocument>()) as Refinement.Refined).value,
                QueryOutputDocument.Occurrences,
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            )
        for (policy in
            listOf(
                null,
                InvalidPolicy("PROGRESSIVE"),
                InvalidPolicy("COMPLETE_ONLY", "COMPILER_RESOLVED_STATIC_V1"),
                InvalidPolicy("UNKNOWN"),
            )) {
            val raw =
                json.encodeToString(
                    ObsoleteCompletionRun.serializer(),
                    ObsoleteCompletionRun(input.from, input.steps, input.output, input.execution, policy),
                )
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryRunRequest.Run.serializer(), raw)
            }
        }
    }

    @Test
    fun `original qualifications retain a nonempty canonical limitation set`() {
        val values = listOf(QueryLimitationDocument.RELATION_INCOMPLETE, QueryLimitationDocument.EXECUTION_INCOMPLETE)
        val admitted = (QueryCompletionLimitationsDocument.from(values) as Refinement.Refined).value
        val encoded = json.encodeToString(QueryCompletionLimitationsDocument.serializer(), admitted)
        assertEquals(
            listOf("RELATION_INCOMPLETE", "EXECUTION_INCOMPLETE"),
            json.decodeFromString(ListSerializer(String.serializer()), encoded),
        )
        for (values in
            listOf(
                emptyList(),
                listOf("RELATION_INCOMPLETE", "RELATION_INCOMPLETE"),
                listOf("EXECUTION_INCOMPLETE", "RELATION_INCOMPLETE"),
                listOf("UNKNOWN"),
            )) {
            val raw = json.encodeToString(ListSerializer(String.serializer()), values)
            assertThrows(SerializationException::class.java) {
                json.decodeFromString(QueryCompletionLimitationsDocument.serializer(), raw)
            }
        }
    }
}

/** Deliberately invalid typed boundary fixture; nullable fields exercise absence. */
@Serializable private data class InvalidPolicy(val type: String? = null, val model: String? = null)

/** Boundary input has exactly the current canonical run fields. */
@Serializable
private data class DefaultCompletionRun(
    val from: QueryFromDocument,
    val steps: BoundedProtocolList<QueryStepDocument>,
    val output: QueryOutputDocument,
    val execution: QueryExecutionDocument,
)

/** Negative fixture deliberately supplies a removed canonical input field. */
@Serializable
private data class ObsoleteCompletionRun(
    val from: QueryFromDocument,
    val steps: BoundedProtocolList<QueryStepDocument>,
    val output: QueryOutputDocument,
    val execution: QueryExecutionDocument,
    val completion: InvalidPolicy?,
)

/** Negative output fixture cannot manufacture the fixed proof from a missing field. */
@Serializable
private data class CompletionlessQuestion(
    val from: QueryFromDocument,
    val steps: BoundedProtocolList<QueryStepDocument>,
    val output: QueryOutputDocument,
)
