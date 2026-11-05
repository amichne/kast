package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicJqLibraryTest {
    @Test
    fun `navigation map select and composition preserve independently specified shape`() {
        val input = Json.encodeToString(listOf(listOf(1, 2), emptyList(), listOf(3)))
        assertEquals(
            Json.encodeToString(listOf(listOf(2), emptyList(), listOf(3))),
            evaluate("map(map(select(. > 1)))", input),
        )
        assertEquals("null", evaluate(".missing", "null"))
        assertEquals("false", evaluate("false", "null"))
        assertEquals(
            Json.encodeToString(emptyList<Int>()),
            evaluate("[.[] | select(. > 9)]", Json.encodeToString(listOf(1, 2))),
        )
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
            PublicJqFailure.NUMERIC_LITERAL_LIMIT,
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
    @OptIn(ExperimentalSerializationApi::class)
    fun `numeric comparison and grouping reject lossy input coercion without losing navigation`() {
        val identifiers = Json.encodeToString(listOf(9007199254740992L, 9007199254740993L))
        assertFailure(PublicJqFailure.NUMERIC_VALUE_LOSS, ".[0] == .[1]", identifiers)
        assertFailure(PublicJqFailure.NUMERIC_VALUE_LOSS, "unique", identifiers)
        assertEquals(identifiers, evaluate(".", identifiers))
        assertEquals(
            Json.encodeToString(listOf("9007199254740992", "9007199254740993")),
            evaluate("map(tostring)", identifiers),
        )
        // Numeric tokens are dynamic user-projection data, not a fixed protocol shape.
        val decimals =
            Json.encodeToString(
                listOf(
                    JsonUnquotedLiteral("1.2345678901234567890123456789"),
                    JsonUnquotedLiteral("1.2345678901234567890123456788"),
                )
            )
        assertFailure(PublicJqFailure.NUMERIC_VALUE_LOSS, ".[0] == .[1]", decimals)
        assertEquals("true", evaluate(".[0] < .[1]", Json.encodeToString(listOf(0.1, 0.2))))
        assertFailure(
            PublicJqFailure.NUMERIC_VALUE_LOSS,
            ".items[.index]",
            Json.encodeToString(NumericIndex(listOf("first", "last"), 4294967295L)),
        )
    }

    @Test
    fun `literal admission prevents narrowing and handles lexer and normalization rejection`() {
        for (program in
            listOf("2147483648", "4294967296", "-2147483648", ".5", "0.5", "1e-1", "100e2147483647", "1e-2147483647")) {
            assertEquals(
                Refinement.Rejected(PublicJqFailure.NUMERIC_LITERAL_LIMIT),
                PublicJqProgram.compile(program),
                program,
            )
        }
        for (program in listOf("2147483647", "2147483647.0", "1e3", "0e2147483647", "0e-2147483647")) {
            assertTrue(PublicJqProgram.compile(program) is Refinement.Refined, program)
        }
        for (program in listOf(")", "1)")) assertEquals(
            Refinement.Rejected(PublicJqFailure.PROGRAM_INVALID),
            PublicJqProgram.compile(program),
        )
        assertEquals("3", evaluate("((. + 1))", "2"))
        assertEquals("1.25", evaluate("fromjson", Json.encodeToString("1.25")))
    }

    @Test
    fun `implode preserves complete Unicode scalars and rejects invalid code points`() {
        val points = listOf(0x10000, 0x1F600, 0x10FFFF)
        val expected = points.joinToString("") { String(Character.toChars(it)) }
        assertEquals(Json.encodeToString(expected), evaluate("implode", Json.encodeToString(points)))
        assertEquals(Json.encodeToString(expected), evaluate("explode | implode", Json.encodeToString(expected)))
        assertEquals(Json.encodeToString(""), evaluate("implode", Json.encodeToString(emptyList<Int>())))
        for (point in listOf(-1, 0xD800, 0xDFFF, 0x110000)) {
            assertFailure(PublicJqFailure.EVALUATION_FAILED, "implode", Json.encodeToString(listOf(point)))
        }
        assertFailure(PublicJqFailure.EVALUATION_FAILED, "implode", Json.encodeToString(listOf(0.5)))
        assertFailure(PublicJqFailure.EVALUATION_FAILED, "implode", Json.encodeToString(listOf("text")))
        assertFailure(PublicJqFailure.OUTPUT_INVALID, Json.encodeToString("\uD800"))
    }

    @Test
    fun `numeric token limits report a finite cause instead of malformed output`() {
        assertFailure(PublicJqFailure.JSON_LIMIT, ".", "1" + "0".repeat(PublicJqLimits.JSON_NUMBER_CHARACTERS))
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
        assertEquals(Refinement.Rejected(expected), result, program)
        assertFalse(result.toString().contains("private payload"))
    }
}

@Serializable private data class NumericIndex(val items: List<String>, val index: Long)
