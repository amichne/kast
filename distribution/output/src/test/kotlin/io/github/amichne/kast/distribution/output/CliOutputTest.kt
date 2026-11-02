package io.github.amichne.kast.distribution.output

import dev.toonformat.jtoon.JToon
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFailure
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.contract.configuration.selectCliOutputFormat
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CliOutputTest {
    @Test
    fun `uniform rows use the published tabular form`() {
        val document = Json.encodeToString(Users(listOf(User(1, "Ada", "admin"), User(2, "Bob", "user"))))
        assertEquals("users[2]{id,name,role}:\n  1,Ada,admin\n  2,Bob,user", render(document))
    }

    @Test
    fun `references source escapes nulls and integer identities survive presentation`() {
        val fixture = Evidence("exact:v5:issued-token", "fun f() {\n  println(\"a,b: c\")\n}", Long.MAX_VALUE)
        val document = Json { encodeDefaults = true }.encodeToString(fixture)
        assertEquals(Json.parseToJsonElement(document), Json.parseToJsonElement(JToon.decodeToJson(render(document))))
        assertEquals(Refinement.Refined(document), renderCliOutput(document, CliOutputFormat.JSON))
    }

    @Test
    fun `invalid JSON rejects without a success-shaped fallback`() {
        assertEquals(
            Refinement.Rejected(CliOutputFailure.OUTPUT_ENCODING_REJECTED),
            renderCliOutput("{", CliOutputFormat.TOON),
        )
    }

    @Test
    fun `environment admission accepts only the documented switch values`() {
        for (value in listOf(null, "", "0")) assertEquals(
            Refinement.Refined(CliOutputFormat.JSON),
            selectCliOutputFormat(value),
        )
        assertEquals(Refinement.Refined(CliOutputFormat.TOON), selectCliOutputFormat("1"))
        for (value in listOf("true", "2", " 1", "toon")) {
            assertEquals(Refinement.Rejected(CliOutputFailure.INVALID_OUTPUT_FORMAT), selectCliOutputFormat(value))
        }
    }

    private fun render(document: String): String =
        when (val result = renderCliOutput(document, CliOutputFormat.TOON)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> error("Unexpected encoding rejection: ${result.failure}")
        }
}

@Serializable private data class Users(val users: List<User>)

@Serializable private data class User(val id: Int, val name: String, val role: String)

@Serializable
private data class Evidence(
    val ref: String,
    val source: String,
    val generation: Long,
    val missing: String? = null,
    val empty: List<String> = emptyList(),
    val complete: Boolean = false,
)
