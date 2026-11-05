package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Mandatory real jq oracle. mise.toml owns its qualification pin; no evaluator double supplies expected values. */
class PublicJqOracleTest {
    @TempDir lateinit var temporary: Path
    private val json = Json { encodeDefaults = true }
    private val fixture = json.encodeToString(JqOracleFixture(listOf(JqOracleRow("Ada", 2), JqOracleRow("Bob", 4))))

    @Test
    fun `the independent 43 case corpus qualifies the real adapter and both encodings`() {
        val corpus =
            Json.decodeFromString<JqOracleCorpus>(
                checkNotNull(javaClass.getResource("/jq-oracle-cases.json")).readText()
            )
        assertEquals("kast.jq.oracle.v1", corpus.schema)
        assertEquals(43, corpus.cases.size)
        corpus.cases.forEach(::verifyCase)
    }

    private fun verifyCase(case: JqOracleCase) {
        verifyIndependentOracle(case)
        val admitted = admitEngine(case.program)
        if (case.expectedExit == 3) {
            assertEquals(
                PublicJqFailure.PROGRAM_INVALID,
                (admitted as Refinement.Rejected).failure.failure,
                case.name,
            )
            return
        }
        val result =
            (admitted as Refinement.Refined)
                .value
                .project(PublicPreparedOutput(case.inputJson, PublicShellExit.COMPLETE))
        if (case.expectedExit != 0) {
            val failure = (result as Refinement.Rejected).failure
            assertEquals(PublicJqFailure.EVALUATION_FAILED, failure.failure, case.name)
            assertEquals(22, failure.childExit, case.name)
            return
        }
        if (case.expectedValues.size != 1) {
            assertEquals(
                PublicJqFailure.OUTPUT_CARDINALITY,
                (result as Refinement.Rejected).failure.failure,
                case.name,
            )
            return
        }
        when (case.name) {
            "inputs_eof" ->
                assertEquals(PublicJqFailure.EVALUATION_FAILED, (result as Refinement.Rejected).failure.failure)
            "large_integer_arithmetic" -> assertEquals("9007199254740994", (result as Refinement.Refined).value.text)
            else -> verifyEncodings(case, (result as Refinement.Refined).value)
        }
    }

    private fun verifyIndependentOracle(case: JqOracleCase) {
        val executable =
            System.getenv("PATH")
                .split(java.io.File.pathSeparatorChar)
                .map { Path.of(it).resolve("jq") }
                .first { Files.isRegularFile(it) && Files.isExecutable(it) }
                .toRealPath()
        val stdout = temporary.resolve("${case.name}.oracle.json")
        val stderr = temporary.resolve("${case.name}.oracle.stderr")
        val child =
            ProcessBuilder(executable.toString(), "-c", "--", case.program)
                .directory(temporary.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start()
        try {
            child.outputStream.use { it.write(case.inputJson.toByteArray()) }
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), case.name)
            assertEquals(case.expectedExit, child.exitValue(), case.name)
            val values = Files.readAllLines(stdout).filter(String::isNotBlank).map(Json::parseToJsonElement)
            assertEquals(case.expectedValues, values, case.name)
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
        }
    }

    private fun verifyEncodings(case: JqOracleCase, projection: PublicPreparedOutput) {
        for (format in io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat.entries) {
            val rendered = presentPublicOutput(projection, format)
            assertEquals(PublicShellExit.COMPLETE, rendered.exit, case.name)
            val document =
                if (format == io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat.TOON)
                    dev.toonformat.jtoon.JToon.decodeToJson(rendered.text)
                else rendered.text
            assertEquals(case.expectedValues.single(), Json.parseToJsonElement(document), case.name)
        }
    }

    @Test
    fun `higher order projections match independently specified values`() {
        val cases =
            listOf(
                ".items | map(.name)" to Json.encodeToString(listOf("Ada", "Bob")),
                "[.items[] | select(.count > 2) | .name]" to Json.encodeToString(listOf("Bob")),
                "reduce .items[] as \$row (0; . + \$row.count)" to "6",
                ".items | sort_by(.count) | reverse | map(.name)" to Json.encodeToString(listOf("Bob", "Ada")),
                "[.items | map([.name])]" to Json.encodeToString(listOf(listOf(listOf("Ada"), listOf("Bob")))),
                "has(\"nil\")" to "true",
                "has(\"missing\")" to "false",
                ".missing" to "null",
                ".nil" to "null",
                "false" to "false",
                ".generation" to "9223372036854775807",
                "def sparse(f): map(f); .items | sparse(.name)" to Json.encodeToString(listOf("Ada", "Bob")),
                "try (.items | .wrong) catch \"type-error\"" to Json.encodeToString("type-error"),
            )
        for ((program, expected) in cases) {
            val projected = project(program)
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(projected.text), program)
            assertEquals(PublicShellExit.COMPLETE, projected.exit)
        }
    }

    @Test
    fun `Unicode implode matches independent jq and unsafe literal programs reject before execution`() {
        val points = listOf(0x10000, 0x1F600, 0x10FFFF)
        val text = points.joinToString("") { String(Character.toChars(it)) }
        for ((program, input) in
            listOf(
                "implode" to Json.encodeToString(points),
                "explode | implode" to Json.encodeToString(text),
                Json.encodeToString(text) to "null",
            )) {
            val expected = Json.encodeToString(text)
            verifyIndependentOracle(
                JqOracleCase(
                    "unicode-${program.hashCode()}",
                    program,
                    input,
                    listOf(Json.parseToJsonElement(expected)),
                    0,
                )
            )
            assertEquals(
                expected,
                (engine(program).project(PublicPreparedOutput(input, PublicShellExit.COMPLETE)) as Refinement.Refined)
                    .value
                    .text,
            )
        }
        val hasProgram = "has(4294967296)"
        val input = Json.encodeToString(listOf("only"))
        verifyIndependentOracle(
            JqOracleCase("literal-has", hasProgram, input, listOf(Json.parseToJsonElement("false")), 0)
        )
        assertEquals(
            PublicJqFailure.NUMERIC_LITERAL_LIMIT,
            (admitEngine(hasProgram) as Refinement.Rejected).failure.failure,
        )
        // Do not ask the external oracle to allocate a huge array. Admission must prevent this effect entirely.
        assertEquals(
            PublicJqFailure.NUMERIC_LITERAL_LIMIT,
            (admitEngine("setpath([4294967296]; \"changed\")") as Refinement.Rejected).failure.failure,
        )
    }

    @Test
    fun `precision requiring numeric comparisons reject while native jq retains its comparison semantics`() {
        val input = Json.encodeToString(listOf(9007199254740992L, 9007199254740993L))
        for ((program, nativeValue) in listOf(".[0] == .[1]" to "false", "unique" to input)) {
            val case =
                JqOracleCase(
                    "numeric-${program.hashCode()}",
                    program,
                    input,
                    listOf(Json.parseToJsonElement(nativeValue)),
                    0,
                )
            verifyIndependentOracle(case)
            val projected = engine(program).project(PublicPreparedOutput(input, PublicShellExit.COMPLETE))
            assertEquals(PublicJqFailure.NUMERIC_VALUE_LOSS, (projected as Refinement.Rejected).failure.failure)
        }
    }

    @Test
    fun `generators require explicit collection and runtime errors never publish a prefix`() {
        for (program in listOf("empty", ".items[].name")) {
            val result = engine(program).project(PublicPreparedOutput(fixture, PublicShellExit.COMPLETE))
            assertEquals(PublicJqFailure.OUTPUT_CARDINALITY, (result as Refinement.Rejected).failure.failure)
        }
        val failed =
            engine("1, error(\"private payload\")").project(PublicPreparedOutput(fixture, PublicShellExit.COMPLETE))
                as Refinement.Rejected
        assertEquals(PublicJqFailure.EVALUATION_FAILED, failed.failure.failure)
        assertEquals(22, failed.failure.childExit)
        assertFalse(failed.failure.prepared().text.contains("private payload"))
        assertEquals(Json.encodeToString(emptyList<String>()), project("[.items[] | select(.count < 0)]").text)
    }

    @Test
    fun `exact decimal remains JSON and rejects lossy TOON`() {
        val decimal = "1.2345678901234567890123456789"
        val projected =
            ((engine(".").project(PublicPreparedOutput(decimal, PublicShellExit.COMPLETE))) as Refinement.Refined).value
        assertEquals(
            decimal,
            presentPublicOutput(
                    projected,
                    io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat.JSON,
                )
                .text,
        )
        val toon =
            presentPublicOutput(
                projected,
                io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat.TOON,
            )
        assertEquals(PublicShellExit.PROTOCOL, toon.exit)
        assertTrue(toon.text.contains("OUTPUT_VALUE_LOSS"), toon.text)
        assertTrue(toon.text.contains("--json"), toon.text)
    }

    @Test
    fun `shell metacharacters are literal program data and never commands`() {
        val marker = temporary.resolve("must-not-exist")
        val literal = "\$(touch $marker)"
        assertEquals(Json.encodeToString(literal), project(Json.encodeToString(literal)).text)
        assertFalse(Files.exists(marker))
    }

    private fun project(program: String): PublicPreparedOutput =
        (engine(program).project(PublicPreparedOutput(fixture, PublicShellExit.COMPLETE)) as Refinement.Refined).value

    private fun engine(program: String): PublicJqEngine {
        return when (val admitted = admitEngine(program)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected ->
                error("Real jq oracle unavailable: ${admitted.failure}. Run tests through mise exec.")
        }
    }

    private fun admitEngine(program: String): Refinement<PublicJqEngine, PublicJqRejection> {
        val command =
            (parsePublicCliArguments(listOf("query", "plan", "--name", "Order", "--jq", program)) as Refinement.Refined)
                .value
        val options = (command.projection as PublicOutputProjection.Jq).options
        return PublicJqEngine.prepare(options, temporary)
    }
}

@Serializable
private data class JqOracleCorpus(
    val schema: String,
    val oracle: String,
    @SerialName("fixture_kind") val fixtureKind: String,
    val cases: List<JqOracleCase>,
)

@Serializable
private data class JqOracleCase(
    val name: String,
    val program: String,
    @SerialName("input_json") val inputJson: String,
    /** Independent expected values are dynamic JSON; these fixtures do not claim canonical query admission. */
    @SerialName("expected_values") val expectedValues: List<JsonElement>,
    @SerialName("expected_exit") val expectedExit: Int,
)

@Serializable
private data class JqOracleFixture(
    val items: List<JqOracleRow>,
    val nil: String? = null,
    val generation: Long = Long.MAX_VALUE,
)

@Serializable private data class JqOracleRow(val name: String, val count: Int)
