package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ExecutionAllowance
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
        assertEquals(
            """{"max_elapsed_ms":200,"max_work_units":50,"max_results":2,"max_returned_bytes":1000}""",
            Json.encodeToString(document),
        )
        assertEquals(ExecutionAllowance.Requested(ResultLimit.parse(2).proven()), document.requested().results)
    }

    @Test
    fun `nonpositive overflowing and unknown controls fail before semantic admission`() {
        for (field in listOf("max_elapsed_ms", "max_work_units", "max_results", "max_returned_bytes")) {
            for (invalid in listOf("0", "-1", "9223372036854775808", "1.5", "\"10\"")) {
                assertThrows(
                    SerializationException::class.java,
                    { Json.decodeFromString<ExecutionBudgetDocument>("{\"$field\":$invalid}") },
                    "$field=$invalid",
                )
            }
        }
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<ExecutionBudgetDocument>("""{"unsupported":1}""")
        }
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
