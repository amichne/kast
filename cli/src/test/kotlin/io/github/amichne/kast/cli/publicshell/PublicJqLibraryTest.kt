package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PublicJqLibraryTest {
    @Test
    fun `navigation map select and composition preserve independently specified shape`() {
        val input = Json.encodeToString(listOf(listOf(1, 2), emptyList(), listOf(3)))
        assertEquals("[[2],[],[3]]", evaluate("map(map(select(. > 1)))", input))
        assertEquals("null", evaluate(".missing", "null"))
        assertEquals("false", evaluate("false", "null"))
        assertEquals("[]", evaluate("[.[] | select(. > 9)]", "[1,2]"))
    }

    @Test
    fun `exact input decimals and signed 64 bit integers survive identity`() {
        for (value in listOf("9223372036854775807", "1.2345678901234567890123456789", "1.2300")) {
            val projected = evaluate(".", value)
            assertEquals(0, value.toBigDecimal().compareTo(projected.toBigDecimal()), value)
        }
        assertFailure(PublicJqFailure.NUMERIC_VALUE_LOSS, ".", "-0.0")
        val decimal = "1.2345678901234567890123456789"
        assertEquals(decimal, evaluate("fromjson", Json.encodeToString(decimal)))
        assertEquals(decimal, evaluate("tonumber", Json.encodeToString(decimal)))
        assertFailure(PublicJqFailure.NUMERIC_VALUE_LOSS, "fromjson", Json.encodeToString("-0.0"))
    }

    @Test
    fun `invalid lossy literals and unsupported interpolation reject before evaluation`() {
        assertEquals(PublicJqFailure.PROGRAM_INVALID, (PublicJqProgram.compile(".[") as Refinement.Rejected).failure)
        assertEquals(
            PublicJqFailure.NUMERIC_VALUE_LOSS,
            (PublicJqProgram.compile("1.2345678901234567890123456789") as Refinement.Rejected).failure,
        )
        assertEquals(
            PublicJqFailure.PROGRAM_UNSUPPORTED,
            (PublicJqProgram.compile("\"\\(1)\"") as Refinement.Rejected).failure,
        )
        assertEquals(
            Json.encodeToString("1.2345678901234567890123456789"),
            evaluate(Json.encodeToString("1.2345678901234567890123456789"), "null"),
        )
    }

    @Test
    fun `atomic result requires one emission and discards prefix on error`() {
        assertFailure(PublicJqFailure.OUTPUT_CARDINALITY, "empty")
        assertFailure(PublicJqFailure.OUTPUT_CARDINALITY, "1,2")
        assertFailure(PublicJqFailure.EVALUATION_FAILED, "1,error(\"private payload\")")
        assertFailure(PublicJqFailure.EVALUATION_FAILED, "inputs")
        assertFailure(PublicJqFailure.EVALUATION_FAILED, "env")
        assertFailure(PublicJqFailure.EVALUATION_FAILED, "include \"private-module\"; .")
        assertFailure(PublicJqFailure.NUMERIC_VALUE_LOSS, "nan")
        assertFailure(PublicJqFailure.NUMERIC_VALUE_LOSS, "infinite")
    }

    @Test
    fun `library arithmetic differences are explicit qualification cases`() {
        assertEquals("9007199254740994", evaluate(". + 1", "9007199254740993"))
        assertEquals("-9223372036854775808", evaluate(". + 1", "9223372036854775807"))
    }

    private fun evaluate(program: String, input: String): String =
        ((PublicJqProgram.compile(program) as Refinement.Refined).value.evaluate(input, 4096) as Refinement.Refined)
            .value
            .text

    private fun assertFailure(expected: PublicJqFailure, program: String, input: String = "null") {
        val result = (PublicJqProgram.compile(program) as Refinement.Refined).value.evaluate(input, 4096)
        assertEquals(expected, (result as Refinement.Rejected).failure, program)
        assertFalse(result.toString().contains("private payload"))
    }
}
