package io.github.amichne.kast.cli.publicshell

import dev.toonformat.jtoon.JToon
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir

/** Embedded JVM jq over actual native documents with PATH empty; local plans establish no compiler semantics. */
@Tag("public-launcher")
class NativeJqCompositionTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) lateinit var temporary: Path

    @Test
    fun `native default is JSON for projected values and structured failures with PATH empty`() {
        NativePublicFixture(temporary).use { fixture ->
            val base = listOf("query", "plan", "--name", "Order")
            val success = fixture.start("default-json", base + jq("{type}"), environment = mapOf("PATH" to ""))
            fixture.assertExit(success, 0, "default-json")
            val value = Json.parseToJsonElement(Files.readString(temporary.resolve("default-json.stdout"))).jsonObject
            assertEquals("planned", value.getValue("type").jsonPrimitive.content)
            val rejected = fixture.start("default-error", base + jq("empty"), environment = mapOf("PATH" to ""))
            fixture.assertExit(rejected, 2, "default-error")
            val error = Json.parseToJsonElement(Files.readString(temporary.resolve("default-error.stdout"))).jsonObject
            assertEquals("OUTPUT_CARDINALITY", error.getValue("failure").jsonPrimitive.content)
            val unicode = Json.encodeToString("Ω𐀀😀\uDBFF\uDFFF")
            val literal = fixture.start("unicode-program", base + jq(unicode), environment = mapOf("PATH" to ""))
            fixture.assertExit(literal, 0, "unicode-program")
            assertEquals(unicode + "\n", Files.readString(temporary.resolve("unicode-program.stdout")))
        }
    }

    @Test
    fun `native filtering preserves singleton values and exact JSON numbers before encoding`() {
        NativePublicFixture(temporary).use { fixture ->
            val plan = listOf("query", "plan", "--name", "Order", "--fields", "name,location")
            val cases =
                listOf(
                    NativeProjection(".request.output.fields", false, "[2]: name,location\n"),
                    NativeProjection(
                        "[.request.output.fields[] | select(false)]",
                        false,
                        Json.encodeToString(emptyList<String>()) + "\n",
                    ),
                    NativeProjection(
                        "{fields:.request.output.fields,empty:[],nil:null,flag:false}",
                        false,
                        "fields[2]: name,location\nempty: []\nnil: null\nflag: false\n",
                    ),
                    NativeProjection(
                        ".request.output.fields | map(ascii_upcase)",
                        true,
                        Json.encodeToString(listOf("NAME", "LOCATION")) + "\n",
                    ),
                    NativeProjection("false", false, "false\n"),
                    NativeProjection("null", false, "null\n"),
                    NativeProjection("[128512] | implode", true, Json.encodeToString("😀") + "\n"),
                    NativeProjection(
                        Json.encodeToString("😀") + " | explode | implode",
                        true,
                        Json.encodeToString("😀") + "\n",
                    ),
                    NativeProjection(Json.encodeToString("1.25") + " | fromjson", true, "1.25\n"),
                    NativeProjection(
                        Json.encodeToString("1.2345678901234567890123456789") + " | fromjson",
                        true,
                        "1.2345678901234567890123456789\n",
                    ),
                )
            cases.forEachIndexed { index, case ->
                val label = "projection-$index"
                val arguments = plan + jq(case.program) + if (case.json) listOf("--json") else emptyList()
                val child =
                    fixture.start(label, arguments, environment = mapOf("PATH" to "", "KAST_OUTPUT_TOON" to "1"))
                fixture.assertExit(child, 0, label)
                assertEquals(case.expected, Files.readString(temporary.resolve("$label.stdout")))
                assertEquals("", Files.readString(temporary.resolve("$label.stderr")))
            }
            assertFalse(Files.exists(fixture.installation.resolve("state/run/one-shot")))
        }
    }

    @Test
    fun `native filtering reports syntax runtime cardinality and TOON precision failures`() {
        NativePublicFixture(temporary).use { fixture ->
            val plan = listOf("query", "plan", "--name", "Order")
            val cases =
                listOf(
                    ".[" to "PROGRAM_INVALID",
                    ")" to "PROGRAM_INVALID",
                    "1)" to "PROGRAM_INVALID",
                    "error(\"private native payload\")" to "EVALUATION_FAILED",
                    "empty" to "OUTPUT_CARDINALITY",
                    "1,2" to "OUTPUT_CARDINALITY",
                    "1.2345678901234567890123456789" to "NUMERIC_LITERAL_LIMIT",
                    (Json.encodeToString("1.2345678901234567890123456789") + " | fromjson") to "OUTPUT_VALUE_LOSS",
                    "has(4294967296)" to "NUMERIC_LITERAL_LIMIT",
                    "setpath([4294967296]; \"changed\")" to "NUMERIC_LITERAL_LIMIT",
                    "100e2147483647" to "NUMERIC_LITERAL_LIMIT",
                    "\"\\ud800\"" to "OUTPUT_INVALID",
                    (Json.encodeToString(Json.encodeToString(listOf(9007199254740992L, 9007199254740993L))) +
                        " | fromjson | unique") to "NUMERIC_VALUE_LOSS",
                )
            cases.forEachIndexed { index, (program, failure) ->
                val label = "rejection-$index"
                val child =
                    fixture.start(
                        label,
                        plan + jq(program),
                        environment = mapOf("PATH" to "", "KAST_OUTPUT_TOON" to "1"),
                    )
                fixture.assertExit(
                    child,
                    if (failure in setOf("NUMERIC_VALUE_LOSS", "OUTPUT_VALUE_LOSS", "OUTPUT_INVALID")) 6 else 2,
                    label,
                )
                val output = Files.readString(temporary.resolve("$label.stdout"))
                val diagnostic = Files.readString(temporary.resolve("$label.stderr"))
                val document = Json.parseToJsonElement(JToon.decodeToJson(output)).jsonObject
                assertEquals("jq_rejected", document.getValue("type").jsonPrimitive.content)
                assertEquals(failure, document.getValue("failure").jsonPrimitive.content)
                assertFalse((output + diagnostic).contains("private native payload"))
            }
        }
    }

    private fun jq(program: String): List<String> = listOf("--jq", program)

    private data class NativeProjection(val program: String, val json: Boolean, val expected: String)
}
