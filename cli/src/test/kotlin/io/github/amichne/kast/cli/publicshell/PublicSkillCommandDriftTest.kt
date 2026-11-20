package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every command and code the bundled guidance teaches must exist in the shipped CLI and protocol owners. */
class PublicSkillCommandDriftTest {
    private val guidance =
        listOf("SKILL.md", "public-cli.md").associateWith {
            Files.readString(Path.of("../agent-tools/skills/kast/$it"))
        }

    // Illustrative values only; admission checks their shape, never their authority.
    private val placeholders =
        mapOf(
            "ref" to "exact:v5:AAAAAAAAAAAAAAAAAAAAAA",
            "exact_ref" to "exact:v5:AAAAAAAAAAAAAAAAAAAAAA",
            "result" to "result:v1:11111111-1111-1111-1111-111111111111",
            "next_cursor" to "1",
            "verdict" to ".",
            "handle" to ".",
            "A" to "exact:v5:AAAAAAAAAAAAAAAAAAAAAA",
            "B" to "exact:v5:AAAAAAAAAAAAAAAAAAAAAQ",
            "callersA" to "result:v1:22222222-2222-2222-2222-222222222222",
            "tested" to "result:v1:33333333-3333-3333-3333-333333333333",
        )

    @Test
    fun `every semantic command in the bundled guidance parses with the shipped CLI`() {
        val commands = guidance.values.flatMap(::semanticCommands)
        assertTrue(commands.size >= 12, "expected the guidance commands, found ${commands.size}")
        for (argv in commands) {
            val parsed = parsePublicCliArguments(argv)
            assertTrue(parsed is Refinement.Refined, "$argv -> $parsed")
            val command = (parsed as Refinement.Refined).value
            // Replay admits the query contract only; other operations are covered by the parser above.
            if (
                command is PublicCliCommand.Query &&
                    command.identity == PublicToolIdentity.QUERY_SYMBOLS &&
                    command.input is PublicCliInput.Lowered
            ) {
                val receipt = replayAdmission(argv)
                assertEquals(ReplayAdmissionType.ADMITTED, receipt.type, "$argv -> ${receipt.cause}")
            }
        }
    }

    @Test
    fun `documented exit statuses are published shell statuses`() {
        val published = PublicShellExit.entries.map { it.code }.toSet()
        val documented =
            guidance.values.flatMap { text ->
                Regex("""(?m)^\| (\d+) \|""").findAll(text).map { it.groupValues[1].toInt() } +
                    Regex("""Exit `(\d+)`""").findAll(text).map { it.groupValues[1].toInt() }
            }
        assertTrue(documented.toSet().containsAll(listOf(2, 4, 10, 124)), documented.toString())
        assertEquals(emptySet<Int>(), documented.toSet() - published)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `documented failure codes name current protocol values`() {
        val owned =
            IdeLifecycleFailure.entries.map { it.name } +
                QueryCompletionUnprovenReason.entries.map { it.name } +
                serializer<ReadRecoveryAction>().descriptor.let { descriptor ->
                    (0 until descriptor.elementsCount).map(descriptor::getElementName)
                }
        val documented =
            guidance.values.flatMap { text ->
                Regex("""(?m)^\| `([A-Za-z_]+)` \|""").findAll(text).map { it.groupValues[1] }.toList()
            }
        assertTrue("GRADLE_DISTRIBUTION_UNAVAILABLE" in documented, documented.toString())
        assertEquals(emptySet<String>(), documented.toSet() - owned.toSet())
    }

    private fun semanticCommands(markdown: String): List<List<String>> =
        Regex("```sh\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(markdown)
            .flatMap { block -> block.groupValues[1].replace("\\\n", " ").lines() }
            .map(String::trim)
            .filter { it.startsWith("kast ") }
            .map { shellWords(it).drop(1) }
            .filter { it.firstOrNull() in setOf("query", "source", "result", "diagnostic", "describe") }
            .toList()

    /** Sufficient for the guidance's quoting: bare words, single quotes, and double quotes around one variable. */
    private fun shellWords(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var started = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == ' ' -> {
                    if (started) words += word.toString()
                    word.clear()
                    started = false
                    index++
                }
                char == '\'' -> {
                    val end = line.indexOf('\'', index + 1).also { check(it > index) { "unclosed quote: $line" } }
                    word.append(line, index + 1, end)
                    started = true
                    index = end + 1
                }
                char == '"' -> {
                    val end = line.indexOf('"', index + 1).also { check(it > index) { "unclosed quote: $line" } }
                    word.append(expand(line.substring(index + 1, end), line))
                    started = true
                    index = end + 1
                }
                else -> {
                    word.append(char)
                    started = true
                    index++
                }
            }
        }
        if (started) words += word.toString()
        return words
    }

    private fun expand(quoted: String, line: String): String =
        Regex("""\$(\w+)""").replace(quoted) { match ->
            checkNotNull(placeholders[match.groupValues[1]]) { "unknown guidance variable in: $line" }
        }
}
