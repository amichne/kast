package io.github.amichne.kast.distribution.output

import io.github.amichne.kast.distribution.contract.configuration.CliOutputFailure
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LosslessCliOutputTest {
    @Test
    fun `reencoding admits equal decimals and rejects changed numeric values signs and array shape`() {
        val decimal = (JsonProjectionDocument.parse("1.2300") as Refinement.Refined).value
        assertTrue(decimal.reencoded("1.23") is Refinement.Refined)
        for ((before, after) in
            listOf(
                "9223372036854775807" to "9223372036854776000",
                "-0.0" to "0.0",
                "[[1],[]]" to "[1]",
                "null" to "false",
            )) {
            val original = (JsonProjectionDocument.parse(before) as Refinement.Refined).value
            assertEquals(Refinement.Rejected(CliOutputFailure.OUTPUT_VALUE_LOSS), original.reencoded(after))
        }
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `TOON rejects exact decimal loss instead of rounding a jq projection`() {
        val decimal = "1.2345678901234567890123456789"
        for (document in listOf(decimal, Json.encodeToString(listOf(JsonUnquotedLiteral(decimal))))) {
            assertEquals(
                Refinement.Rejected(CliOutputFailure.OUTPUT_VALUE_LOSS),
                renderLosslessCliOutput(document, CliOutputFormat.TOON),
            )
            assertEquals(
                document,
                (renderLosslessCliOutput(document, CliOutputFormat.JSON) as Refinement.Refined).value.text,
            )
        }
    }

    @Test
    fun `scalars arrays exact integers and empty objects remain distinct`() {
        val emptyArray = Json.encodeToString(emptyList<String>())
        val examples =
            mapOf(
                "null" to "null",
                "false" to "false",
                Json.encodeToString(EmptyBody()) to "",
                emptyArray to emptyArray,
                "\"\"" to "\"\"",
                "9223372036854775807" to "9223372036854775807",
            )
        for ((document, toon) in examples) {
            assertEquals(
                toon,
                (renderLosslessCliOutput(document, CliOutputFormat.TOON) as Refinement.Refined).value.text,
            )
        }
        val arrays = Json.encodeToString(listOf(listOf(false, null), emptyList()))
        assertTrue(renderLosslessCliOutput(arrays, CliOutputFormat.TOON) is Refinement.Refined)
    }

    @Test
    fun `multiple or malformed JSON documents reject in both formats`() {
        val objectDocument = Json.encodeToString(ScalarHolder(1))
        for (document in
            listOf(
                "1\n2",
                "{}{}",
                "{",
                "",
                "invalid",
                "NaN",
                "01",
                objectDocument.replace("\"", ""),
                objectDocument.replace("1", "1,"),
            )) {
            for (format in CliOutputFormat.entries) {
                assertEquals(
                    Refinement.Rejected(CliOutputFailure.OUTPUT_ENCODING_REJECTED),
                    renderLosslessCliOutput(document, format),
                )
            }
        }
    }
}

@Serializable private class EmptyBody

@Serializable private data class ScalarHolder(val key: Int)
