package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExecutionBudgetDocumentTest {
    @Test
    fun `encoded budget uses four positive controls and preserves typed requests`() {
        val document =
            ExecutionBudgetDocument(
                ElapsedTimeLimitMillis.parse(200).proven(),
                WorkUnitLimit.parse(50).proven(),
                ResultLimit.parse(2).proven(),
                ReturnedByteLimit.parse(1_000).proven(),
            )
        assertEquals(Json.encodeToString(ExpectedExecutionBudget(200, 50, 2, 1_000)), Json.encodeToString(document))
        assertEquals(ExecutionAllowance.Requested(ResultLimit.parse(2).proven()), document.requested().results)
    }

    @Test
    fun `nonpositive overflowing and unknown controls fail before semantic admission`() {
        for (invalid in listOf("0", "-1", "9223372036854775808", "1.5", "\"10\"")) {
            val scalar = Json.parseToJsonElement(invalid)
            for (document in
                listOf(
                    InvalidExecutionBudget(elapsed = scalar),
                    InvalidExecutionBudget(work = scalar),
                    InvalidExecutionBudget(results = scalar),
                    InvalidExecutionBudget(bytes = scalar),
                )) {
                assertThrows(SerializationException::class.java) {
                    Json.decodeFromString<ExecutionBudgetDocument>(Json.encodeToString(document))
                }
            }
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<ExecutionBudgetDocument>(Json.encodeToString(UnknownExecutionBudget(1)))
        }
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}

@Serializable
private data class ExpectedExecutionBudget(
    @SerialName("max_elapsed_ms") val elapsed: Long,
    @SerialName("max_work_units") val work: Long,
    @SerialName("max_results") val results: Int,
    @SerialName("max_returned_bytes") val bytes: Long,
)

/** Opaque invalid scalar values intentionally exercise the numeric admission boundary. */
@Serializable
private data class InvalidExecutionBudget(
    @SerialName("max_elapsed_ms") val elapsed: JsonElement? = null,
    @SerialName("max_work_units") val work: JsonElement? = null,
    @SerialName("max_results") val results: JsonElement? = null,
    @SerialName("max_returned_bytes") val bytes: JsonElement? = null,
)

@Serializable private data class UnknownExecutionBudget(val unsupported: Int)
